package com.filetransfer.forwarder.transfer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream wrapper that records every read as transfer progress.
 *
 * <p>Used by FTP/FTPS forwarders where the underlying library (Apache Commons Net)
 * reads from our InputStream to write to the remote server. Each successful read
 * updates the {@link TransferSession}, keeping the inactivity clock fresh.
 *
 * <p>If the watchdog has flagged this transfer as stalled (typically because the
 * remote write blocked and no reads occurred), subsequent reads throw an IOException
 * to abort the transfer cleanly.
 */
public class ProgressTrackingInputStream extends FilterInputStream {

    private final TransferSession session;

    public ProgressTrackingInputStream(InputStream source, TransferSession session) {
        super(source);
        this.session = session;
    }

    @Override
    public int read() throws IOException {
        checkStalled();
        int b = super.read();
        if (b >= 0) {
            session.recordProgress(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        checkStalled();
        int bytesRead = super.read(buf, off, len);
        if (bytesRead > 0) {
            session.recordProgress(bytesRead);
        }
        return bytesRead;
    }

    private void checkStalled() throws IOException {
        if (session.isStalled()) {
            throw new IOException("Transfer stalled — no data activity for >"
                    + session.getIdleSeconds() + "s (transfer: " + session.getTransferId() + ")");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Transfer interrupted (transfer: " + session.getTransferId() + ")");
        }
    }
}
