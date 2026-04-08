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
