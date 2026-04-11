import { onboardingApi } from './client'

/**
 * Config Export API client — mirrors ConfigExportController on onboarding-api.
 *
 * Feature flag: requires Phase 1 backend (commit r7) to be deployed.
 * All endpoints require ADMIN role.
 */

/** Get counts of entity types the exporter can include. */
export function getExportScope() {
  return onboardingApi.get('/api/v1/config-export/scope').then(r => r.data)
}

/**
 * Build a bundle containing the selected entity types.
 * Returns the full ConfigBundle JSON — the caller is responsible for
 * triggering a browser download.
 */
export function buildBundle(scope) {
  return onboardingApi
    .post('/api/v1/config-export', { scope })
    .then(r => r.data)
}

/** Lightweight feature-flag check — returns schema version. */
export function getExportInfo() {
  return onboardingApi.get('/api/v1/config-export/info').then(r => r.data)
}

/**
 * Helper — given a ConfigBundle object, trigger a browser download of it
 * as a JSON file. Name uses the source environment + timestamp so multiple
 * exports don't collide on the operator's Downloads folder.
 */
export function downloadBundleAsJson(bundle) {
  const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  const ts = new Date().toISOString().replace(/[:.]/g, '-')
  const env = (bundle?.sourceEnvironment || 'unknown').toLowerCase()
  a.href = url
  a.download = `tranzfer-config-${env}-${ts}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
