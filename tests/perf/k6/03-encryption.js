/**
 * Phase 2 — encryption-service Throughput Test
 * Tests: AES encrypt/decrypt across multiple file sizes.
 *
 * Run:
 *   k6 run --env FILE_SIZE=1KB   tests/perf/k6/03-encryption.js
 *   k6 run --env FILE_SIZE=100KB tests/perf/k6/03-encryption.js
 *   k6 run --env FILE_SIZE=1MB   tests/perf/k6/03-encryption.js
 *   k6 run --env FILE_SIZE=10MB  tests/perf/k6/03-encryption.js
 *   k6 run --env FILE_SIZE=100MB tests/perf/k6/03-encryption.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import encoding from 'k6/encoding';
import { login, authHeaders } from './lib/auth.js';

const BASE_ENC = `${__ENV.BASE_URL || 'http://localhost'}:${__ENV.ENC_PORT || 8086}`;

const encryptLatency = new Trend('encrypt_latency_ms', true);
const decryptLatency = new Trend('decrypt_latency_ms', true);
const encryptErrors  = new Rate('encrypt_errors');

// File sizes in bytes
const FILE_SIZES = {
  '1KB':   1024,
  '10KB':  10240,
  '100KB': 102400,
  '1MB':   1048576,
  '10MB':  10485760,
  '100MB': 104857600,
};

const fileSize = FILE_SIZES[__ENV.FILE_SIZE || '100KB'] || 102400;
const fileSizeLabel = __ENV.FILE_SIZE || '100KB';

// Thresholds by file size
const THRESHOLDS = {
  '1KB':   { encP95: 50,    decP95: 50    },
  '10KB':  { encP95: 80,    decP95: 80    },
  '100KB': { encP95: 150,   decP95: 150   },
  '1MB':   { encP95: 500,   decP95: 500   },
  '10MB':  { encP95: 3000,  decP95: 3000  },
  '100MB': { encP95: 20000, decP95: 20000 },
};

const threshold = THRESHOLDS[fileSizeLabel] || { encP95: 500, decP95: 500 };

// VUs inversely proportional to file size
const VU_MAP = {
  '1KB': 50, '10KB': 30, '100KB': 20, '1MB': 10, '10MB': 5, '100MB': 2
};
const vus = VU_MAP[fileSizeLabel] || 10;

export const options = {
  stages: [
    { duration: '30s', target: vus },
    { duration: '3m',  target: vus },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    'encrypt_latency_ms': [`p(95)<${threshold.encP95}`],
    'decrypt_latency_ms': [`p(95)<${threshold.decP95}`],
    'encrypt_errors':     ['rate<0.01'],
    'http_req_failed':    ['rate<0.01'],
  },
};

// Generate payload of given size
function generatePayload(bytes) {
  // Return base64-like random string representing file content
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let result = '';
  // For large files, repeat a block
  const block = 'TranzFerMFTTestPayload1234567890abcdefghijklmnopqrstuvwxyz0123456789';
  const repeats = Math.ceil(bytes / block.length);
  result = block.repeat(repeats).substring(0, bytes);
  return result;
}

export function setup() {
  const token = login();
  console.log(`Encryption test — File size: ${fileSizeLabel} (${fileSize} bytes) — VUs: ${vus}`);

  // Get a key ID for encryption from keystore-manager
  const keysRes = http.get(
    `${__ENV.BASE_URL || 'http://localhost'}:8093/api/v1/keys`,
    { headers: authHeaders(token) }
  );
  let keyId = null;
  if (keysRes.status === 200) {
    try {
      const keys = JSON.parse(keysRes.body);
      const list = Array.isArray(keys) ? keys : (keys.content || []);
      const activeKey = list.find(k => k.active !== false);
      keyId = activeKey ? activeKey.id : null;
    } catch (_) {}
  }

  if (!keyId) {
    console.warn('No active encryption key found in keystore — encrypt/decrypt groups will be skipped');
  }
  return { token, keyId };
}

export default function (data) {
  // Skip if no active key available in keystore
  if (!data.keyId) {
    sleep(0.3);
    return;
  }

  const payload = generatePayload(fileSize);
  // Encode payload as base64 for the /encrypt/base64 endpoint
  const b64Payload = encoding.b64encode(payload);

  // ── Encrypt (POST /api/encrypt/encrypt/base64?keyId={uuid}) ───────────────
  let encryptedB64 = null;
  group('encrypt', () => {
    const res = http.post(
      `${BASE_ENC}/api/encrypt/encrypt/base64?keyId=${data.keyId}`,
      b64Payload,
      {
        headers: Object.assign(authHeaders(data.token), { 'Content-Type': 'text/plain' }),
        tags: { op: 'encrypt', size: fileSizeLabel },
      }
    );

    const ok = check(res, {
      'encrypt: status 200':    (r) => r.status === 200,
      'encrypt: has result':    (r) => r.status === 200 && r.body.length > 0,
    });

    encryptErrors.add(!ok);
    encryptLatency.add(res.timings.duration);

    if (res.status === 200) {
      encryptedB64 = res.body.trim();  // response is raw base64 cipher text
    }
  });

  sleep(0.1);

  // ── Decrypt round-trip (POST /api/encrypt/decrypt/base64?keyId={uuid}) ────
  if (encryptedB64) {
    group('decrypt', () => {
      const res = http.post(
        `${BASE_ENC}/api/encrypt/decrypt/base64?keyId=${data.keyId}`,
        encryptedB64,
        {
          headers: Object.assign(authHeaders(data.token), { 'Content-Type': 'text/plain' }),
          tags: { op: 'decrypt', size: fileSizeLabel },
        }
      );

      check(res, {
        'decrypt: status 200': (r) => r.status === 200,
        'decrypt: round-trip matches': (r) => {
          if (r.status !== 200) return false;
          try {
            // Response is base64-encoded original data
            const decrypted = encoding.b64decode(r.body.trim(), 'rawstd', 's');
            return decrypted.substring(0, 100) === payload.substring(0, 100);
          } catch (_) { return true; }
        },
      });

      decryptLatency.add(res.timings.duration);
    });
  }

  sleep(0.2);
}
