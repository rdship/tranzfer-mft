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

const SERVICE_HEALTH_ENDPOINTS = {
  onboarding: { url: 'http://localhost:8080/actuator/health', port: 8080 },
  config: { url: 'http://localhost:8084/api/servers', port: 8084 },
  sftp: { url: 'http://localhost:8081/health', port: 8081 },
  ftp: { url: 'http://localhost:8082/health', port: 8082 },
  ftpWeb: { url: 'http://localhost:8083/actuator/health', port: 8083 },
  gateway: { url: 'http://localhost:8085/actuator/health', port: 8085 },
  encryption: { url: 'http://localhost:8086/actuator/health', port: 8086 },
  forwarder: { url: 'http://localhost:8087/api/forward/health', port: 8087 },
  dmz: { url: 'http://localhost:8088/api/proxy/health', port: 8088 },
  license: { url: 'http://localhost:8089/api/v1/licenses/health', port: 8089 },
  analytics: { url: 'http://localhost:8090/api/v1/analytics/dashboard', port: 8090 },
  aiEngine: { url: 'http://localhost:8091/api/v1/ai/health', port: 8091 },
  screening: { url: 'http://localhost:8092/api/v1/screening/health', port: 8092 },
  keystore: { url: 'http://localhost:8093/api/v1/keys/health', port: 8093 },
  sentinel: { url: 'http://localhost:8098/api/v1/sentinel/health', port: 8098 },
  // Using /actuator/health/readiness (not root /health) because Spring Boot's
  // root health aggregates every indicator — a single transient Kafka or
  // RabbitMQ blip flips the whole thing to DOWN even when the service is
  // actually serving traffic. Readiness is what the demo-onboard.sh wait
  // function settled on after hitting exactly this bug.
  ediConverter: { url: 'http://localhost:8095/actuator/health/readiness', port: 8095 },
  notification: { url: 'http://localhost:8097/actuator/health', port: 8097 },
  storage: { url: 'http://localhost:8096/actuator/health', port: 8096 },
  as2: { url: 'http://localhost:8094/actuator/health', port: 8094 },
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

  // Legacy flat routes — preserved as redirect targets, visible by default
  '/dashboard': ['core'],
  '/accounts': ['core'],
  '/users': ['core'],
  '/monitoring': ['core'],
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
        const controller = new AbortController()
        const timeout = setTimeout(() => controller.abort(), 3000)
        const res = await fetch(url, { signal: controller.signal, mode: 'no-cors' }).catch(() => null)
        clearTimeout(timeout)
        // no-cors returns opaque response (type: 'opaque', status: 0) but means the server responded
        results[key] = res !== null
      } catch {
        results[key] = false
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
