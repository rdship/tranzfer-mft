import { dmzApi } from './client'

// Port mappings
export const listMappings = () => dmzApi.get('/api/proxy/mappings').then(r => r.data)
export const addMapping = (data) => dmzApi.post('/api/proxy/mappings', data).then(r => r.data)
export const removeMapping = (name) => dmzApi.delete(`/api/proxy/mappings/${encodeURIComponent(name)}`).then(r => r.data)

// Health & status
export const getDmzHealth = () => dmzApi.get('/api/proxy/health').then(r => r.data)

// QoS stats
export const getQosStats = () => dmzApi.get('/api/proxy/qos/stats').then(r => r.data)
export const getQosStatsByMapping = (name) => dmzApi.get(`/api/proxy/qos/stats/${encodeURIComponent(name)}`).then(r => r.data)

// Security intelligence
export const getSecurityStats = () => dmzApi.get('/api/proxy/security/stats').then(r => r.data)
export const getConnectionStats = () => dmzApi.get('/api/proxy/security/connections').then(r => r.data)
export const getIpIntelligence = (ip) => dmzApi.get(`/api/proxy/security/ip/${ip}`).then(r => r.data)
export const getRateLimits = () => dmzApi.get('/api/proxy/security/rate-limits').then(r => r.data)
export const getSecuritySummary = () => dmzApi.get('/api/proxy/security/summary').then(r => r.data)

// Backend health
export const getBackendHealth = () => dmzApi.get('/api/proxy/backends/health').then(r => r.data)

// Audit
export const getAuditStats = () => dmzApi.get('/api/proxy/audit/stats').then(r => r.data)
export const flushAudit = () => dmzApi.post('/api/proxy/audit/flush').then(r => r.data)

// Zone enforcement
export const getZoneRules = () => dmzApi.get('/api/proxy/zones/rules').then(r => r.data)
export const checkZone = (sourceIp, targetHost, targetPort) =>
  dmzApi.get('/api/proxy/zones/check', { params: { sourceIp, targetHost, targetPort } }).then(r => r.data)

// Egress filter
export const getEgressStats = () => dmzApi.get('/api/proxy/egress/stats').then(r => r.data)
export const checkEgress = (host, port) =>
  dmzApi.get('/api/proxy/egress/check', { params: { host, port } }).then(r => r.data)

// Listeners
export const getListeners = () => dmzApi.get('/api/proxy/listeners').then(r => r.data)

// IP blacklist/whitelist management
export const addBlacklistIp = (name, ip) =>
  dmzApi.put(`/api/proxy/mappings/${encodeURIComponent(name)}/security/blacklist`, { ip }).then(r => r.data)
export const removeBlacklistIp = (name, ip) =>
  dmzApi.delete(`/api/proxy/mappings/${encodeURIComponent(name)}/security/blacklist/${encodeURIComponent(ip)}`).then(r => r.data)
export const addWhitelistIp = (name, ip) =>
  dmzApi.put(`/api/proxy/mappings/${encodeURIComponent(name)}/security/whitelist`, { ip }).then(r => r.data)
export const removeWhitelistIp = (name, ip) =>
  dmzApi.delete(`/api/proxy/mappings/${encodeURIComponent(name)}/security/whitelist/${encodeURIComponent(ip)}`).then(r => r.data)

// Reachability test
export const testReachability = (host, port) =>
  dmzApi.post('/api/proxy/reachability', { host, port }).then(r => r.data)

// Security policy update
export const updateSecurityPolicy = (name, policy) =>
  dmzApi.put(`/api/proxy/mappings/${encodeURIComponent(name)}/security-policy`, policy).then(r => r.data)

// Prometheus metrics (raw text)
export const getMetrics = () => dmzApi.get('/api/proxy/metrics').then(r => r.data)
