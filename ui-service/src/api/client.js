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
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        window.location.href = '/login'
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
export const onboardingApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8080' }))
export const onboardingClient = onboardingApi
export const configApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8084' }))
export const configClient = configApi
export const analyticsApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8090' }))
export const licenseApi = axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8089' })
export const gatewayApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8085' }))
export const dmzApi = withAuth(axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8088' }))

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
