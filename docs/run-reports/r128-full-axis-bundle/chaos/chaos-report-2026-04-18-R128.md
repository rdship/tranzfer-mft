# Chaos Report — R128 (tip 2fe1a230)

**Verdict: PASS — platform remains functional under kill-and-restart of a non-hot-path service.**

## Experiment

- **Target:** `mft-notification-service` — chosen because notifications fan out through RabbitMQ and callers never block on them synchronously; the correct behaviour under a kill is graceful degradation, not an outage.
- **Kill:** `docker kill mft-notification-service` (SIGKILL — no grace).
- **Workload during kill:** SFTP upload + admin API read.
- **Restart:** `docker compose start notification-service`, wait for healthcheck.

## Timeline (epoch-seconds relative to t=0 at kill)

| t (s) | Event |
|---|---|
| -1 | `chaos-pre-*.csv` SFTP upload → OK, 1 s |
| 0 | `docker kill mft-notification-service` — SIGKILL delivered |
| +8 | `chaos-during-*.csv` SFTP upload → OK, 2 s (platform continues ingesting) |
| +8 | `GET /api/activity-monitor` → HTTP 200 (no cascading failure) |
| +8 | `docker compose start notification-service` |
| +34 | `notification-service` healthcheck returns `healthy` → **26 s recovery time** |
| +41 | `chaos-post-*.csv` SFTP upload → OK, 1 s |

## What this proves

- **Asynchronous boundary works:** callers publish to RabbitMQ `file-transfer.events` and move on; the notification consumer being gone does not block upload, flow execution, or admin read.
- **No cascading failure:** gateway-service, onboarding-api, storage-manager, sftp-service, flow-engine all stayed healthy. No 5xx ripple.
- **Recovery budget:** 26 s from `start` to `healthy` — fits inside the default Kubernetes readiness-probe window. Queued messages are drained on reconnect (RabbitMQ manual ack).

## What this does NOT prove

- **No sustained-load during the kill.** A single upload during the outage confirms the path still works; it doesn't measure throughput degradation. Pair this with the 30-min soak to see if the restart creates a backlog visible in `file-transfer.events` queue depth.
- **Only one target service killed.** Storage-manager, onboarding-api, gateway-service would be more disruptive and are not yet exercised. Save for the Gold gate.
- **Clean kill, not partial network partition.** `tc netem` or iptables blackhole would test the timeout/retry paths on BaseServiceClient, which this experiment skips.

## R129 chaos asks

1. **Kill storage-manager under active upload load** — expected: uploads queue via RabbitMQ, activity rows stay PENDING, no byte loss; resume on restart.
2. **Kill postgres** — expected: all services go unhealthy within their liveness window, reconnect on startup, no data corruption (Flyway is idempotent).
3. **Network partition** between onboarding-api and config-service via `tc qdisc` — expected: circuit-breaker opens, degraded-mode response returned, metric `resilience4j.circuitbreaker.state` visible.
4. **RabbitMQ broker crash** — expected: publisher confirms fail, producers queue locally, consumers reconnect. Verify no double-publish.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;
