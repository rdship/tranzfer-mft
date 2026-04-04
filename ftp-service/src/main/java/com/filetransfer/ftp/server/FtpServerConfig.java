package com.filetransfer.ftp.server;

import com.filetransfer.ftp.routing.FtpletRoutingAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.ListenerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FtpServerConfig {

    private final FtpUserManager ftpUserManager;
    private final FtpletRoutingAdapter ftpletRoutingAdapter;
    private final FtpsConfig ftpsConfig;

    @Value("${ftp.port:21}")
    private int ftpPort;

    @Value("${ftp.passive-ports:21000-21010}")
    private String passivePorts;

    @Value("${ftp.public-host:127.0.0.1}")
    private String publicHost;

    @Bean
    public FtpServer ftpServer() {
        FtpServerFactory serverFactory = new FtpServerFactory();
        serverFactory.setUserManager(ftpUserManager);

        // Register the routing Ftplet
        serverFactory.setFtplets(Map.of("routingFtplet", ftpletRoutingAdapter));

        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(ftpPort);
        listenerFactory.setServerAddress("0.0.0.0");

        org.apache.ftpserver.DataConnectionConfigurationFactory dataConnConfig =
                new org.apache.ftpserver.DataConnectionConfigurationFactory();
        dataConnConfig.setPassiveExternalAddress(publicHost);
        dataConnConfig.setPassivePorts(passivePorts);
        listenerFactory.setDataConnectionConfiguration(dataConnConfig.createDataConnectionConfiguration());

        // FTPS (FTP over TLS) — if enabled
        org.apache.ftpserver.ssl.SslConfiguration sslConfig = ftpsConfig.buildSslConfig();
        if (sslConfig != null) {
            listenerFactory.setSslConfiguration(sslConfig);
            listenerFactory.setImplicitSsl(false); // Explicit FTPS (AUTH TLS on port 21)
            log.info("FTPS enabled: explicit TLS on port {}", ftpPort);
        }

        serverFactory.addListener("default", listenerFactory.createListener());
        return serverFactory.createServer();
    }

    @Bean
    public ApplicationRunner ftpServerRunner(FtpServer ftpServer) {
        return args -> {
            ftpServer.start();
            log.info("FTP server started on port {}", ftpPort);
        };
    }
}
