/**
 * TranzFer MFT — Regression pins.
 *
 * One named test per historical production bug. These tests exist SPECIFICALLY
 * to fail loudly if the same defect reappears. Each pin cites the release that
 * introduced the fix (or, for open items, the release that surfaced the bug).
 *
 * Naming convention: `Rxx: short description of the fix`.
 *
 * If you're adding a pin for a new bug, follow the template:
 *
 *   test('Rxx: concise fix description @regression', async ({ api }) => {
 *     // Arrange — set up the precondition that used to trigger the bug
 *     // Act     — do the specific operation
 *     // Assert  — verify the fixed behavior, not a generic 200
 *   });
 *
 * Tag every test `@regression` so `npm run test:regression` runs only these.
 * Tests that currently reproduce an *unfixed* bug are marked `test.fail()` so
 * they go green once the fix lands and immediately signal "regression resolved".
 */
const { test, expect } = require('../fixtures/auth.fixture');
const {
  waitForActivityStatus,
  findActivityByFilename,
  snapshotServiceErrors,
  assertNoFreshErrors,
  assertRoleBoundary,
} = require('../helpers/assertions');
const { execSync } = require('child_process');

// --- Utility --------------------------------------------------------------

/** Upload a small text file via SFTP to regtest-sftp-1 using a docker sidecar.
 *  Returns the filename. Requires the regression fixture (scripts/build-regression-fixture.sh)
 *  to have been run. Skips the calling test if docker or the network is missing. */
async function sftpUploadVia(network, { content, filename, port = 2231, user = 'regtest-sftp-1', password = 'RegTest@2026!' }) {
  const tmp = `/tmp/pw-${Date.now()}-${Math.random().toString(36).slice(2, 7)}.dat`;
  try {
    require('fs').writeFileSync(tmp, content);
    execSync(
      `docker run --rm --network ${network} -v ${tmp}:/in.dat alpine:3.19 sh -c "` +
      `apk add -q openssh-client sshpass 2>/dev/null; ` +
      `sshpass -p '${password}' sftp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -P ${port} ${user}@sftp-service <<EOF 2>&1 | tail -3
put /in.dat /upload/${filename}
bye
EOF"`,
      { timeout: 60000 }
    );
    return filename;
  } finally {
    try { require('fs').unlinkSync(tmp); } catch {}
  }
}

test.describe('Regression pins — authentication + RBAC', () => {
  test('R101: SPIFFE is genuinely enabled platform-wide — S2S calls succeed under rapid burst @regression', async ({ api }) => {
    // R101 closed R79's observable: 1002 ERROR lines in 90s of listener-CRUD load,
    // [dmz-proxy] deleteMapping failed after resilience: 401. Root cause was that
    // SPIFFE_ENABLED was only set on edi-converter, never propagated. Verify the
    // fix by firing a rapid S2S burst and asserting zero 401s.
    //
    // Endpoint choice: /api/activity-monitor lives on onboarding-api (the BASE_URL
    // host) and exercises the full platform auth chain — JWT validation →
    // BaseServiceClient → SPIFFE JWT-SVID attachment on downstream calls — so a
    // SPIFFE regression surfaces here even though the outer call is not direct S2S.
    const results = await Promise.all(
      Array.from({ length: 20 }, () => api.get('/api/activity-monitor?page=0&size=1').then(r => r.status()))
    );
    const counts = results.reduce((acc, s) => ((acc[s] = (acc[s] || 0) + 1), acc), {});
    expect(counts[401] || 0, `R101 regression: got 401 on rapid burst — ${JSON.stringify(counts)}`).toBe(0);
    expect(counts[200] || 0, `R101 regression: expected 20 OK, got ${JSON.stringify(counts)}`).toBe(20);
  });

  test('R104: java-spiffe-provider is bundled in every consumer service fat jar @regression', async () => {
    // R104 hot-fixed R101's blocker: shared-core declared java-spiffe-provider
    // as <optional>true</optional>, so none of the 16 consumer services bundled
    // the lib. Every service threw ClassNotFoundException at startup. Verify by
    // inspecting at least 3 representative fat jars.
    let dockerAvailable = true;
    try { execSync('docker ps', { stdio: 'ignore' }); } catch { dockerAvailable = false; }
    test.skip(!dockerAvailable, 'docker not available in this environment');

    for (const svc of ['onboarding-api', 'config-service', 'keystore-manager']) {
      let jarList;
      try {
        const tmp = `/tmp/pw-${svc}-app.jar`;
        execSync(`docker cp mft-${svc}:/app/app.jar ${tmp}`, { timeout: 30000 });
        jarList = execSync(`unzip -l ${tmp}`, { timeout: 10000 }).toString();
        execSync(`rm -f ${tmp}`);
      } catch (e) {
        throw new Error(`could not inspect ${svc} fat jar: ${e.message}`);
      }
      expect(jarList, `R104 regression: java-spiffe-provider missing from ${svc}/app.jar`)
        .toMatch(/java-spiffe-provider-0\.\d+\.\d+\.jar/);
      expect(jarList, `R104 regression: java-spiffe-core missing from ${svc}/app.jar`)
        .toMatch(/java-spiffe-core-0\.\d+\.\d+\.jar/);
    }
  });

  test('USER role cannot access ADMIN-gated endpoints @regression', async ({ api, apiUser }) => {
    // Consolidated pin: several RBAC regressions closed over the arc.
    await assertRoleBoundary(api, apiUser, '/api/users');
  });
});

