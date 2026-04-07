# DMZ Proxy Connection Handling — End to End

A complete technical walkthrough of how the TranzFer MFT DMZ proxy handles every TCP connection, from accept to teardown, covering the Netty pipeline, security orchestration, relay mechanics, and observability.

## Netty Pipeline Architecture

```
Client Channel Pipeline:
┌──────────┐   ┌───────────────────┐   ┌──────────────┐
│ TLS      │──→│ IntelligentProxy  │──→│ RelayHandler │──→ Backend Channel
│ Handler  │   │ Handler           │   │ (→ backend)  │
│ (opt.)   │   │ (security)        │   │              │
└──────────┘   └───────────────────┘   └──────────────┘

Backend Channel Pipeline:
┌────────────────────┐   ┌──────────────┐
│ ProxyProtocol      │──→│ RelayHandler │──→ Client Channel
│ Handler (one-shot) │   │ (→ client)   │
└────────────────────┘   └──────────────┘
```

Each connection creates two channels: one for the client, one for the backend. They are linked by `RelayHandler` instances that forward bytes bidirectionally.

## Step-by-Step Connection Flow

### Step 1: Server Bootstrap Accept

```java
ServerBootstrap b = new ServerBootstrap()
    .group(bossGroup, workerGroup)      // 1 boss thread accepts, N workers process
    .channel(NioServerSocketChannel.class)
    .childOption(ChannelOption.AUTO_READ, false)  // Critical: manual read control
```

**Boss group (1 thread):** Accepts TCP connections on the listen port.
**Worker group (N threads):** Processes I/O on accepted channels.
**AUTO_READ=false:** The proxy explicitly controls when to read from each channel. This is the foundation of the backpressure mechanism.

When a client TCP SYN arrives:
1. Boss thread accepts the socket
2. Creates a `SocketChannel` bound to a worker thread
3. Calls `initChannel(clientCh)` — the entire pipeline setup runs here

### Step 2: Active Connection Tracking

```java
activeConnections.incrementAndGet();
```

Atomic counter tracks total active connections across all clients. Available via REST API for monitoring.

### Step 3: TLS Termination (Optional)

If `SslContext` was pre-built for this mapping:

```java
SslHandler sslHandler = sslCtx.newHandler(clientCh.alloc());
clientCh.pipeline().addLast("tls", sslHandler);
```

- TLS handshake happens asynchronously
- On success: audit logs TLS version + cipher suite
- On failure: channel closed, warning logged
- SslContext is cached by config fingerprint — not rebuilt per connection

### Step 4: Security Handler

```java
clientCh.pipeline().addLast("security",
    new IntelligentProxyHandler(
        connectionTracker, rateLimiter,
        aiVerdictClient, eventReporter, securityMetrics,
        listenPort, mappingName, securityPolicy, manualFilter, manager));
```

The IntelligentProxyHandler runs when the channel becomes active (`channelActive()`) and on every read (`channelRead()`).

**channelActive() flow:**

```
┌─────────────────────┐
│ Manual Security      │──→ BLOCK? → Close + audit
│ (IP, geo, window)    │
└──────────┬──────────┘
           ↓
┌─────────────────────┐
│ Rate Limiter         │──→ RATE_LIMITED? → Close + audit
│ (token bucket)       │
└──────────┬──────────┘
           ↓
┌─────────────────────┐
│ AI Verdict           │──→ BLOCK/BLACKHOLE? → Close + audit
│ (cache → network)    │
└──────────┬──────────┘
           ↓
    Connection allowed
    (sets connectionAllowed=true)
```

**channelRead() flow (per message):**

```
┌─────────────────────┐
│ Protocol Detection   │──→ Detect SSH/FTP/HTTP/TLS from first bytes
│ (once, on first msg) │
└──────────┬──────────┘
           ↓
┌─────────────────────┐
│ DPI                  │──→ BLOCK? → Close + audit
│ (payload inspection) │
└──────────┬──────────┘
           ↓
┌─────────────────────┐
│ FTP Command Filter   │──→ BLOCK? → Close + audit
│ (if FTP detected)    │
└──────────┬──────────┘
           ↓
┌─────────────────────┐
│ Byte Rate Check      │──→ EXCEEDED? → Close + audit
└──────────┬──────────┘
           ↓
    Forward to next handler (RelayHandler)
```

