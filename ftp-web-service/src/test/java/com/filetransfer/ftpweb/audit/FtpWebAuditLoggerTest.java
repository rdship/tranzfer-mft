package com.filetransfer.ftpweb.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R91: FTP_WEB audit events always carry the listener id + source IP so
 * downstream log collectors can partition activity per listener.
 *
 * <p>Attaches a list appender to the FTP_WEB_AUDIT logger and asserts on
 * the structured map emitted by {@link FtpWebAuditLogger}.
 */
class FtpWebAuditLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        auditLogger = (Logger) LoggerFactory.getLogger("FTP_WEB_AUDIT");
        auditLogger.addAppender(appender);
        auditLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void uploadEventIncludesListenerIdAndIp() {
        new FtpWebAuditLogger().logUpload("alice", "ftpweb-eu", "203.0.113.7",
                "/inbox/report.csv", 4096, "VIRTUAL");

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("event=UPLOAD");
        assertThat(msg).contains("username=alice");
        assertThat(msg).contains("instanceId=ftpweb-eu");
        assertThat(msg).contains("ip=203.0.113.7");
        assertThat(msg).contains("path=/inbox/report.csv");
        assertThat(msg).contains("size=4096");
        assertThat(msg).contains("storageMode=VIRTUAL");
    }

    @Test
    void downloadDeleteRenameListMkdirAllTagged() {
        FtpWebAuditLogger logger = new FtpWebAuditLogger();
        logger.logDownload("bob", "ftpweb-primary", "10.0.0.1", "/outbox/x.dat");
        logger.logDelete("bob", "ftpweb-primary", "10.0.0.1", "/tmp/y.dat");
        logger.logRename("bob", "ftpweb-primary", "10.0.0.1", "/a", "/b");
        logger.logList("bob", "ftpweb-primary", "10.0.0.1", "/");
        logger.logMkdir("bob", "ftpweb-primary", "10.0.0.1", "/reports");

        assertThat(appender.list).hasSize(5);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("event=DOWNLOAD");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("event=DELETE");
        assertThat(appender.list.get(2).getFormattedMessage()).contains("event=RENAME");
        assertThat(appender.list.get(2).getFormattedMessage()).contains("from=/a", "to=/b");
        assertThat(appender.list.get(3).getFormattedMessage()).contains("event=LIST");
        assertThat(appender.list.get(4).getFormattedMessage()).contains("event=MKDIR");
    }
}
