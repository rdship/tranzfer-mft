package com.filetransfer.shared.vfs;

import com.filetransfer.shared.entity.vfs.VirtualEntry;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.Set;

/**
 * File attributes backed by a VirtualEntry from the database.
 *
 * <p>Implements BOTH {@link BasicFileAttributes} and {@link PosixFileAttributes}
 * so that {@code provider.readAttributes(path, PosixFileAttributes.class)}
 * casts cleanly. Apache MINA SSHD's SFTP subsystem requests POSIX attributes
 * for each entry while building SSH_FXP_NAME responses — if the cast in
 * {@code VirtualSftpFileSystemProvider.readAttributes(Path, Class, LinkOption...)}
 * fails with ClassCastException, MINA catches it and returns
 * {@code SSH_FX_OP_UNSUPPORTED}, which the OpenSSH client renders as
 * "Couldn't read directory: Operation unsupported".</p>
 *
 * <p>Owner/group are synthetic ({@code "vfs"}), permissions are fixed
 * (0755 for directories, 0644 for files). VFS has no per-entry ACL concept —
 * access is controlled at the ServerInstance / listener / transfer-account
 * level, not at filesystem attribute level.</p>
 */
public class VirtualSftpFileAttributes implements BasicFileAttributes, PosixFileAttributes {

    private static final UserPrincipal VFS_OWNER = () -> "vfs";
    private static final GroupPrincipal VFS_GROUP = () -> "vfs";
    private static final Set<PosixFilePermission> DIR_PERMS  =
            PosixFilePermissions.fromString("rwxr-xr-x");
    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-r--r--");

    private final VirtualEntry entry;

    public VirtualSftpFileAttributes(VirtualEntry entry) {
        this.entry = entry;
    }

    /** Synthetic root directory attributes (for "/"). */
    public static VirtualSftpFileAttributes rootDirectory() {
        VirtualEntry root = VirtualEntry.builder()
                .type(VirtualEntry.EntryType.DIR)
                .sizeBytes(0)
                .permissions("rwxr-xr-x")
                .build();
        root.setCreatedAt(Instant.EPOCH);
        root.setUpdatedAt(Instant.now());
        return new VirtualSftpFileAttributes(root);
    }

    // ── BasicFileAttributes ─────────────────────────────────────────────────

    @Override
    public FileTime lastModifiedTime() {
        Instant ts = entry.getUpdatedAt() != null ? entry.getUpdatedAt() : Instant.now();
        return FileTime.from(ts);
    }

    @Override
    public FileTime lastAccessTime() {
        Instant ts = entry.getLastAccessedAt() != null ? entry.getLastAccessedAt()
                : entry.getUpdatedAt() != null ? entry.getUpdatedAt() : Instant.now();
        return FileTime.from(ts);
    }

    @Override
    public FileTime creationTime() {
        Instant ts = entry.getCreatedAt() != null ? entry.getCreatedAt() : Instant.now();
        return FileTime.from(ts);
    }

    @Override
    public boolean isRegularFile() { return entry.isFile(); }

    @Override
    public boolean isDirectory() { return entry.isDirectory(); }

    @Override
    public boolean isSymbolicLink() { return false; }

    @Override
    public boolean isOther() { return false; }

    @Override
    public long size() { return entry.getSizeBytes(); }

    @Override
    public Object fileKey() { return entry.getId(); }

    // ── PosixFileAttributes ─────────────────────────────────────────────────

    @Override
    public UserPrincipal owner() { return VFS_OWNER; }

    @Override
    public GroupPrincipal group() { return VFS_GROUP; }

    @Override
    public Set<PosixFilePermission> permissions() {
        return isDirectory() ? DIR_PERMS : FILE_PERMS;
    }

    public VirtualEntry getEntry() { return entry; }
}
