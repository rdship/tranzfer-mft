import axios from 'axios'

const withAuth = (instance) => {
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('token')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })
  instance.interceptors.response.use(
    (res) => res,
    (err) => {
      if (err.response?.status === 401) {
        // Only redirect to login if the auth token itself is invalid.
        // Don't redirect when a management endpoint (gateway, dmz, proxy)
        // rejects the request — the user is still logged in, just lacks
        // permission for that specific service.
        const url = err.config?.url || ''
        const isAuthEndpoint = url.includes('/api/auth/')
        const isTokenExpired = !localStorage.getItem('token')
        if (isAuthEndpoint || isTokenExpired) {
          localStorage.removeItem('token')
          localStorage.removeItem('user')
          window.location.href = '/login'
        }
        // For non-auth 401s: let the component handle it (shows error toast)
      }
      return Promise.reject(err)
    }
  )
  return instance
}

/**
 * API Gateway mode (production): all requests go through one gateway URL.
 * Direct mode (development): each service on its own port.
 *
 * Set VITE_API_GATEWAY_URL in .env for production:
 *   VITE_API_GATEWAY_URL=https://mft.yourcompany.com
 *
 * If not set, falls back to direct service ports (dev mode).
 */
const GATEWAY_URL = import.meta.env.VITE_API_GATEWAY_URL

// In gateway mode, ALL requests go through one URL (the gateway routes internally)
// In direct mode, each service gets its own port
// Default to HTTPS gateway. Dev override: VITE_API_GATEWAY_URL=http://localhost:80
const DEFAULT_GATEWAY = window.location.origin  // Same origin as the UI (gateway serves both)
export const onboardingApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const onboardingClient = onboardingApi
export const configApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const configClient = configApi
export const analyticsApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const licenseApi = axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY })
export const gatewayApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const dmzApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))

// DMZ proxy requires X-Internal-Key for all management endpoints
dmzApi.interceptors.request.use((config) => {
  const controlKey = localStorage.getItem('controlKey')
  if (controlKey) config.headers['X-Internal-Key'] = controlKey
  return config
})
export const keystoreApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8093' }))
export const screeningApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8092' }))
export const storageApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8096' }))
export const aiApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8091' }))
export const notificationApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8097' }))
export const sentinelApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8098' }))
export const ediApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8095' }))

// Log correlation IDs from API errors for debugging
const logCorrelationId = (error) => {
  const correlationId = error?.response?.headers?.['x-correlation-id']
  const errorCode = error?.response?.data?.code
  if (correlationId) {
    console.debug(`[API] ${error.config?.method?.toUpperCase()} ${error.config?.url} → ${error.response?.status} [${correlationId}] ${errorCode || ''}`)
  }
  return Promise.reject(error)
}

onboardingApi.interceptors.response.use(r => r, logCorrelationId)
configApi.interceptors.response.use(r => r, logCorrelationId)
licenseApi.interceptors.response.use(r => r, logCorrelationId)
analyticsApi.interceptors.response.use(r => r, logCorrelationId)
dmzApi.interceptors.response.use(r => r, logCorrelationId)
keystoreApi.interceptors.response.use(r => r, logCorrelationId)
screeningApi.interceptors.response.use(r => r, logCorrelationId)
storageApi.interceptors.response.use(r => r, logCorrelationId)
aiApi.interceptors.response.use(r => r, logCorrelationId)
notificationApi.interceptors.response.use(r => r, logCorrelationId)
sentinelApi.interceptors.response.use(r => r, logCorrelationId)
ediApi.interceptors.response.use(r => r, logCorrelationId)
