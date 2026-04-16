package com.filetransfer.gateway.server;

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
 * Routes SFTP file I/O through the path's own FileSystemProvider instead of
 * FileChannel.open(). Ensures VFS-backed paths use VirtualWriteChannel.
 * Same logic as sftp-service's copy — kept separate to avoid pulling MINA
 * SFTP dependency into shared-platform.
 */
public class VfsSftpFileSystemAccessor implements SftpFileSystemAccessor {

    @Override
    public SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle fileHandle,
                                         Path file, String handle,
                                         Set<? extends OpenOption> options,
                                         FileAttribute<?>... attrs) throws IOException {
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
