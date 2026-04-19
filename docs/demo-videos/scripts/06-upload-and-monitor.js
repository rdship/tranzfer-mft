/*  SCENARIO 06 — Upload a file via SFTP, watch it appear in Activity Monitor
 *  Runtime: ~110 s
 *  Side effect: uploads a file via regtest-sftp-1:2231 before starting the browser,
 *               so the row appears within the video window.
 */
const path = require('path');
const fs = require('fs');
const { execSync } = require('child_process');
const { setup, login, slowGoto, pulse, teardown } = require('./_helpers');

function uploadDemoFile() {
  const FN = `demo-upload-${Date.now()}.csv`;
  const tmp = `/tmp/${FN}`;
  fs.writeFileSync(tmp, `demo file for Activity Monitor — ${new Date().toISOString()}`);
  execSync(
    `docker run --rm --network tranzfer-mft_default -v ${tmp}:/in.dat alpine:3.19 sh -c ` +
    `"apk add -q openssh-client sshpass 2>/dev/null; sshpass -p RegTest@2026! sftp ` +
    `-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -P 2231 ` +
    `regtest-sftp-1@sftp-service <<EOF >/dev/null 2>&1
put /in.dat /upload/${FN}
bye
EOF"`,
    { stdio: 'inherit' }
  );
  fs.unlinkSync(tmp);
  return FN;
}

(async () => {
  const OUT = path.join(__dirname, '..', 'tutorial');
  const name = '06-upload-and-monitor';
  const s = await setup(OUT, name);

  // Upload first so the row is visible when we navigate
  const FN = uploadDemoFile();
  console.log(`uploaded: ${FN}`);

  await login(s.page);
  await slowGoto(s.page, 'activity-monitor', 2500);

  // Wait for the row to appear / let auto-refresh kick in
  await pulse(s.page, 4000);
  // Scroll the table
  await s.page.evaluate(() => window.scrollTo({ top: 300, behavior: 'smooth' }));
  await pulse(s.page, 3000);
  // Search for our file
  const search = s.page.locator('input[placeholder*="Filename" i]').first();
  if (await search.count() > 0) {
    await search.fill(FN).catch(()=>{});
    await pulse(s.page, 3500);
  }
  await pulse(s.page, 1500);
  await teardown(s, name);
})().catch(e => { console.error('fail', e.message); process.exit(1); });
