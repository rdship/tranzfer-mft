package com.filetransfer.sftp.server;

import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;

/**
 * Per-listener identity threaded onto each SSH {@link Session} at creation time.
 *
 * <p>TranzFer SFTP service hosts multiple listeners per JVM (one env-var primary
 * plus N dynamic listeners created via the API). Apache SSHD's authenticator
 * and filesystem callbacks are shared singletons — they have no native way to
 * know which listener a session arrived on. We fix that by attaching the
 * listener's {@code instanceId} and {@code storageMode} as session attributes
 * the moment a session is created, via a per-listener {@link Tagger}.</p>
 *
 * <p>Downstream consumers ({@link SftpPasswordAuthenticator},
 * {@link SftpPublicKeyAuthenticator}, {@link SftpFileSystemFactory}) call
 * {@link #instanceId(Session)} / {@link #storageMode(Session)} to route the
 * lookup to the correct ServerInstance row.</p>
 */
public final class ListenerContext {

    public static final AttributeKey<String> INSTANCE_ID  = new AttributeKey<>();
    public static final AttributeKey<String> STORAGE_MODE = new AttributeKey<>();

    public static String instanceId(Session session) {
        return session == null ? null : session.getAttribute(INSTANCE_ID);
    }

    public static String storageMode(Session session) {
        return session == null ? null : session.getAttribute(STORAGE_MODE);
    }

    private ListenerContext() {}

    /**
     * Attach one of these to each SshServer instance. On {@code sessionCreated}
     * it stamps the arriving session with this listener's identity so shared
     * auth / FS callbacks can look it up.
     */
    public static final class Tagger implements SessionListener {
        private final String instanceId;
        private final String storageMode;

        public Tagger(String instanceId, String storageMode) {
            this.instanceId = instanceId;
            this.storageMode = storageMode;
        }

        @Override
        public void sessionCreated(Session session) {
            if (instanceId != null)  session.setAttribute(INSTANCE_ID,  instanceId);
            if (storageMode != null) session.setAttribute(STORAGE_MODE, storageMode);
        }
    }
}
