package com.filetransfer.shared.vfs;

/**
 * AutoCloseable handle returned by {@link VirtualFileSystem#lockPath(java.util.UUID, String)}.
 *
 * <p><b>R134AC:</b> extracted from the now-deleted {@code DistributedVfsLock}
 * (Redis SETNX lock, retired in Sprint 9 Phase 1). The lock-acquisition path
 * collapses from three tiers (storage-coord → Redis → pg_advisory) to two
 * (storage-coord primary, pg_advisory_xact_lock last-resort). Callers
 * continue to interact with the lock purely through this close-able handle.
 */
@FunctionalInterface
public interface VfsLockHandle extends AutoCloseable {

    @Override
    void close();

    /** No-op handle used when the lock is session-scoped (pg_advisory_xact_lock auto-releases on tx end). */
    VfsLockHandle NOOP = () -> { };
}
