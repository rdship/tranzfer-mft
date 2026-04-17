package com.filetransfer.ftp.server;

import com.filetransfer.ftp.routing.FtpletRoutingAdapter;
import com.filetransfer.ftp.security.FileOperationFilter;
import com.filetransfer.ftp.security.FtpBounceFilter;
import com.filetransfer.shared.entity.core.ServerInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Ftplet;
import org.apache.ftpserver.listener.ListenerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a configured-but-not-started {@link FtpServer} for a given
 * {@link ServerInstance}. Shared between the boot-time env-var bean and the
 * runtime FTP listener registry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FtpServerBuilder {

    private final FtpUserManager ftpUserManager;
    private final FtpletRoutingAdapter ftpletRoutingAdapter;
    private final FtpsConfig ftpsConfig;
    private final FtpBounceFilter ftpBounceFilter;
    private final FileOperationFilter fileOperationFilter;
    private final VirtualFtpFileSystemFactory virtualFtpFileSystemFactory;

    @Value("${ftp.public-host:127.0.0.1}") private String defaultPublicHost;
    @Value("${ftp.passive-ports:21000-21010}") private String defaultPassivePorts;
    @Value("${ftp.connection.max-total:200}") private int defaultMaxConnections;
    @Value("${ftp.connection.max-logins:100}") private int defaultMaxLogins;
    @Value("${ftp.data-connection-timeout-seconds:120}") private int defaultDataTimeoutSeconds;
    @Value("${ftp.anonymous.enabled:false}") private boolean defaultAnonymousEnabled;

    public FtpServer build(ServerInstance si) {
        FtpServerFactory serverFactory = new FtpServerFactory();
        serverFactory.setUserManager(ftpUserManager);
        serverFactory.setFileSystem(virtualFtpFileSystemFactory);

        int maxConn = si.getMaxConnections() > 0 ? si.getMaxConnections() : defaultMaxConnections;
        ConnectionConfigFactory connConfig = new ConnectionConfigFactory();
        connConfig.setMaxLogins(defaultMaxLogins);
        connConfig.setMaxThreads(maxConn);
        connConfig.setAnonymousLoginEnabled(defaultAnonymousEnabled);
        serverFactory.setConnectionConfig(connConfig.createConnectionConfig());

        Map<String, Ftplet> ftplets = new LinkedHashMap<>();
        // listenerContext MUST be first — sets the per-listener ThreadLocal that
        // FtpUserManager and VirtualFtpFileSystemFactory read downstream.
        ftplets.put("listenerContext",
                new FtpListenerContext.Ftplet(si.getInstanceId(), si.getDefaultStorageMode()));
        ftplets.put("bounceFilter", ftpBounceFilter);
        ftplets.put("fileOperationFilter", fileOperationFilter);
        ftplets.put("routingFtplet", ftpletRoutingAdapter);
        serverFactory.setFtplets(ftplets);

        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(si.getInternalPort());
        listenerFactory.setServerAddress("0.0.0.0");
        int idle = si.getIdleTimeoutSeconds() > 0 ? si.getIdleTimeoutSeconds() : 300;
        listenerFactory.setIdleTimeout(idle);

        DataConnectionConfigurationFactory dataConnConfig = new DataConnectionConfigurationFactory();
        dataConnConfig.setPassiveExternalAddress(
                si.getExternalHost() != null && !si.getExternalHost().isBlank()
                        ? si.getExternalHost() : defaultPublicHost);
        dataConnConfig.setPassivePorts(defaultPassivePorts);
        dataConnConfig.setIdleTime(defaultDataTimeoutSeconds);
        dataConnConfig.setActiveEnabled(true);
        dataConnConfig.setActiveIpCheck(true);
        listenerFactory.setDataConnectionConfiguration(dataConnConfig.createDataConnectionConfiguration());

        org.apache.ftpserver.ssl.SslConfiguration sslConfig = ftpsConfig.buildSslConfig();
        if (sslConfig != null) {
            listenerFactory.setSslConfiguration(sslConfig);
            listenerFactory.setImplicitSsl(ftpsConfig.isImplicit());
        }

        serverFactory.addListener("default", listenerFactory.createListener());
        return serverFactory.createServer();
    }
}
