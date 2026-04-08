/**
 * Shared authentication helpers for all k6 test scripts.
 * Usage: import { login, authHeaders } from './lib/auth.js';
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const ONBOARDING_PORT = __ENV.ONBOARDING_PORT || '8080';

export const BASE = BASE_URL;
export const ONBOARDING = `${BASE_URL}:${ONBOARDING_PORT}`;

/**
 * Login and return JWT token. Use in setup() for shared token.
 */
export function login(email, password) {
  email    = email    || __ENV.ADMIN_EMAIL || 'admin@filetransfer.local';
  password = password || __ENV.ADMIN_PASS  || 'Admin@1234';

  const res = http.post(
    `${ONBOARDING}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(res, { 'login 200': (r) => r.status === 200 });

  if (res.status !== 200) {
    console.error(`Login failed: HTTP ${res.status} — ${res.body}`);
    return null;
  }

  const body = JSON.parse(res.body);
  return body.token || body.accessToken || body.jwt || null;
}

/**
 * Returns headers object with Authorization + Content-Type.
 */
export function authHeaders(token, extra) {
  return Object.assign({
    'Content-Type':  'application/json',
    'Authorization': `Bearer ${token}`,
  }, extra || {});
}

/**
 * Returns headers for internal service-to-service calls.
 */
export function internalHeaders(token) {
  return {
    'Content-Type':    'application/json',
    'Authorization':   `Bearer ${token}`,
    'X-Internal-Key':  __ENV.INTERNAL_KEY || 'mft-internal-secret-2026',
  };
}

/**
 * Decode JWT payload (no signature verification — just for extracting claims).
 */
export function decodeJwt(token) {
  if (!token) return {};
  const parts = token.split('.');
  if (parts.length !== 3) return {};
  try {
    const payload = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(payload);
  } catch (_) {
    return {};
  }
}
