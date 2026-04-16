package com.filetransfer.sftp.server;

import com.filetransfer.shared.vfs.VirtualSftpFileSystem;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

/**
 * Custom SftpFileSystemAccessor that routes file I/O through the VFS provider.
 *
 * <p>MINA's default accessor calls {@code FileChannel.open()} directly, bypassing
 * our {@code VirtualSftpFileSystemProvider.newByteChannel()}. This means VFS writes
 * never reach MinIO — bytes go to local disk or fail silently.
 *
 * <p>This accessor delegates to the path's own {@code FileSystemProvider} which,
 * for VIRTUAL accounts, is {@code VirtualSftpFileSystemProvider} and returns a
 * {@code VirtualWriteChannel} that stores to MinIO on close.
 *
 * <p>For PHYSICAL accounts (standard filesystem), the provider's newByteChannel()
 * returns a normal FileChannel — same behavior as the default accessor.
 */
public class VfsSftpFileSystemAccessor implements SftpFileSystemAccessor {

    @Override
    public SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle fileHandle,
                                         Path file, String handle,
                                         Set<? extends OpenOption> options,
                                         FileAttribute<?>... attrs) throws IOException {
        // Delegate to the path's filesystem provider — this calls
        // VirtualSftpFileSystemProvider.newByteChannel() for VIRTUAL accounts
        return file.getFileSystem().provider().newByteChannel(file, options, attrs);
    }

    @Override
    public void closeFile(SftpSubsystemProxy subsystem, FileHandle fileHandle,
                           Path file, String handle, Channel channel,
                           Set<? extends OpenOption> options) throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }
}
