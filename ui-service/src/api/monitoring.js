import { onboardingApi } from './client'

/**
 * Thin wrapper around the MonitoringProxyController (onboarding-api).
 * All calls are authenticated via the existing bearer token interceptor
 * on `onboardingApi`. The backend forwards to Prometheus / Loki /
 * Alertmanager and returns their native JSON shapes, so the UI can
 * use Prometheus query result handling conventions directly.
 */

const BASE = '/api/v1/monitoring'

// ── Prometheus ──────────────────────────────────────────────────────

export const promQuery = (query, time = null) =>
  onboardingApi.get(`${BASE}/prometheus/query`, {
    params: { query, ...(time ? { time } : {}) },
  }).then(r => r.data)

export const promRange = (query, start, end, step = '30s') =>
  onboardingApi.get(`${BASE}/prometheus/query_range`, {
    params: { query, start, end, step },
  }).then(r => r.data)

export const promTargets = () =>
  onboardingApi.get(`${BASE}/prometheus/targets`).then(r => r.data)

export const promAlerts = () =>
  onboardingApi.get(`${BASE}/prometheus/alerts`).then(r => r.data)

export const promLabels = () =>
  onboardingApi.get(`${BASE}/prometheus/labels`).then(r => r.data)

// ── Loki ────────────────────────────────────────────────────────────

export const lokiRange = (query, start, end, limit = 200, direction = 'backward') =>
  onboardingApi.get(`${BASE}/loki/query_range`, {
    params: { query, start, end, limit, direction },
  }).then(r => r.data)

export const lokiLabels = () =>
  onboardingApi.get(`${BASE}/loki/labels`).then(r => r.data)

export const lokiLabelValues = (name) =>
  onboardingApi.get(`${BASE}/loki/label/${encodeURIComponent(name)}/values`).then(r => r.data)

// ── Alertmanager ────────────────────────────────────────────────────

export const alertmanagerAlerts = () =>
  onboardingApi.get(`${BASE}/alertmanager/alerts`).then(r => r.data)

export const alertmanagerGroups = () =>
  onboardingApi.get(`${BASE}/alertmanager/groups`).then(r => r.data)

// ── Aggregated overview ─────────────────────────────────────────────

export const overview = () =>
  onboardingApi.get(`${BASE}/overview`).then(r => r.data)

// ── Time range helpers (used by charts + log search) ────────────────

/**
 * Convert a relative shorthand like "5m", "1h", "24h", "7d" into absolute
 * unix-seconds start/end strings ready for Prometheus/Loki. Returns
 * `{start, end, step}` where step is auto-sized to ~300 points for
 * the plot resolution.
 */
export function rangeForWindow(window) {
  const now = Math.floor(Date.now() / 1000)
  const secondsByWindow = {
    '5m':  5 * 60,
    '15m': 15 * 60,
    '1h':  60 * 60,
    '6h':  6 * 60 * 60,
    '24h': 24 * 60 * 60,
    '7d':  7 * 24 * 60 * 60,
  }
  const duration = secondsByWindow[window] ?? secondsByWindow['1h']
  const start = now - duration
  // Target ~200 plot points
  const stepSec = Math.max(1, Math.floor(duration / 200))
  return {
    start: String(start),
    end: String(now),
    step: stepSec >= 60 ? `${Math.floor(stepSec / 60)}m` : `${stepSec}s`,
  }
}

/** Extract a single scalar from a Prometheus instant query response. */
export function scalarValue(response, defaultValue = null) {
  const result = response?.data?.result
  if (!Array.isArray(result) || result.length === 0) return defaultValue
  const pair = result[0]?.value
  if (!Array.isArray(pair) || pair.length !== 2) return defaultValue
  const n = Number(pair[1])
  return Number.isFinite(n) ? n : defaultValue
}

/** Extract time-series matrix into Recharts-friendly `[{time, value}, ...]`. */
export function toChartSeries(response, seriesName = 'value') {
  const result = response?.data?.result
  if (!Array.isArray(result) || result.length === 0) return []
  // Single-series case (most panels)
  const series = result[0]
  const values = series?.values
  if (!Array.isArray(values)) return []
  return values.map(([ts, v]) => ({
    time: new Date(Number(ts) * 1000).toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit' }),
    [seriesName]: Number(v),
  }))
}
