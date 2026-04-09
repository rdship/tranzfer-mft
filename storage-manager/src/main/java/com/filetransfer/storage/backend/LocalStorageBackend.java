package com.filetransfer.storage.backend;

import com.filetransfer.storage.engine.ParallelIOEngine;
import com.filetransfer.storage.registry.StorageLocationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local filesystem backend — the default.
 *
 * <p>Files are written to {@code {storage.hot.path}/{sha256}} (content-addressed).
 * For backward compatibility, {@link #read(String)} also accepts absolute paths
 * from legacy {@code StorageObject.physicalPath} records.
 *
 * <p>Correct for:
 * <ul>
 *   <li>Single-instance deployments
 *   <li>NFS/CIFS shared mounts (all pods see the same {@code /data/storage})
 *   <li>Kubernetes ReadWriteMany PVCs
 * </ul>
 *
 * <p>NOT correct for plain multi-replica Docker deployments — use {@link S3StorageBackend}
 * with MinIO instead.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "storage.backend", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalStorageBackend implements StorageBackend {

    private final ParallelIOEngine ioEngine;

    @Autowired(required = false)
    @Nullable
    private StorageLocationRegistry locationRegistry;

    @Value("${storage.hot.path:/data/storage/hot}")
    private String hotPath;

    @Override
    public WriteResult write(InputStream data, long sizeBytes, String filename) throws Exception {
        // CAS path: use SHA-256 as filename — deterministic, dedup-friendly
        // We write to a temp file first so the engine can compute the hash,
        // then rename to {sha256} so the path is content-addressed.
        Path dest = Paths.get(hotPath, "tmp-" + Thread.currentThread().getId() + "-" + System.nanoTime());

        long start = System.currentTimeMillis();
        ParallelIOEngine.WriteResult r = ioEngine.write(data, dest, sizeBytes);
        long durationMs = System.currentTimeMillis() - start;

        // Rename to SHA-256 based path (idempotent CAS)
        Path casPath = Paths.get(hotPath, r.getSha256());
        if (Files.exists(casPath)) {
            Files.deleteIfExists(dest); // Dedup — content already stored
            if (locationRegistry != null) locationRegistry.register(r.getSha256());
            return new WriteResult(r.getSha256(), r.getSizeBytes(), r.getSha256(),
                    durationMs, r.getThroughputMbps(), true);
        }
        Files.move(dest, casPath);

        if (locationRegistry != null) locationRegistry.register(r.getSha256());
        log.debug("[LocalBackend] Stored {} → {} ({} MB/s)", r.getSha256().substring(0, 8), casPath, String.format("%.1f", r.getThroughputMbps()));

        return new WriteResult(r.getSha256(), r.getSizeBytes(), r.getSha256(),
                durationMs, r.getThroughputMbps(), false);
    }

    @Override
    public ReadResult read(String storageKey) throws Exception {
        Path path = resolvePath(storageKey);
        ParallelIOEngine.ReadResult r = ioEngine.read(path);
        return new ReadResult(r.getData(), r.getData().length, storageKey, null);
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolvePath(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolvePath(storageKey));
            if (locationRegistry != null) locationRegistry.deregister(storageKey);
        } catch (Exception e) {
            log.warn("[LocalBackend] delete({}) failed: {}", storageKey, e.getMessage());
        }
    }

    @Override
    public String type() { return "LOCAL"; }

    /**
     * Resolve a storage key to a physical path.
     * Supports both the new CAS scheme ({sha256} in hotPath) and legacy absolute paths.
     */
    private Path resolvePath(String storageKey) {
        if (storageKey == null) throw new IllegalArgumentException("storageKey is null");
        Path p = Paths.get(storageKey);
        if (p.isAbsolute()) return p;          // Legacy physicalPath from old StorageObject rows
        return Paths.get(hotPath, storageKey); // New CAS scheme: {hotPath}/{sha256}
    }
}
