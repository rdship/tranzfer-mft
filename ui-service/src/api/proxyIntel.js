import { aiApi } from './client'

// Dashboard
export const getProxyDashboard = () => aiApi.get('/api/v1/proxy/dashboard').then(r => r.data)

// Verdicts
export const getVerdicts = (params) => aiApi.get('/api/v1/proxy/verdicts', { params }).then(r => r.data)
export const getVerdict = (ip) => aiApi.post('/api/v1/proxy/verdict', { ip }).then(r => r.data)

// Block/Allow lists
export const getBlocklist = () => aiApi.get('/api/v1/proxy/blocklist').then(r => r.data)
export const addToBlocklist = (entry) => aiApi.post('/api/v1/proxy/blocklist', entry).then(r => r.data)
export const removeFromBlocklist = (ip) => aiApi.delete(`/api/v1/proxy/blocklist/${ip}`)
export const getAllowlist = () => aiApi.get('/api/v1/proxy/allowlist').then(r => r.data)
export const addToAllowlist = (entry) => aiApi.post('/api/v1/proxy/allowlist', entry).then(r => r.data)
export const removeFromAllowlist = (ip) => aiApi.delete(`/api/v1/proxy/allowlist/${ip}`)

// IP Intelligence
export const getIpIntel = (ip) => aiApi.get(`/api/v1/proxy/ip/${ip}`).then(r => r.data)

// Events
export const getProxyEvents = () => aiApi.get('/api/v1/proxy/events').then(r => r.data)

// Geo
export const getGeoStats = () => aiApi.get('/api/v1/proxy/geo/stats').then(r => r.data)

// Overhead estimates
export const getOverheadEstimates = () => aiApi.get('/api/v1/proxy/overhead-estimates').then(r => r.data)
