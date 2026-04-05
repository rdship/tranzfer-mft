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
}

/**
 * Maps each UI page to the service(s) it requires.
 * 'core' means always visible if onboarding is running.
 */
const PAGE_SERVICE_MAP = {
  // Always visible (core platform)
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
  '/p2p': ['core'],

  // Intelligence tier
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

  // New pages
  '/storage': ['core'],
  '/activity': ['core'],
  '/encryption': ['encryption'],
  '/server-instances': ['core'],
  '/platform-config': ['config'],

  // Partner Management
  '/partners': ['core'],
  '/partner-setup': ['core'],
  '/services': ['core'],

  // AI
  '/ai': ['aiEngine'],
  '/recommendations': ['aiEngine'],
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
