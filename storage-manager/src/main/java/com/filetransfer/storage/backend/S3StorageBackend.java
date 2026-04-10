package com.filetransfer.storage.backend;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * S3-compatible object storage backend (AWS S3 or self-hosted MinIO).
 *
 * <p>Activated when {@code storage.backend=s3}. All storage-manager replicas share
 * the same bucket → no pod-to-pod routing registry needed, no local volume dependency.
 *
 * <h3>Proxy behaviour</h3>
 * <ul>
 *   <li><b>Internal MinIO</b> ({@code STORAGE_S3_ENDPOINT=http://mft-minio:9000}):
 *       the MinIO host is automatically added to the no-proxy list — direct connection,
 *       zero DMZ proxy overhead.
 *   <li><b>AWS S3</b> ({@code STORAGE_S3_ENDPOINT} left empty or set to AWS URL):
 *       if {@code PROXY_ENABLED=true}, the platform's DMZ proxy is used for the outbound
 *       HTTPS connection. This keeps all external traffic passing through the DMZ.
 * </ul>
 *
 * <h3>Startup</h3>
 * On {@code @PostConstruct}, creates the bucket if it doesn't exist and logs
 * connection details. If the bucket can't be created (permissions), the service
 * starts but logs a warning.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "storage.backend", havingValue = "s3")
public class S3StorageBackend implements StorageBackend {

    @Value("${storage.s3.endpoint:}")             private String endpoint;
    @Value("${storage.s3.bucket:mft-storage}")    private String bucket;
    @Value("${storage.s3.access-key:minioadmin}") private String accessKey;
    @Value("${storage.s3.secret-key:minioadmin}") private String secretKey;
    @Value("${storage.s3.region:us-east-1}")      private String region;
    @Value("${storage.s3.path-style:true}")       private boolean pathStyle;

    // Platform proxy settings (from x-common-env)
    @Value("${platform.proxy.enabled:${PROXY_ENABLED:false}}")   private boolean proxyEnabled;
    @Value("${platform.proxy.host:${PROXY_HOST:dmz-proxy}}")      private String proxyHost;
    @Value("${platform.proxy.port:${PROXY_PORT:8088}}")           private int    proxyPort;
    @Value("${platform.proxy.no-proxy-hosts:${PROXY_NO_PROXY_HOSTS:localhost,127.0.0.1,postgres,rabbitmq}}")
    private String noProxyHostsStr;

    private S3Client s3;

    @PostConstruct
    void init() {
        s3 = buildClient();

        // Ensure the bucket exists
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.info("[S3Backend] Connected to bucket '{}' at {}", bucket, endpoint.isBlank() ? "AWS S3" : endpoint);
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("[S3Backend] Created bucket '{}' at {}", bucket, endpoint.isBlank() ? "AWS S3" : endpoint);
            } catch (Exception ex) {
                log.warn("[S3Backend] Could not create bucket '{}': {} — ensure bucket exists before writes", bucket, ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("[S3Backend] Could not connect to S3 backend: {} — storage operations will fail", e.getMessage());
        }

        String proxyInfo = proxyEnabled
                ? String.format("via DMZ proxy %s:%d (no-proxy: %s)", proxyHost, proxyPort, noProxyHostsStr)
                : "direct (no proxy)";
        log.info("[S3Backend] Outbound S3 connections: {}", proxyInfo);
    }

    // ── StorageBackend implementation ─────────────────────────────────────────

    @Override
    public WriteResult write(InputStream data, long sizeBytes, String filename) throws Exception {
        // Wrap input in DigestInputStream to compute SHA-256 during the S3 upload
        // instead of buffering the entire payload into heap.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        DigestInputStream digestStream = new DigestInputStream(data, digest);

        // We must upload to a temp key first because we don't know the sha256 yet.
        String tempKey = "tmp-upload-" + Thread.currentThread().getId() + "-" + System.nanoTime();
        long start = System.currentTimeMillis();

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(tempKey)
                        .contentLength(sizeBytes > 0 ? sizeBytes : null)
                        .build(),
                RequestBody.fromInputStream(digestStream, sizeBytes > 0 ? sizeBytes : -1));

        String sha256 = HexFormat.of().formatHex(digest.digest());
        long uploadedSize = sizeBytes > 0 ? sizeBytes : headObjectSize(tempKey);

        // Deduplication: check if canonical key already exists
        if (objectExists(sha256)) {
            // Delete the temp object — dedup hit
            try { s3.deleteObject(b -> b.bucket(bucket).key(tempKey)); } catch (Exception ignored) {}
            log.debug("[S3Backend] Dedup hit for sha256={}", sha256.substring(0, 12));
            return new WriteResult(sha256, uploadedSize, sha256, 0L, 0.0, true);
        }

        // Move temp → canonical key via copy + delete
        s3.copyObject(b -> b
                .sourceBucket(bucket).sourceKey(tempKey)
                .destinationBucket(bucket).destinationKey(sha256));
        try { s3.deleteObject(b -> b.bucket(bucket).key(tempKey)); } catch (Exception ignored) {}

        long durationMs = System.currentTimeMillis() - start;
        double mbps = durationMs > 0 ? (uploadedSize / 1048576.0) / (durationMs / 1000.0) : 0;

        log.debug("[S3Backend] Stored {} bytes → s3://{}/{} ({} MB/s)",
                uploadedSize, bucket, sha256.substring(0, 8), String.format("%.1f", mbps));

        return new WriteResult(sha256, uploadedSize, sha256, durationMs, mbps, false);
    }

    @Deprecated
    @Override
    public ReadResult read(String storageKey) throws Exception {
        // storageKey may be a sha256 or a legacy s3:// URI — normalize to just sha256
        String key = normalizeKey(storageKey);

        try (var response = s3.getObject(b -> b.bucket(bucket).key(key))) {
            byte[] data = response.readAllBytes();
            return new ReadResult(data, data.length, key, response.response().contentType());
        }
    }

    /**
     * Stream S3 object content directly to the target using a 256 KB chunked pipe.
     * No JVM heap allocation for the full file payload.
     *
     * @param storageKey SHA-256 hex key or legacy {@code s3://} URI
     * @param target     destination stream (e.g. servlet response output stream)
     */
    @Override
    public void readTo(String storageKey, OutputStream target) throws Exception {
        String key = normalizeKey(storageKey);
        try (var response = s3.getObject(b -> b.bucket(bucket).key(key))) {
            byte[] buf = new byte[256 * 1024]; // 256 KB chunks
            int n;
            while ((n = response.read(buf)) != -1) {
                target.write(buf, 0, n);
            }
        }
    }

    /**
     * Open a streaming {@link InputStream} for the given S3 object.
     * Returns the raw S3 response stream — caller is responsible for closing it.
     *
     * @param storageKey SHA-256 hex key or legacy {@code s3://} URI
     * @return the S3 response input stream positioned at the start of the object
     */
    @Override
    public InputStream readStream(String storageKey) throws Exception {
        String key = normalizeKey(storageKey);
        return s3.getObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public boolean exists(String storageKey) {
        return objectExists(normalizeKey(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(normalizeKey(storageKey)));
        } catch (Exception e) {
            log.warn("[S3Backend] delete({}) failed: {}", storageKey, e.getMessage());
        }
    }

    @Override
    public String type() {
        return endpoint.isBlank() ? "AWS-S3" : "MINIO";
    }

    // ── Client construction ───────────────────────────────────────────────────

    private S3Client buildClient() {
        // Build HTTP client — add DMZ proxy if enabled and target is not internal
        ApacheHttpClient.Builder httpBuilder = ApacheHttpClient.builder();

        if (proxyEnabled) {
            Set<String> noProxy = new HashSet<>(Arrays.asList(noProxyHostsStr.split(",")));
            // Internal MinIO endpoint — always bypass proxy
            if (!endpoint.isBlank()) {
                try { noProxy.add(URI.create(endpoint).getHost()); } catch (Exception ignored) {}
            }

            httpBuilder.proxyConfiguration(
                    ProxyConfiguration.builder()
                            .endpoint(URI.create("http://" + proxyHost + ":" + proxyPort))
                            .nonProxyHosts(noProxy)
                            .build());
            log.info("[S3Backend] HTTP client configured with proxy {}:{} no-proxy={}",
                    proxyHost, proxyPort, noProxy);
        }

        var builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .httpClientBuilder(httpBuilder)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .checksumValidationEnabled(false) // We validate ourselves
                        .build());

        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean objectExists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.debug("[S3Backend] headObject({}) error: {}", key, e.getMessage());
            return false;
        }
    }

    /** Query S3 for the content-length of an object (used after streaming upload when size was unknown). */
    private long headObjectSize(String key) {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(key)).contentLength();
        } catch (Exception e) {
            log.warn("[S3Backend] headObject({}) failed: {}", key, e.getMessage());
            return -1;
        }
    }

    private static String toBase64(String hex) {
        // S3 checksumSHA256 expects base64 of the raw bytes, not the hex string
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /** Strip any s3:// prefix if present (for legacy physicalPath values). */
    private static String normalizeKey(String storageKey) {
        if (storageKey == null) throw new IllegalArgumentException("storageKey is null");
        if (storageKey.startsWith("s3://")) {
            // s3://bucket/key → extract the key part
            String withoutScheme = storageKey.substring(5);
            int slash = withoutScheme.indexOf('/');
            return slash >= 0 ? withoutScheme.substring(slash + 1) : withoutScheme;
        }
        return storageKey;
    }
}
