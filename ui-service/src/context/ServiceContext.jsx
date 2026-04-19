import { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { onboardingApi, licenseApi } from '../api/client'

/**
 * ServiceContext — drives the entire UI visibility.
 *
 * On load:
 * 1. Checks which microservices are reachable (health endpoints)
 * 2. Checks license to see which services are licensed
 * 3. Merges: a page shows ONLY if the service is both running AND licensed
 * 4. Admin can also manually enable/disable visibility per service in Settings
 *
 * This means:
 * - Customer only bought SFTP + Screening? They only see those pages.
 * - AI engine not deployed? AI pages don't show.
 * - Everything is dynamic — no code changes needed.
 */

// N13 fix: Health checks routed through API gateway (not direct localhost:PORT).
// Browser can't reach Docker internal ports. Gateway routes all /api/* paths
// to the correct backend. Uses relative paths so it works in any environment.
const SERVICE_HEALTH_ENDPOINTS = {
  onboarding: { url: '/api/pipeline/health' },
  config: { url: '/api/servers/health' },
  sftp: { url: '/api/pipeline/health' },
  ftp: { url: '/api/pipeline/health' },
  ftpWeb: { url: '/api/pipeline/health' },
  gateway: { url: '/api/gateway/status' },
  encryption: { url: '/api/encrypt/status' },
  forwarder: { url: '/api/forward/health' },
  dmz: { url: '/api/proxy/health' },
  license: { url: '/api/v1/licenses/health' },
  analytics: { url: '/api/v1/analytics/health' },
  aiEngine: { url: '/api/v1/ai/health' },
  screening: { url: '/api/v1/screening/health' },
  keystore: { url: '/api/v1/keys/health' },
  sentinel: { url: '/api/v1/sentinel/health' },
  ediConverter: { url: '/api/v1/convert/health' },
  notification: { url: '/api/notifications/health' },
  storage: { url: '/api/v1/storage/health' },
  as2: { url: '/api/as2/health' },
}

/**
 * Maps each UI page to the service(s) it requires.
 * 'core' means always visible if onboarding is running.
 */
const PAGE_SERVICE_MAP = {
  // Operations — the unified shell for Dashboard / Fabric / Activity / Live / Journey
  '/operations':          ['core'],
  '/operations/fabric':   ['core'],
  '/operations/activity': ['core'],
  '/operations/live':     ['core'],
  '/operations/journey':  ['core'],

  // Legacy flat routes — preserved as redirect targets, visible by default.
  // R128: removed duplicate '/monitoring' key here — it is defined once
  // below with the load-bearing comment. Having both entries produced the
  // "duplicate key '/monitoring'" warning vite printed on every build
  // (tester flagged it in the R127 FULL acceptance report).
  '/dashboard': ['core'],
  '/accounts': ['core'],
  '/users': ['core'],
  '/terminal': ['core'],
  '/license': ['core'],
  '/settings': ['core'],

  // Transfer services
  '/folder-mappings': ['core'],
  '/flows': ['config'],
  '/servers': ['config'],
  '/external-destinations': ['config'],
  '/security-profiles': ['config'],

  // Protocol-specific
  '/gateway': ['gateway'],
  '/dmz-proxy': ['dmz'],
  '/proxy-groups': ['core'],
  '/p2p': ['core'],

  // Intelligence tier
  '/observatory': ['analytics'],
  '/analytics': ['analytics'],
  '/predictions': ['analytics'],
  '/logs': ['core'],
  '/journey': ['core'],

  // Optional services
  '/keystore': ['keystore'],
  '/screening': ['screening'],
  '/sla': ['config'],
  '/connectors': ['config'],
  '/scheduler': ['config'],

  // Pages previously invisible to the gating system — they fell through
  // to the `return true` default in isPageVisible. Listing them here makes
  // them honour their actual backend dependency so the sidebar correctly
  // hides them when that service is down.
  '/2fa': ['core'],               // onboarding-api owns 2FA endpoints
  '/api-console': ['core'],       // onboarding-api serves /actuator/*
  '/blockchain': ['core'],        // onboarding-api owns /api/v1/blockchain
  '/compliance': ['config'],      // config-service owns /api/compliance
  '/folder-templates': ['config'],// config-service owns /api/folder-templates
  '/tenants': ['core'],           // onboarding-api owns /api/v1/tenants
  '/vfs-storage': ['config'],     // config-service owns /api/vfs

  // New pages
  '/storage': ['core'],
  '/cas-dedup': ['analytics'],
  '/activity': ['core'],
  '/activity-monitor': ['core'],
  '/encryption': ['encryption'],
  '/server-instances': ['core'],
  '/platform-config': ['config'],

  // Partner Management
  '/partners': ['core'],
  '/partner-setup': ['core'],
  '/services': ['core'],

  // AI
  '/ai': ['aiEngine'],
  '/sentinel': ['sentinel'],
  '/circuit-breakers': ['sentinel'],
  '/recommendations': ['aiEngine'],

  // New admin pages
  '/dlq': ['core'],
  '/quarantine': ['screening'],
  '/file-manager': ['ftpWeb'],
  '/cluster': ['core'],
  '/auto-onboarding': ['aiEngine'],
  '/migration': ['config'],
  '/threat-intelligence': ['aiEngine'],
  '/edi-training': ['aiEngine'],
  '/proxy-intelligence': ['aiEngine'],

  // EDI converter — always visible (page handles service-down gracefully via useQuery error)
  '/edi': ['core'],
  '/edi-mapping': ['core'],
  '/edi-partners': ['core'],

  // AS2/AS4
  '/as2-partnerships': ['as2'],

  // Notifications
  '/notifications': ['notification'],

  // Config Export (Phase 1 — read-only export side)
  '/config-export': ['onboarding'],

  // Monitoring — embedded Prometheus + Grafana + Alertmanager. Always
  // visible because the page itself probes each tool's reachability
  // and renders a fallback card when one is down. It's valuable to
  // the admin even when all services are down (shows what's missing).
  '/monitoring': ['core'],

  // Database Advisory — published Postgres tuning recommendations
  // backed by R23 workload audit. Always visible to admins.
  '/db-advisory': ['core'],
}

const ServiceContext = createContext(null)

export function ServiceProvider({ children }) {
  const [services, setServices] = useState({})
  const [loading, setLoading] = useState(true)
  const [overrides, setOverrides] = useState(() => {
    try { return JSON.parse(localStorage.getItem('service-overrides') || '{}') } catch { return {} }
  })

  const detectServices = useCallback(async () => {
    const results = {}
    const checks = Object.entries(SERVICE_HEALTH_ENDPOINTS).map(async ([key, { url }]) => {
      try {
        // N13 fix: use authenticated API client through gateway (not raw fetch to localhost)
        await onboardingApi.get(url, { timeout: 4000 })
        results[key] = true
      } catch (err) {
        // 401/403 means service IS running but auth differs — still "reachable"
        results[key] = err?.response?.status === 401 || err?.response?.status === 403
      }
    })
    await Promise.all(checks)

    // Core is always true if we're running
    results.core = true
    setServices(results)
    setLoading(false)
  }, [])

  useEffect(() => {
    detectServices()
    const interval = setInterval(detectServices, 60000) // re-check every 60s
    return () => clearInterval(interval)
  }, [detectServices])

  const isServiceRunning = (serviceKey) => {
    if (overrides[serviceKey] === false) return false // Admin manually disabled
    if (overrides[serviceKey] === true) return true   // Admin manually enabled
    return services[serviceKey] !== false
  }

  const isPageVisible = (path) => {
    const required = PAGE_SERVICE_MAP[path]
    if (!required) return true // Unknown page — show by default
    return required.every(svc => isServiceRunning(svc))
  }

  const toggleOverride = (serviceKey, enabled) => {
    const next = { ...overrides, [serviceKey]: enabled }
    setOverrides(next)
    localStorage.setItem('service-overrides', JSON.stringify(next))
  }

  const clearOverrides = () => {
    setOverrides({})
    localStorage.removeItem('service-overrides')
  }

  return (
    <ServiceContext.Provider value={{
      services, loading, isServiceRunning, isPageVisible,
      overrides, toggleOverride, clearOverrides,
      serviceList: Object.keys(SERVICE_HEALTH_ENDPOINTS),
      pageServiceMap: PAGE_SERVICE_MAP,
    }}>
      {children}
    </ServiceContext.Provider>
  )
}

export const useServices = () => useContext(ServiceContext)
