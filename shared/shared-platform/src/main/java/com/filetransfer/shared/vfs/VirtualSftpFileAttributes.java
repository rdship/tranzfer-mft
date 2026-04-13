package com.filetransfer.shared.vfs;

import com.filetransfer.shared.entity.vfs.VirtualEntry;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/**
 * File attributes backed by a VirtualEntry from the database.
 */
public class VirtualSftpFileAttributes implements BasicFileAttributes {

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

    public VirtualEntry getEntry() { return entry; }
}
