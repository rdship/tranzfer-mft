/**
 * TranzFer MFT — Server-Sent Events stream tests.
 *
 * The UI's ActivityMonitor subscribes to `/api/activity-monitor/stream?token=...`
 * to receive live updates. This surface had ZERO test coverage before this file.
 * Bugs here are invisible until a user notices their live feed is frozen —
 * exactly what surfaced in the R100 report when the user said "why do I not
 * see the tests you are performing on the activity monitor".
 *
 * These tests:
 *   - connect to the SSE endpoint
 *   - verify the connection stays open and emits events
 *   - correlate an upload with its SSE event within a reasonable window
 *   - close the connection cleanly
 */
const { test, expect } = require('../fixtures/auth.fixture');
const { execSync } = require('child_process');

// Helper — consume SSE events until `matcher` returns truthy, timeout, or connection drops.
async function collectSseEvents(page, url, { token, timeout = 15000, matcher }) {
  return page.evaluate(async ({ url, token, timeout, matcherStr }) => {
    return new Promise((resolve) => {
      const events = [];
      const matcher = matcherStr ? new Function('e', `return (${matcherStr})(e)`) : null;
      const sseUrl = url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
      const es = new EventSource(sseUrl);
      const deadline = setTimeout(() => { es.close(); resolve({ events, reason: 'timeout' }); }, timeout);
      es.onmessage = (ev) => {
        try {
          const data = JSON.parse(ev.data);
          events.push(data);
          if (matcher && matcher(data)) {
            clearTimeout(deadline); es.close(); resolve({ events, reason: 'matched', matched: data });
          }
        } catch { /* non-JSON event, ignore */ }
      };
      es.onerror = (ev) => {
        clearTimeout(deadline); es.close();
        resolve({ events, reason: 'error', readyState: es.readyState });
      };
    });
  }, { url, token, timeout, matcherStr: matcher ? matcher.toString() : null });
}

test.describe('Activity-monitor SSE stream', () => {
  test('stream opens and stays alive for 3s without error @sse', async ({ page, token }) => {
    // Use a fresh tab with no nav; EventSource works from any page context.
    await page.goto('about:blank');
    const result = await collectSseEvents(page, '/api/activity-monitor/stream', { token, timeout: 3500 });
    // "timeout" is the HAPPY path here — means the stream stayed open for 3s without error
    expect(['timeout', 'matched'], `SSE stream errored: ${JSON.stringify(result)}`).toContain(result.reason);
  });

  test('SSE emits an event within 15s of a real SFTP upload @sse', async ({ page, token, api }) => {
    let dockerAvailable = true;
    try { execSync('docker ps', { stdio: 'ignore' }); } catch { dockerAvailable = false; }
    test.skip(!dockerAvailable, 'docker not available for SFTP upload sidecar');

    const filename = `sse-pin-${Date.now()}.dat`;
    await page.goto('about:blank');

    // Start consuming in parallel with the upload
    const ssePromise = collectSseEvents(page, '/api/activity-monitor/stream', {
      token,
      timeout: 20000,
      matcher: (e) => e && e.filename === filename,
    });

    // Upload via docker sidecar on the mft network
    const tmp = `/tmp/sse-${filename}`;
    require('fs').writeFileSync(tmp, 'sse test body\n');
    execSync(
      `docker run --rm --network tranzfer-mft_default -v ${tmp}:/in.dat alpine:3.19 sh -c "` +
      `apk add -q openssh-client sshpass 2>/dev/null; ` +
      `sshpass -p 'RegTest@2026!' sftp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -P 2231 regtest-sftp-1@sftp-service <<EOF 2>&1 | tail -2
put /in.dat /upload/${filename}
bye
EOF"`,
      { timeout: 60000 }
    );
    require('fs').unlinkSync(tmp);

    const result = await ssePromise;
    expect(result.reason, `SSE did not emit event for ${filename}; collected ${result.events.length} events`).toBe('matched');
    expect(result.matched.filename).toBe(filename);
  });
});
