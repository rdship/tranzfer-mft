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
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
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
        byte[] bytes = readFully(data, sizeBytes);
        String sha256 = computeSha256(bytes);
        long start = System.currentTimeMillis();

        // Deduplication: check if object already exists
        if (objectExists(sha256)) {
            log.debug("[S3Backend] Dedup hit for sha256={}", sha256.substring(0, 12));
            return new WriteResult(sha256, bytes.length, sha256, 0L, 0.0, true);
        }

        // Store with SHA-256 as key
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(sha256)
                        .contentLength((long) bytes.length)
                        .checksumSHA256(toBase64(sha256))
                        .build(),
                RequestBody.fromBytes(bytes));

        long durationMs = System.currentTimeMillis() - start;
        double mbps = durationMs > 0 ? (bytes.length / 1048576.0) / (durationMs / 1000.0) : 0;

        log.debug("[S3Backend] Stored {} bytes → s3://{}/{} ({} MB/s)",
                bytes.length, bucket, sha256.substring(0, 8), String.format("%.1f", mbps));

        return new WriteResult(sha256, bytes.length, sha256, durationMs, mbps, false);
    }

    @Override
    public ReadResult read(String storageKey) throws Exception {
        // storageKey may be a sha256 or a legacy s3:// URI — normalize to just sha256
        String key = normalizeKey(storageKey);

        try (var response = s3.getObject(b -> b.bucket(bucket).key(key))) {
            byte[] data = response.readAllBytes();
            return new ReadResult(data, data.length, key, response.response().contentType());
        }
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

    private static byte[] readFully(InputStream in, long sizeHint) throws IOException {
        if (sizeHint > 0 && sizeHint <= Integer.MAX_VALUE) {
            return in.readNBytes((int) sizeHint);
        }
        return in.readAllBytes();
    }

    private static String computeSha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
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
