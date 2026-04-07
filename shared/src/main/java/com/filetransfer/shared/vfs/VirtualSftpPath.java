package com.filetransfer.shared.vfs;

import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Virtual path for the Phantom Folder SFTP filesystem.
 * Backed by database, not physical disk.
 */
public class VirtualSftpPath implements Path {

    private final VirtualSftpFileSystem fileSystem;
    private final String path;
    private final String[] components;
    private final boolean absolute;

    public VirtualSftpPath(VirtualSftpFileSystem fileSystem, String path) {
        this.fileSystem = fileSystem;
        String normalized = path == null || path.isEmpty() ? "/" : path.replace('\\', '/');
        // Remove double slashes
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        this.absolute = normalized.startsWith("/");
        // Remove leading and trailing /
        String trimmed = normalized;
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        this.components = trimmed.isEmpty() ? new String[0] : trimmed.split("/");
        // Store the normalized path
        this.path = absolute ? "/" + String.join("/", components) : String.join("/", components);
    }

    private VirtualSftpPath(VirtualSftpFileSystem fileSystem, String[] components, boolean absolute) {
        this.fileSystem = fileSystem;
        this.components = components;
        this.absolute = absolute;
        String joined = String.join("/", components);
        this.path = absolute ? "/" + joined : joined;
    }

    /** Get the full normalized path string. */
    public String getPathString() {
        return components.length == 0 && absolute ? "/" : path;
    }

    @Override
    public FileSystem getFileSystem() { return fileSystem; }

    @Override
    public boolean isAbsolute() { return absolute; }

    @Override
    public Path getRoot() {
        return absolute ? new VirtualSftpPath(fileSystem, "/") : null;
    }

    @Override
    public Path getFileName() {
        if (components.length == 0) return null;
        return new VirtualSftpPath(fileSystem, new String[]{components[components.length - 1]}, false);
    }

    @Override
    public Path getParent() {
        if (components.length == 0) return null;
        if (components.length == 1) return absolute ? getRoot() : null;
        return new VirtualSftpPath(fileSystem, Arrays.copyOf(components, components.length - 1), absolute);
    }

    @Override
    public int getNameCount() { return components.length; }

    @Override
    public Path getName(int index) {
        if (index < 0 || index >= components.length) throw new IllegalArgumentException("Index: " + index);
        return new VirtualSftpPath(fileSystem, new String[]{components[index]}, false);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > components.length || beginIndex >= endIndex)
            throw new IllegalArgumentException();
        return new VirtualSftpPath(fileSystem, Arrays.copyOfRange(components, beginIndex, endIndex), false);
    }

    @Override
    public boolean startsWith(Path other) {
        if (!(other instanceof VirtualSftpPath o)) return false;
        if (absolute != o.absolute) return false;
        if (o.components.length > components.length) return false;
        for (int i = 0; i < o.components.length; i++) {
            if (!components[i].equals(o.components[i])) return false;
        }
        return true;
    }

    @Override
    public boolean endsWith(Path other) {
        if (!(other instanceof VirtualSftpPath o)) return false;
        if (o.absolute && !this.equals(o)) return false;
        if (o.components.length > components.length) return false;
        int offset = components.length - o.components.length;
        for (int i = 0; i < o.components.length; i++) {
            if (!components[offset + i].equals(o.components[i])) return false;
        }
        return true;
    }

    @Override
    public Path normalize() {
        // Remove . and .. components
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String c : components) {
            if (".".equals(c)) continue;
            if ("..".equals(c)) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(c);
            }
        }
        return new VirtualSftpPath(fileSystem, stack.toArray(new String[0]), absolute);
    }

    @Override
    public Path resolve(Path other) {
        if (other.isAbsolute()) return other;
        VirtualSftpPath o = (VirtualSftpPath) other;
        if (o.components.length == 0) return this;
        String[] merged = new String[components.length + o.components.length];
        System.arraycopy(components, 0, merged, 0, components.length);
        System.arraycopy(o.components, 0, merged, components.length, o.components.length);
        return new VirtualSftpPath(fileSystem, merged, absolute);
    }

    @Override
    public Path resolve(String other) {
        return resolve(new VirtualSftpPath(fileSystem, other));
    }

    @Override
    public Path relativize(Path other) {
        VirtualSftpPath o = (VirtualSftpPath) other;
        if (absolute != o.absolute) throw new IllegalArgumentException("Cannot relativize mixed absolute/relative paths");
        // Find common prefix
        int common = 0;
        int max = Math.min(components.length, o.components.length);
        while (common < max && components[common].equals(o.components[common])) common++;
        // Build relative path: .. for each remaining in this, then append remaining from other
        int ups = components.length - common;
        int remaining = o.components.length - common;
        String[] result = new String[ups + remaining];
        Arrays.fill(result, 0, ups, "..");
        System.arraycopy(o.components, common, result, ups, remaining);
        return new VirtualSftpPath(fileSystem, result, false);
    }

    @Override
    public URI toUri() {
        return URI.create("vfs://" + fileSystem.getAccountId() + getPathString());
    }

    @Override
    public Path toAbsolutePath() {
        if (absolute) return this;
        return new VirtualSftpPath(fileSystem, components, true);
    }

    @Override
    public Path toRealPath(LinkOption... options) {
        return toAbsolutePath().normalize();
    }

    @Override
    public File toFile() { throw new UnsupportedOperationException("Virtual filesystem has no physical files"); }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("Watch not supported on virtual filesystem");
    }

    @Override
    public Iterator<Path> iterator() {
        return new Iterator<>() {
            private int index = 0;
            public boolean hasNext() { return index < components.length; }
            public Path next() {
                if (!hasNext()) throw new NoSuchElementException();
                return getName(index++);
            }
        };
    }

    @Override
    public int compareTo(Path other) {
        return getPathString().compareTo(((VirtualSftpPath) other).getPathString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VirtualSftpPath that)) return false;
        return absolute == that.absolute && Arrays.equals(components, that.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(absolute, Arrays.hashCode(components));
    }

    @Override
    public String toString() { return getPathString(); }
}