### Step 5: Backend Health Check

```java
BackendHealthChecker hc = manager.getHealthChecker();
if (hc != null && !hc.isHealthy(mapping.getName())) {
    // Reject immediately — don't waste time connecting to dead backend
    clientCh.close();
    return;
}
```

The health checker runs TCP probes every 10 seconds in a background thread. The check here is a simple `ConcurrentHashMap` lookup — O(1), no blocking.

**Health State Machine:**
```
UNKNOWN ──(1 success)──→ HEALTHY ──(3 failures)──→ UNHEALTHY
                              ↑                         │
                              └──(1 success)────────────┘
```

### Step 6: Zone Enforcement

```java
ZoneEnforcer.ZoneCheckResult zoneResult = ze.checkTransitionFast(
    sourceIp, mapping.getCachedTargetZone(), mapping.getTargetPort());
```

**Critical: no DNS on the event loop.** The target zone is pre-resolved at mapping creation time and cached. `checkTransitionFast()` only classifies the source IP (always a literal — no resolution needed).

### Step 7: Backend Connection

```java
Bootstrap backendBootstrap = new Bootstrap()
    .group(clientCh.eventLoop())       // Same event loop as client
    .channel(NioSocketChannel.class)
    .option(ChannelOption.AUTO_READ, false)  // Manual read control
    .handler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel backendCh) {
            // PROXY protocol header (one-shot)
            if (mapping.isProxyProtocolEnabled()) {
                backendCh.pipeline().addLast("proxy-protocol",
                    ProxyProtocolHandler.v1(clientAddr, localAddr));
            }
            backendCh.pipeline().addLast(new RelayHandler(clientCh, bytesForwarded));
        }
    });
```

**Same event loop:** Both client and backend channels share the same NIO thread. This eliminates synchronization overhead and thread context switches.

### Step 8: PROXY Protocol Injection

On the first write to the backend, `ProxyProtocolHandler` prepends:

```
PROXY TCP4 192.168.65.1 172.18.0.10 58681 2222\r\n
```

This tells the gateway the real client IP (192.168.65.1) instead of the proxy's IP. The handler then removes itself from the pipeline — subsequent writes go directly to the relay.

### Step 9: Relay Activation

```java
backendFuture.addListener((ChannelFutureListener) f -> {
    if (f.isSuccess()) {
        auditConnection("OPEN", clientCh, "connected");
        clientCh.read();    // Start reading from client
        backendCh.read();   // Start reading from backend (SSH banner!)
    } else {
        clientCh.close();
    }
});
```

**Both `.read()` calls are essential.** Without `backendCh.read()`, the backend's initial data (SSH banner, FTP 220 banner) would never be relayed to the client — the connection would hang.

### Step 10: Bidirectional Byte Relay

The `RelayHandler` is the core relay engine:

```java
static class RelayHandler extends ChannelInboundHandlerAdapter {
    private final Channel outboundChannel;
    private final AtomicLong bytesForwarded;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) bytesForwarded.addAndGet(buf.readableBytes());
        if (outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) ctx.channel().read();  // Read more only after write succeeds
                else f.channel().close();
            });
        }
    }
}
```

**Backpressure mechanism:**
1. Channel reads one message (ByteBuf)
2. Writes it to the paired channel
3. **Only after the write completes** does it trigger another read
4. If the paired channel's write buffer is full (e.g., slow client), the write listener doesn't fire immediately → no more reads → natural flow control

This prevents:
- Buffer overflow on slow consumers
- OOM from fast producers
- Unbounded memory growth

### Step 11: Connection Teardown

```java
// Client disconnects
clientCh.closeFuture().addListener(cf -> {
    activeConnections.decrementAndGet();
    backendCh.close();
});

// OR backend disconnects (RelayHandler.channelInactive)
if (outboundChannel.isActive()) {
    outboundChannel.writeAndFlush(ctx.alloc().buffer(0))
        .addListener(ChannelFutureListener.CLOSE);
}
```

