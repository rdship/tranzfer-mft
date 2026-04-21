package com.filetransfer.sftp.server;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class VfsSftpFileSystemAccessor implements SftpFileSystemAccessor {

    @Override
    public SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle fileHandle,
                                         Path file, String handle,
                                         Set<? extends OpenOption> options,
                                         FileAttribute<?>... attrs) throws IOException {
        // R134H — tester R134F-G found SFTP uploads succeed at the audit layer
        // but never produce an event_outbox row or flow_executions row. R134G's
        // hypothesis (VirtualWriteChannel.close() null callback) was disproved
        // because that log never fired. Prime suspect now: MINA is routing the
        // write through the DEFAULT filesystem (not our VirtualSftpFileSystem),
        // so the write doesn't reach the VFS provider at all. Log the path +
        // filesystem + provider classes on every open so the tester's next run
        // tells us exactly which FS MINA hands us.
        log.info("[sftp-accessor] openFile path={} class={} fs={} provider={} options={}",
                file, file.getClass().getSimpleName(),
                file.getFileSystem().getClass().getSimpleName(),
                file.getFileSystem().provider().getClass().getSimpleName(),
                options);
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
