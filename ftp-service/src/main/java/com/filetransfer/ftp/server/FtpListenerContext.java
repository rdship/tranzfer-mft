package com.filetransfer.ftp.server;

import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;

/**
 * Per-listener identity for Apache FtpServer.
 *
 * <p>Unlike Apache MINA SSHD (which supports session attributes natively),
 * Apache FtpServer's {@code UserManager.authenticate()} callback has no
 * direct handle to the arriving connection. We bridge this with a thread-local
 * populated by {@link Ftplet} — the Ftplet chain runs on the same thread as
 * authenticate, so setting a ThreadLocal in {@code beforeCommand} is visible
 * when {@code UserManager.authenticate} fires for USER/PASS.</p>
 *
 * <p>Each FtpServer built by {@link FtpServerBuilder} installs its own
 * {@link Ftplet} carrying the {@link com.filetransfer.shared.entity.core.ServerInstance}'s
 * {@code instanceId} + {@code defaultStorageMode}.</p>
 */
public final class FtpListenerContext {

    private static final ThreadLocal<String> INSTANCE_ID  = new ThreadLocal<>();
    private static final ThreadLocal<String> STORAGE_MODE = new ThreadLocal<>();

    public static String instanceId()  { return INSTANCE_ID.get(); }
    public static String storageMode() { return STORAGE_MODE.get(); }

    public static void clear() {
        INSTANCE_ID.remove();
        STORAGE_MODE.remove();
    }

    private FtpListenerContext() {}

    /**
     * Install one of these as the FIRST Ftplet on each per-listener
     * {@link org.apache.ftpserver.FtpServerFactory}. Sets the listener
     * identity into thread-local on every command, clears on session close.
     */
    public static final class Ftplet extends DefaultFtplet {

        private final String instanceId;
        private final String storageMode;

        public Ftplet(String instanceId, String storageMode) {
            this.instanceId = instanceId;
            this.storageMode = storageMode;
        }

        @Override
        public FtpletResult beforeCommand(FtpSession session, FtpRequest request) {
            if (instanceId != null)  INSTANCE_ID.set(instanceId);
            if (storageMode != null) STORAGE_MODE.set(storageMode);
            return FtpletResult.DEFAULT;
        }

        @Override
        public FtpletResult afterCommand(FtpSession session, FtpRequest request, org.apache.ftpserver.ftplet.FtpReply reply) {
            // Leave state set — next command on this thread may need it again
            // (thread pooled). Explicit clear happens on session disconnect.
            return FtpletResult.DEFAULT;
        }

        @Override
        public FtpletResult onDisconnect(FtpSession session) {
            clear();
            return FtpletResult.DEFAULT;
        }
    }
}
