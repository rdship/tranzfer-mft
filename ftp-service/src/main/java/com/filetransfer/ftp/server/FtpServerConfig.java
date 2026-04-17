package com.filetransfer.ftp.server;

import com.filetransfer.ftp.routing.FtpletRoutingAdapter;
import com.filetransfer.ftp.security.FtpBounceFilter;
import com.filetransfer.ftp.security.FileOperationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Ftplet;
import org.apache.ftpserver.listener.ListenerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central FTP server configuration.
 *
 * <p>Wires together the Apache FtpServer with all production features:
 * connection limits, FTPS, passive mode, active mode, banner, ftplets
 * for routing, file operation controls, and FTP bounce prevention.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FtpServerConfig {

    private final FtpUserManager ftpUserManager;
    private final FtpletRoutingAdapter ftpletRoutingAdapter;
    private final FtpsConfig ftpsConfig;
    private final FtpBounceFilter ftpBounceFilter;
    private final FileOperationFilter fileOperationFilter;
    private final VirtualFtpFileSystemFactory virtualFtpFileSystemFactory;

    @Autowired(required = false)
    @org.springframework.lang.Nullable
    private com.filetransfer.shared.repository.core.ServerInstanceRepository serverInstanceRepository;

    @Value("${ftp.instance-id:#{null}}")
    private String instanceId;

    @Value("${ftp.port:21}")
    private int ftpPort;

    @Value("${ftp.passive-ports:21000-21010}")
    private String passivePorts;

    @Value("${ftp.public-host:127.0.0.1}")
    private String publicHost;

    @Value("${ftp.connection.max-total:200}")
    private int maxConnections;

    @Value("${ftp.connection.max-logins:100}")
    private int maxLogins;

    @Value("${ftp.idle-timeout-seconds:300}")
    private int idleTimeoutSeconds;

    @Value("${ftp.data-connection-timeout-seconds:120}")
    private int dataConnectionTimeoutSeconds;

    @Value("${ftp.banner-message:220 TranzFer MFT FTP Service Ready}")
    private String bannerMessage;

    @Value("${ftp.anonymous.enabled:false}")
    private boolean anonymousEnabled;

    @Value("${ftp.passive.epsv-enabled:true}")
    private boolean epsvEnabled;

    // --- Active mode settings ---

    /** Enable or disable active mode (PORT/EPRT commands). */
    @Value("${ftp.active.enabled:true}")
    private boolean activeModeEnabled;

    /** Minimum source port for active mode data connections (0 = OS assigned). */
    @Value("${ftp.active.data-port-min:0}")
    private int activeDataPortMin;

    /** Maximum source port for active mode data connections (0 = OS assigned). */
    @Value("${ftp.active.data-port-max:0}")
    private int activeDataPortMax;

    /** Timeout in seconds for establishing active data connections. */
    @Value("${ftp.active.data-timeout-seconds:30}")
    private int activeDataTimeoutSeconds;

    /**
     * Build and configure the Apache FTP server instance.
     *
     * @return a fully configured but not yet started FTP server
     */
    @Bean
    public FtpServer ftpServer() {
        // Load per-instance config from DB — UI changes take effect on restart.
        if (instanceId != null && serverInstanceRepository != null) {
            serverInstanceRepository.findByInstanceId(instanceId).ifPresent(si -> {
                if (si.getIdleTimeoutSeconds() > 0) {
                    idleTimeoutSeconds = si.getIdleTimeoutSeconds();
                    log.info("FTP idle timeout loaded from DB: {}s", idleTimeoutSeconds);
                }
                if (si.getSshBannerMessage() != null && !si.getSshBannerMessage().isBlank()) {
                    bannerMessage = si.getSshBannerMessage();
                    log.info("FTP banner loaded from DB");
                }
                log.info("FTP instance '{}' config loaded from database", instanceId);
            });
        }

        FtpServerFactory serverFactory = new FtpServerFactory();
        serverFactory.setUserManager(ftpUserManager);
        serverFactory.setFileSystem(virtualFtpFileSystemFactory);

        // Connection configuration
        ConnectionConfigFactory connConfig = new ConnectionConfigFactory();
        connConfig.setMaxLogins(maxLogins);
        connConfig.setMaxThreads(maxConnections);
        connConfig.setAnonymousLoginEnabled(anonymousEnabled);
        serverFactory.setConnectionConfig(connConfig.createConnectionConfig());

        // Register Ftplets (order matters -- security filters first, then routing)
        Map<String, Ftplet> ftplets = new LinkedHashMap<>();
        ftplets.put("bounceFilter", ftpBounceFilter);
        ftplets.put("fileOperationFilter", fileOperationFilter);
        ftplets.put("routingFtplet", ftpletRoutingAdapter);
        serverFactory.setFtplets(ftplets);

        // Primary listener
        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(ftpPort);
        listenerFactory.setServerAddress("0.0.0.0");
        listenerFactory.setIdleTimeout(idleTimeoutSeconds);

        // Data connection configuration (passive + active mode)
        DataConnectionConfigurationFactory dataConnConfig =
                new DataConnectionConfigurationFactory();

        // Passive mode settings
        dataConnConfig.setPassiveExternalAddress(publicHost);
        dataConnConfig.setPassivePorts(passivePorts);
        dataConnConfig.setIdleTime(dataConnectionTimeoutSeconds);

        // Active mode settings
        dataConnConfig.setActiveEnabled(activeModeEnabled);
        dataConnConfig.setActiveIpCheck(true); // Defense-in-depth alongside FtpBounceFilter
        if (activeDataPortMin > 0 && activeDataPortMax > 0) {
            dataConnConfig.setActiveLocalPort(activeDataPortMin);
            log.info("Active mode local port range: {}-{}", activeDataPortMin, activeDataPortMax);
        }

        // The active IP check (PORT must match client IP) is also handled by FtpBounceFilter
        // which provides EPRT command validation and audit logging for bounce attempts.

        listenerFactory.setDataConnectionConfiguration(dataConnConfig.createDataConnectionConfiguration());

        // FTPS (FTP over TLS)
        org.apache.ftpserver.ssl.SslConfiguration sslConfig = ftpsConfig.buildSslConfig();
        if (sslConfig != null) {
            listenerFactory.setSslConfiguration(sslConfig);
            listenerFactory.setImplicitSsl(ftpsConfig.isImplicit());
            if (ftpsConfig.isImplicit()) {
                log.info("FTPS enabled: implicit TLS on port {}", ftpPort);
            } else {
                log.info("FTPS enabled: explicit TLS (AUTH TLS) on port {}", ftpPort);
            }
            if (ftpsConfig.isRequireTls()) {
                log.info("FTPS required: plain FTP connections will be rejected");
            }
            if (ftpsConfig.isRequireDataTls()) {
                log.info("FTPS data channel encryption required (PROT P enforced)");
            }
        }

        serverFactory.addListener("default", listenerFactory.createListener());

        log.info("FTP server configured: port={} maxConnections={} maxLogins={} " +
                        "idleTimeout={}s dataTimeout={}s passivePorts={} publicHost={} " +
                        "anonymous={} epsv={} activeMode={}",
                ftpPort, maxConnections, maxLogins, idleTimeoutSeconds,
                dataConnectionTimeoutSeconds, passivePorts, publicHost,
                anonymousEnabled, epsvEnabled, activeModeEnabled);

        return serverFactory.createServer();
    }

    /**
     * Start the FTP server after the Spring context is fully initialized.
     *
     * @param ftpServer the configured FTP server instance
     * @return an application runner that starts the FTP server
     */
    @Bean
    public ApplicationRunner ftpServerRunner(FtpServer ftpServer) {
        return args -> {
            try {
                ftpServer.start();
                log.info("FTP server started on port {}", ftpPort);
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null && root != root.getCause()) root = root.getCause();
                if (root instanceof java.net.BindException) {
                    log.error("FTP primary listener FAILED to bind port {} — port already in use. "
                            + "Dynamic listeners may still work on other ports.", ftpPort);
                } else {
                    log.error("FTP primary listener FAILED to start on port {}: {}", ftpPort, e.getMessage());
                }
            }
        };
    }
}