test.describe('Regression pins — observability', () => {
  test('R91 / R103: ftp-web-service /actuator/health/liveness returns 200 @regression', async ({ request }) => {
    // R91 introduced a security filter on ftp-web-service that blocked
    // /actuator/health/**. R103 corrected the filter's path exclusion list.
    // Without this fix, the container never reaches (healthy) state.
    let dockerAvailable = true;
    try { execSync('docker ps', { stdio: 'ignore' }); } catch { dockerAvailable = false; }

    // External probe first (exposed via docker port mapping)
    const ext = await request.get('http://localhost:8083/actuator/health/liveness');
    expect(ext.status(), 'R103 regression: ftp-web /actuator/health/liveness external probe').toBe(200);

    // Internal probe via docker exec — same path, no proxy in between
    if (dockerAvailable) {
      const code = execSync(
        `docker exec mft-ftp-web-service curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/actuator/health/liveness`,
        { timeout: 10000 }
      ).toString().trim();
      expect(code, 'R103 regression: ftp-web internal healthcheck').toBe('200');
    }
  });

  test('R93: Spring /actuator/heapdump + /actuator/threaddump exposed on every Java service @regression', async ({ api }) => {
    // R93 exposed the diagnostic endpoints platform-wide for perf work.
    // Previously: 404 (not exposed) or 500 (fallthrough to static handler).
    // Verify 200 on a representative set — skips if Spring Security requires auth
    // on a specific service (acceptable: 200 OR 401 with WWW-Authenticate).
    const probes = [
      { svc: 'onboarding-api',  port: 8080 },
      { svc: 'config-service',  port: 8084 },
      { svc: 'keystore-manager', port: 8093 },
      { svc: 'sftp-service',    port: 8081 },
    ];
    for (const { svc, port } of probes) {
      for (const ep of ['threaddump', 'heapdump', 'prometheus']) {
        const resp = await api.get(`http://localhost:${port}/actuator/${ep}`);
        const ok = [200, 401].includes(resp.status());
        expect(ok, `R93 regression: ${svc}/actuator/${ep} got ${resp.status()} (expect 200 or 401)`).toBe(true);
      }
    }
  });
});

test.describe('Regression pins — flow engine + data plane', () => {
  test('R86: byte-level E2E preserves content through EXECUTE_SCRIPT step @regression', async ({ api }) => {
    // R86 closed the arc where EXECUTE_SCRIPT needed to pass through VFS.
    // This pin uploads a file known to match regtest-f7-script-mailbox,
    // waits for the flow to complete at the engine level (flowStatus),
    // and asserts the transformation actually happened.
    const payload = 'hello r-regression pin first line\nsecond\nthird\n';
    const filename = `r-regression-${Date.now()}.dat`;
    await sftpUploadVia('tranzfer-mft_default', { content: payload, filename });

    // Wait up to 20s for the activity-monitor to see the entry AND flowStatus=COMPLETED.
    // NB: top-level status may stay PENDING (see R100 bug pin below).
    const entry = await expect.poll(
      async () => (await findActivityByFilename(api, filename)),
      { timeout: 20000, message: `R86 regression: activity-monitor missed upload ${filename}` }
    ).toBeTruthy();

    const polled = await findActivityByFilename(api, filename);
    expect(polled.flowName, 'R86 regression: flow did not match regtest-f7-script-mailbox').toBe('regtest-f7-script-mailbox');
    expect(polled.flowStatus, 'R86 regression: flowStatus is not COMPLETED').toBe('COMPLETED');
    expect(polled.fileSizeBytes, 'R86 regression: file size mismatch').toBe(Buffer.byteLength(payload));
  });

  test('R100 OPEN: mailbox flow top-level `status` must reach COMPLETED when flowStatus is COMPLETED @regression', async ({ api }) => {
    // FAILING INTENTIONALLY — this pin represents the R100 finding that is
    // still open: mailbox flows complete at the engine (flowStatus=COMPLETED)
    // but the activity-monitor top-level `status` stays PENDING indefinitely
    // with completedAt=null. When dev-team fixes this, the `test.fail()`
    // wrapper flips the test to passing and raises an alert that the pin
    // needs to be promoted to a regular `test()`.
    test.fail(true, 'R100 open: mailbox status transition not yet fixed');

    const filename = `r100-pin-${Date.now()}.dat`;
    await sftpUploadVia('tranzfer-mft_default', { content: 'r100 pin\n', filename });

    const entry = await waitForActivityStatus(api, filename, {
      terminalStatus: 'COMPLETED',
      timeout: 20000,
    });
    expect(entry.completedAt, 'R100: completedAt should be set when status=COMPLETED').not.toBeNull();
  });

  test('R89: GET /api/servers/:bogusId returns 404, not 500 @regression', async ({ api }) => {
    const resp = await api.get('/api/servers/00000000-0000-0000-0000-000000000000');
    expect([404], `R89 regression: missing server returned ${resp.status()} (expect 404)`).toContain(resp.status());
  });

  test('R92: listener bind_state writeback persists after rebind @regression', async ({ api }) => {
    // R92 fixed BindStateWriter — rebind used to leave bind_state stale because
    // the writeback happened in a separate transaction that wasn't flushing the
    // reloaded entity. Pin verifies that fetching a server after rebind shows
    // the new bindState.
    const listResp = await api.get('/api/servers');
    if (listResp.status() !== 200) test.skip();
    const list = await listResp.json();
    const bound = list.find(s => s.bindState === 'BOUND');
    test.skip(!bound, 'no BOUND server available in fixture — run build-regression-fixture.sh first');

    const rebindResp = await api.post(`/api/servers/${bound.id}/rebind`);
    expect([200, 202, 204].includes(rebindResp.status()),
      `R92 regression: rebind returned ${rebindResp.status()}`).toBe(true);

    // Poll for bindState to settle back to BOUND (rebind is async on some protocols)
    await expect.poll(async () => {
      const r = await api.get(`/api/servers/${bound.id}`);
      if (r.status() !== 200) return null;
      const s = await r.json();
      return s.bindState;
    }, { timeout: 15000, message: 'R92 regression: bind_state never returned to BOUND' }).toBe('BOUND');
  });
});

