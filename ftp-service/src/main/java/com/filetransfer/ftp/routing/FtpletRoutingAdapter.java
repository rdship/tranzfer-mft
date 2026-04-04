package com.filetransfer.ftp.routing;

import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Apache FTPServer Ftplet that hooks into upload/download completion events
 * to trigger the shared RoutingEngine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FtpletRoutingAdapter extends DefaultFtplet {

    private final RoutingEngine routingEngine;
    private final TransferAccountRepository accountRepository;

    @Value("${ftp.home-base:/data/ftp}")
    private String homeBase;

    @Override
    public FtpletResult onUploadEnd(FtpSession session, FtpRequest request) throws FtpException, IOException {
        String username = session.getUser().getName();
        String filename = request.getArgument();

        Optional<TransferAccount> accountOpt = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP);
        if (accountOpt.isEmpty()) return FtpletResult.DEFAULT;

        TransferAccount account = accountOpt.get();
        String absolutePath = account.getHomeDir() + "/" + filename;
        String relativePath = "/" + filename;

        log.info("FTP upload detected: user={} path={}", username, relativePath);
        routingEngine.onFileUploaded(account, relativePath, absolutePath);
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onDownloadEnd(FtpSession session, FtpRequest request) throws FtpException, IOException {
        String username = session.getUser().getName();
        String filename = request.getArgument();

        Optional<TransferAccount> accountOpt = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP);
        if (accountOpt.isEmpty()) return FtpletResult.DEFAULT;

        TransferAccount account = accountOpt.get();
        String absolutePath = account.getHomeDir() + "/" + filename;

        log.info("FTP download detected: user={} path={}", username, absolutePath);
        routingEngine.onFileDownloaded(account, absolutePath);
        return FtpletResult.DEFAULT;
    }
}
