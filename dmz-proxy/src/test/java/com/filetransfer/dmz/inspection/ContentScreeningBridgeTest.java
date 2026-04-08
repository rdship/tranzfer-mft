package com.filetransfer.dmz.inspection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ContentScreeningBridgeTest {

    private ContentScreeningBridge bridge;
    private ContentScreeningBridge disabledBridge;

    private static final ContentScreeningBridge.ContentScreeningConfig ENABLED_CONFIG =
        new ContentScreeningBridge.ContentScreeningConfig(
            true, 1000L, 5, true, false, true,
            List.of("FTP", "SFTP", "HTTP")
        );

    private static final ContentScreeningBridge.ContentScreeningConfig DISABLED_CONFIG =
        new ContentScreeningBridge.ContentScreeningConfig(
            false, 50_000_000L, 30, true, false, true,
            List.of("FTP", "SFTP", "HTTP")
        );

    @BeforeEach
    void setUp() {
        bridge = new ContentScreeningBridge(
            "http://localhost:8092", null, ENABLED_CONFIG);
        disabledBridge = new ContentScreeningBridge(
            "http://localhost:8092", null, DISABLED_CONFIG);
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
        disabledBridge.shutdown();
    }

    // ── Test 1: disabled_feedDataIsNoop ──────────────────────────────────

    @Test
    void disabled_feedDataIsNoop() {
        ByteBuf data = Unpooled.copiedBuffer("test data content", StandardCharsets.UTF_8);
        try {
            disabledBridge.feedData("conn-1", data);

            Map<String, Object> stats = disabledBridge.getStats();
            assertEquals(0, stats.get("activeBuffers"),
                "feedData should be a no-op when screening is disabled");
        } finally {
            data.release();
        }
    }

    // ── Test 2: feedData_accumulatesData ─────────────────────────────────

    @Test
    void feedData_accumulatesData() {
        ByteBuf data = Unpooled.copiedBuffer("test data content", StandardCharsets.UTF_8);
        try {
            bridge.feedData("conn-1", data);

            Map<String, Object> stats = bridge.getStats();
            assertEquals(1, stats.get("activeBuffers"),
                "feedData should create one active buffer for the connection");
        } finally {
            data.release();
        }
    }

    // ── Test 3: feedData_respectsSizeLimit ───────────────────────────────

    @Test
    void feedData_respectsSizeLimit() {
        // Config has maxScreeningSizeBytes=1000
        ContentScreeningBridge.ContentScreeningConfig smallConfig =
            new ContentScreeningBridge.ContentScreeningConfig(
                true, 100L, 5, true, false, true,
                List.of("FTP", "SFTP", "HTTP")
            );
        ContentScreeningBridge smallBridge = new ContentScreeningBridge(
            "http://localhost:8092", null, smallConfig);

        try {
            // Feed 200 bytes in two chunks of 100+ bytes
            byte[] largePayload = new byte[200];
            java.util.Arrays.fill(largePayload, (byte) 'A');

            ByteBuf data = Unpooled.copiedBuffer(largePayload);
            try {
                smallBridge.feedData("conn-1", data);
            } finally {
                data.release();
            }

            // Buffer should exist but only contain up to 100 bytes
            Map<String, Object> stats = smallBridge.getStats();
            assertEquals(1, stats.get("activeBuffers"),
                "Buffer should still be tracked after hitting size limit");

            // Feeding more data should not increase buffer size
            ByteBuf moreData = Unpooled.copiedBuffer("extra data", StandardCharsets.UTF_8);
            try {
                smallBridge.feedData("conn-1", moreData);
            } finally {
                moreData.release();
            }

            // Still just one buffer, no crash or error
            stats = smallBridge.getStats();
            assertEquals(1, stats.get("activeBuffers"));
        } finally {
            smallBridge.shutdown();
        }
    }

    // ── Test 4: transferCancelled_cleansUpBuffer ────────────────────────

    @Test
    void transferCancelled_cleansUpBuffer() {
        ByteBuf data = Unpooled.copiedBuffer("test data content", StandardCharsets.UTF_8);
        try {
            bridge.feedData("conn-1", data);
        } finally {
            data.release();
        }

        assertEquals(1, bridge.getStats().get("activeBuffers"),
            "Buffer should exist before cancellation");

        bridge.transferCancelled("conn-1");

        assertEquals(0, bridge.getStats().get("activeBuffers"),
            "Buffer should be removed after cancellation");
    }

    // ── Test 5: screenTransfer_disabled_returnsSkipped ──────────────────

    @Test
    void screenTransfer_disabled_returnsSkipped() throws Exception {
        byte[] content = "some content".getBytes(StandardCharsets.UTF_8);

        CompletableFuture<ContentScreeningBridge.ScreeningResult> future =
            disabledBridge.screenTransfer("127.0.0.1", 21, "FTP", "test.txt", content);

        ContentScreeningBridge.ScreeningResult result = future.get();
        assertEquals(ContentScreeningBridge.Outcome.SKIPPED, result.outcome());
        assertEquals("screening_disabled", result.detail());
        assertEquals(0, result.hitsFound());
    }

    // ── Test 6: screenTransfer_emptyContent_returnsSkipped ──────────────

    @Test
    void screenTransfer_emptyContent_returnsSkipped() throws Exception {
        byte[] emptyContent = new byte[0];

        CompletableFuture<ContentScreeningBridge.ScreeningResult> future =
            bridge.screenTransfer("127.0.0.1", 21, "FTP", "test.txt", emptyContent);

        ContentScreeningBridge.ScreeningResult result = future.get();
        assertEquals(ContentScreeningBridge.Outcome.SKIPPED, result.outcome());
        assertEquals("empty_content", result.detail());
    }

    // ── Test 7: screenTransfer_exceedsMaxSize_returnsSkipped ────────────

    @Test
    void screenTransfer_exceedsMaxSize_returnsSkipped() throws Exception {
        // Config has maxScreeningSizeBytes=1000 — send 1001 bytes
        byte[] oversizedContent = new byte[1001];
        java.util.Arrays.fill(oversizedContent, (byte) 'X');

        CompletableFuture<ContentScreeningBridge.ScreeningResult> future =
            bridge.screenTransfer("127.0.0.1", 21, "FTP", "test.txt", oversizedContent);

        ContentScreeningBridge.ScreeningResult result = future.get();
        assertEquals(ContentScreeningBridge.Outcome.SKIPPED, result.outcome());
        assertEquals("content_exceeds_max_size", result.detail());
    }

    // ── Test 8: transferComplete_unscreenedProtocol_skips ───────────────

    @Test
    void transferComplete_unscreenedProtocol_skips() {
        ByteBuf data = Unpooled.copiedBuffer("test data content", StandardCharsets.UTF_8);
        try {
            bridge.feedData("conn-1", data);
        } finally {
            data.release();
        }

        // "AS2" is not in the screened protocols list [FTP, SFTP, HTTP]
        bridge.transferComplete("conn-1", "127.0.0.1", 8094, "AS2", "payload.edi");

        Map<String, Object> stats = bridge.getStats();
        assertEquals(0, stats.get("activeBuffers"),
            "Buffer should be removed after transferComplete");
        assertEquals(1L, stats.get("totalSkipped"),
            "Unscreened protocol should increment totalSkipped");
    }

    // ── Test 9: getStats_returnsExpectedKeys ────────────────────────────

    @Test
    void getStats_returnsExpectedKeys() {
        Map<String, Object> stats = bridge.getStats();

        assertAll("Stats map should contain all expected keys",
            () -> assertTrue(stats.containsKey("enabled"), "missing 'enabled'"),
            () -> assertTrue(stats.containsKey("totalScreened"), "missing 'totalScreened'"),
            () -> assertTrue(stats.containsKey("totalClear"), "missing 'totalClear'"),
            () -> assertTrue(stats.containsKey("totalHit"), "missing 'totalHit'"),
            () -> assertTrue(stats.containsKey("totalPossibleHit"), "missing 'totalPossibleHit'"),
            () -> assertTrue(stats.containsKey("totalError"), "missing 'totalError'"),
            () -> assertTrue(stats.containsKey("totalTimeout"), "missing 'totalTimeout'"),
            () -> assertTrue(stats.containsKey("totalSkipped"), "missing 'totalSkipped'"),
            () -> assertTrue(stats.containsKey("activeBuffers"), "missing 'activeBuffers'"),
            () -> assertTrue(stats.containsKey("pendingScreenings"), "missing 'pendingScreenings'")
        );

        assertEquals(true, stats.get("enabled"));
        assertEquals(0L, stats.get("totalScreened"));
    }

    // ── Test 10: shutdown_clearsState ────────────────────────────────────

    @Test
    void shutdown_clearsState() {
        // Feed some data to create buffers
        ByteBuf data = Unpooled.copiedBuffer("test data", StandardCharsets.UTF_8);
        try {
            bridge.feedData("conn-1", data);
            bridge.feedData("conn-2", data);
        } finally {
            data.release();
        }

        assertEquals(2, bridge.getStats().get("activeBuffers"),
            "Should have 2 active buffers before shutdown");

        bridge.shutdown();

        Map<String, Object> stats = bridge.getStats();
        assertEquals(0, stats.get("activeBuffers"),
            "activeBuffers should be 0 after shutdown");
        assertEquals(0, stats.get("pendingScreenings"),
            "pendingScreenings should be 0 after shutdown");
    }
}
