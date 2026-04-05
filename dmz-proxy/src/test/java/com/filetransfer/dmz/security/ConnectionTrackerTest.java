package com.filetransfer.dmz.security;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTrackerTest {

    private ConnectionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ConnectionTracker();
    }

    private Channel newChannel() {
        return new EmbeddedChannel();
    }

    @Test
    void connectionOpenedTracksIp() {
        Channel ch = newChannel();
        ConnectionTracker.IpState state = tracker.connectionOpened(ch, "10.0.0.1", 22);

        assertNotNull(state);
        assertEquals("10.0.0.1", state.getIp());
        assertEquals(1, state.getActiveConnectionCount());
        assertEquals(1, state.getTotalConnections());
        assertEquals(1, tracker.getActiveConnectionCount());
    }

    @Test
    void connectionClosedDecrementsCount() {
        Channel ch = newChannel();
        tracker.connectionOpened(ch, "10.0.0.1", 22);
        assertEquals(1, tracker.getActiveConnectionCount());

        ConnectionTracker.ConnectionInfo info = tracker.connectionClosed(ch);
        assertNotNull(info);
        assertEquals("10.0.0.1", info.ip());
        assertEquals(0, tracker.getActiveConnectionCount());
    }

    @Test
    void multipleConnectionsSameIp() {
        Channel ch1 = newChannel();
        Channel ch2 = newChannel();

        tracker.connectionOpened(ch1, "10.0.0.1", 22);
        tracker.connectionOpened(ch2, "10.0.0.1", 22);

        ConnectionTracker.IpState state = tracker.get("10.0.0.1").orElse(null);
        assertNotNull(state);
        assertEquals(2, state.getActiveConnectionCount());
        assertEquals(2, state.getTotalConnections());
    }

    @Test
    void bytesTrackedPerChannel() {
        Channel ch = newChannel();
        tracker.connectionOpened(ch, "10.0.0.1", 22);

        tracker.recordBytesIn(ch, 1024);
        tracker.recordBytesIn(ch, 2048);
        tracker.recordBytesOut(ch, 512);

        ConnectionTracker.ConnectionInfo info = tracker.getConnectionInfo(ch);
        assertEquals(3072, info.bytesIn().get());
        assertEquals(512, info.bytesOut().get());

        ConnectionTracker.IpState state = tracker.get("10.0.0.1").orElse(null);
        assertNotNull(state);
        assertEquals(3072, state.getTotalBytesIn());
        assertEquals(512, state.getTotalBytesOut());
    }

    @Test
    void connectionRejectedCounted() {
        tracker.connectionRejected("10.0.0.1");
        ConnectionTracker.IpState state = tracker.get("10.0.0.1").orElse(null);
        assertNotNull(state);
        assertEquals(1, state.getRejectedConnections());
    }

    @Test
    void portsTracked() {
        Channel ch1 = newChannel();
        Channel ch2 = newChannel();

        tracker.connectionOpened(ch1, "10.0.0.1", 22);
        tracker.connectionOpened(ch2, "10.0.0.1", 80);

        ConnectionTracker.IpState state = tracker.get("10.0.0.1").orElse(null);
        assertNotNull(state);
        assertTrue(state.getPortsUsed().contains(22));
        assertTrue(state.getPortsUsed().contains(80));
    }

    @Test
    void statsReturnsOverview() {
        Channel ch = newChannel();
        tracker.connectionOpened(ch, "10.0.0.1", 22);

        var stats = tracker.getStats();
        assertEquals(1, stats.get("trackedIps"));
        assertEquals(1L, stats.get("activeConnections"));
    }

    @Test
    void unknownChannelCloseReturnsNull() {
        Channel ch = newChannel();
        assertNull(tracker.connectionClosed(ch));
    }
}