Either side disconnecting triggers the other to close. The relay handler sends an empty buffer flush before closing to ensure all pending data is delivered.

## Thread Model

```
Boss Thread (1)
  └── Accepts connections on listen port
      └── Hands off to Worker Thread

Worker Thread Pool (N = 2 * CPU cores)
  ├── Worker-1: handles client A + backend A (same thread)
  ├── Worker-2: handles client B + backend B
  ├── Worker-3: handles client C + backend C
  └── ...

Health Check Thread (1, daemon)
  └── TCP probes every 10s, updates ConcurrentHashMap

Audit Writer Thread (1)
  └── Buffered writes, flushes every 1s or 100 events

AI Verdict Thread Pool (bounded)
  └── Async verdict requests and health checks
```

**Key insight:** Client and backend channels share the same worker thread. All relay operations are non-blocking. No locks in the critical path.

## Connection States

```
NEW ──→ SECURITY_CHECK ──→ CONNECTING ──→ RELAYING ──→ CLOSED
 │           │                  │
 │           ↓                  ↓
 │      BLOCKED            BACKEND_FAILED
 │      RATE_LIMITED
 │      ZONE_BLOCKED
 ↓
BACKEND_UNHEALTHY
```

| State | Duration | What Happens |
|-------|----------|--------------|
| NEW | <1ms | Channel accepted, pipeline initialized |
| SECURITY_CHECK | 1-50ms | Manual filter → rate limit → AI verdict |
| CONNECTING | 1-10ms | TCP connect to backend |
| RELAYING | Seconds-hours | Bidirectional byte forwarding |
| CLOSED | <1ms | Cleanup, counter decrement, audit |
| BLOCKED | <1ms | Security rejected, channel closed |
| BACKEND_FAILED | <1ms | Backend TCP connect failed |
| BACKEND_UNHEALTHY | <1ms | Health check pre-reject |

## Observability

### Metrics (Atomic Counters)
- `bytesForwarded` — Total bytes relayed (across all connections)
- `activeConnections` — Current open connection count

### Audit Log Events
| Event | Trigger | Fields |
|-------|---------|--------|
| OPEN | Backend connected | src, port, mapping |
| CLOSE | Either side disconnects | duration, bytes |
| BLOCKED | Security rejected | tier, reason |
| RATE_LIMITED | Token bucket empty | IP, limit |
| ZONE_BLOCKED | Zone violation | sourceZone, targetZone |
| BACKEND_FAILED | TCP connect refused | host, port |
| BACKEND_UNHEALTHY | Health check pre-reject | mapping |
| VERDICT | AI decision | action, risk, llmUsed |
| TLS | Handshake complete | version, cipher, cert |

### Health Check API
```
GET /api/proxy/health
→ backend health status for all mappings
```

## Performance Characteristics

| Metric | Value | Notes |
|--------|-------|-------|
| Connection setup | <1ms (cached) to 50ms (AI miss) | Dominated by AI verdict on cache miss |
| Relay latency | <0.1ms per message | Direct ByteBuf forward, zero-copy |
| Memory per connection | ~2KB | Two channels + relay handlers |
| Max concurrent | Limited by OS file descriptors | Typically 100k+ on Linux |
| Throughput | Line rate | Netty NIO, no copy, no parsing |
| Backpressure response | Immediate | Write-complete-before-read pattern |

## Error Handling

| Error | Proxy Behavior | Client Sees |
|-------|---------------|-------------|
| Backend unreachable | Close client channel, audit BACKEND_FAILED | TCP RST |
| Backend unhealthy | Close immediately (no connect attempt) | TCP RST |
| AI engine timeout | ALLOW with conservative limits | Normal connection |
| Relay write failure | Close both channels | Connection dropped |
| TLS handshake failure | Close channel, warn log | Connection dropped |
| Rate limit exceeded | Close channel, audit | TCP RST |
| Zone violation | Close channel, audit | TCP RST |

The proxy never hangs, never waits indefinitely, and never swallows errors silently. Every rejection is audited.
