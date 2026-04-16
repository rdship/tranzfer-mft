import axios from 'axios'

let isRefreshing = false
let failedQueue = []

const processQueue = (error, token = null) => {
  failedQueue.forEach(prom => {
    if (error) prom.reject(error)
    else prom.resolve(token)
  })
  failedQueue = []
}

const withAuth = (instance) => {
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('token')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })
  instance.interceptors.response.use(
    (res) => res,
    async (err) => {
      const originalRequest = err.config
      if (err.response?.status === 401 && !originalRequest._retry) {
        // Don't retry refresh endpoint itself
        if (originalRequest.url?.includes('/api/auth/refresh')) {
          localStorage.removeItem('token')
          localStorage.removeItem('refreshToken')
          localStorage.removeItem('user')
          window.location.href = '/login'
          return Promise.reject(err)
        }

        // Try refresh token
        const refreshToken = localStorage.getItem('refreshToken')
        if (refreshToken) {
          if (isRefreshing) {
            // Queue concurrent requests while refresh is in progress
            return new Promise((resolve, reject) => {
              failedQueue.push({ resolve, reject })
            }).then(token => {
              originalRequest.headers.Authorization = `Bearer ${token}`
              return instance(originalRequest)
            })
          }

          originalRequest._retry = true
          isRefreshing = true

          try {
            const GATEWAY_URL = import.meta.env.VITE_API_GATEWAY_URL
            const baseURL = GATEWAY_URL || window.location.origin
            const res = await axios.post(`${baseURL}/api/auth/refresh`, { refreshToken })
            const { accessToken, refreshToken: newRefreshToken } = res.data
            localStorage.setItem('token', accessToken)
            if (newRefreshToken) localStorage.setItem('refreshToken', newRefreshToken)
            processQueue(null, accessToken)
            originalRequest.headers.Authorization = `Bearer ${accessToken}`
            return instance(originalRequest)
          } catch (refreshErr) {
            processQueue(refreshErr, null)
            localStorage.removeItem('token')
            localStorage.removeItem('refreshToken')
            localStorage.removeItem('user')
            window.location.href = '/login'
            return Promise.reject(refreshErr)
          } finally {
            isRefreshing = false
          }
        }

        // No refresh token — check if we should redirect
        const url = err.config?.url || ''
        const isAuthEndpoint = url.includes('/api/auth/')
        const isTokenExpired = !localStorage.getItem('token')
        if (isAuthEndpoint || isTokenExpired) {
          localStorage.removeItem('token')
          localStorage.removeItem('refreshToken')
          localStorage.removeItem('user')
          window.location.href = '/login'
        }
      }
      return Promise.reject(err)
    }
  )
  return instance
}

/**
 * API Gateway mode (production): all requests go through one gateway URL.
 * Direct mode (development): each service on its own port.
 */
const GATEWAY_URL = import.meta.env.VITE_API_GATEWAY_URL
const DEFAULT_GATEWAY = window.location.origin
export const onboardingApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const onboardingClient = onboardingApi
export const configApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const configClient = configApi
export const analyticsApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const licenseApi = axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY })
export const gatewayApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const dmzApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))

dmzApi.interceptors.request.use((config) => {
  const controlKey = localStorage.getItem('controlKey')
  if (controlKey) config.headers['X-Internal-Key'] = controlKey
  return config
})
export const keystoreApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const screeningApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const storageApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const aiApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const notificationApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const sentinelApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))
export const ediApi = withAuth(axios.create({ baseURL: GATEWAY_URL || DEFAULT_GATEWAY }))

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
