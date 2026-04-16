package com.filetransfer.as2.routing;

import com.filetransfer.as2.service.As2AccountService;
import com.filetransfer.shared.entity.integration.As2Message;
import com.filetransfer.shared.entity.integration.As2Partnership;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.integration.As2MessageRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Bridges inbound AS2/AS4 messages into the platform's standard file flow.
 * Writes the received payload to disk in the partner's inbox directory,
 * then invokes RoutingEngine.onFileUploaded() — exactly the same path
 * that SFTP and FTP uploads take.
 *
 * This ensures AS2/AS4 files get the same treatment:
 * - Flow processing (encrypt, compress, rename)
 * - AI classification (PCI/PII scan)
 * - Routing evaluation
 * - Audit logging
 * - Connector notifications
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class As2RoutingHandler {

    private final RoutingEngine routingEngine;
    private final As2AccountService accountService;
    private final As2MessageRepository messageRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.filetransfer.shared.vfs.VirtualFileSystem virtualFileSystem;

    @Value("${as2.home-base:/data/as2}")
    private String as2HomeBase;

    /**
     * Route an inbound AS2 message through the platform's standard file flow.
     *
     * @param partnership The trading partner configuration
     * @param message     The AS2 message record
     * @param payload     The raw file bytes
     * @param filename    The filename for the payload
     */
    public void routeInboundMessage(As2Partnership partnership, As2Message message,
                                     byte[] payload, String filename) {
        try {
            // 1. Get or create TransferAccount for this partnership
            TransferAccount account = accountService.getOrCreateAccount(partnership);

            // 2. Write payload — VIRTUAL goes to VFS, PHYSICAL goes to disk
            String relativePath;
            String absolutePath;

            if ("VIRTUAL".equalsIgnoreCase(account.getStorageMode()) && virtualFileSystem != null) {
                // VIRTUAL: store via VFS (DB + MinIO)
                String vpath = "/inbox/" + filename;
                virtualFileSystem.writeFile(account.getId(), vpath, null,
                        payload.length, null, null, payload);
                relativePath = vpath;
                absolutePath = account.getHomeDir() + vpath;
                log.info("AS2 file stored in VFS: partner={} path={}", partnership.getPartnerAs2Id(), vpath);
            } else {
                // PHYSICAL: write to disk
                Path inboxDir = Paths.get(account.getHomeDir(), "inbox");
                Files.createDirectories(inboxDir);
                Path filePath = inboxDir.resolve(filename);

                // Avoid overwriting — append message ID suffix if file exists
                if (Files.exists(filePath)) {
                    String baseName = filename.contains(".")
                            ? filename.substring(0, filename.lastIndexOf('.'))
                            : filename;
                    String ext = filename.contains(".")
                            ? filename.substring(filename.lastIndexOf('.'))
                            : "";
                    String messageId = message.getMessageId();
                    String shortId = messageId != null && messageId.length() > 8
                            ? messageId.substring(0, 8)
                            : (messageId != null ? messageId : "UNKNOWN");
                    filePath = inboxDir.resolve(baseName + "_" + shortId + ext);
                }

                Files.write(filePath, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                relativePath = "/inbox/" + filePath.getFileName().toString();
                absolutePath = filePath.toAbsolutePath().toString();
            }

            // 3. Update message record with track info
            message.setStatus("ROUTING");
            messageRepository.save(message);

            // 4. Invoke RoutingEngine — VFS entry exists (VIRTUAL) or file on disk (PHYSICAL)
            log.info("Routing AS2 file through platform flow: partner={} file={} path={}",
                    partnership.getPartnerAs2Id(), filename, absolutePath);

            routingEngine.onFileUploaded(account, relativePath, absolutePath);

            // 5. Mark message as routed
            message.setStatus("ROUTED");
            messageRepository.save(message);

            log.info("AS2 message routed successfully: messageId={} file={}",
                    message.getMessageId(), filename);

        } catch (Exception e) {
            log.error("Failed to route AS2 message {}: {}", message.getMessageId(), e.getMessage(), e);
            message.setStatus("FAILED");
            message.setErrorMessage("Routing failed: " + e.getMessage());
            messageRepository.save(message);
        }
    }
}