test.describe('Regression pins — AOT readiness', () => {
  test('R97: ai-engine @Async @EventListener initializes cleanly @regression', async ({ request }) => {
    // R97 surfaced that AgentRegistrar had @Async on a concrete-class method that
    // failed under JDK-dynamic-proxy when AOT was on. R102 added
    // @EnableAsync(proxyTargetClass=true). Verify ai-engine liveness (evidence
    // that context refresh completed without the BeanInitializationException).
    const resp = await request.get('http://localhost:8091/actuator/health/liveness');
    expect(resp.status(), 'R97 regression: ai-engine health not 200').toBe(200);
  });

  test('R99: @EnableJpaRepositories on encryption/keystore/license/storage-manager/ai-engine include shared.repository.security @regression', async () => {
    // R99 added shared.repository.security to @EnableJpaRepositories on the 5
    // services that used to crash with RolePermissionRepository missing under AOT.
    // Pin by reading the source — grep works on both filesystem and in a container build.
    const fs = require('fs');
    const path = require('path');
    const targets = [
      'encryption-service/src/main/java/com/filetransfer/encryption/EncryptionServiceApplication.java',
      'keystore-manager/src/main/java/com/filetransfer/keystore/KeystoreManagerApplication.java',
      'license-service/src/main/java/com/filetransfer/license/LicenseServiceApplication.java',
      'storage-manager/src/main/java/com/filetransfer/storage/StorageManagerApplication.java',
      'ai-engine/src/main/java/com/filetransfer/ai/AiEngineApplication.java',
    ];
    const repoRoot = path.resolve(__dirname, '../../..');
    for (const rel of targets) {
      const abs = path.join(repoRoot, rel);
      if (!fs.existsSync(abs)) continue; // skipped in minimal test envs
      const src = fs.readFileSync(abs, 'utf8');
      expect(src, `R99 regression: ${rel} is missing shared.repository.security in @EnableJpaRepositories`)
        .toMatch(/shared\.repository\.security/);
    }
  });
});

test.describe('Regression pins — backend log hygiene under hot path', () => {
  test('no fresh ERROR-level logs on onboarding-api during a burst of authenticated GETs @regression', async ({ api }) => {
    // Catch-all regression: historical bugs often showed up as ERROR spam that
    // was ignored until perf runs made them visible. Snapshot, run a burst,
    // then assert no new ERRORs (ignoring known-benign patterns).
    let dockerAvailable = true;
    try { execSync('docker ps', { stdio: 'ignore' }); } catch { dockerAvailable = false; }
    test.skip(!dockerAvailable, 'docker not available');

    const snap = await snapshotServiceErrors('onboarding-api');
    await Promise.all(
      Array.from({ length: 40 }, () => api.get('/api/activity-monitor?page=0&size=5'))
    );
    await new Promise(r => setTimeout(r, 2000)); // let logs flush
    await assertNoFreshErrors('onboarding-api', snap, {
      // Ignore: SPIRE socket retry before agent is ready (documented self-heal)
      ignoreRegex: /SPIFFE.*Workload API unavailable|Redis.*deserialization/,
    });
  });
});
