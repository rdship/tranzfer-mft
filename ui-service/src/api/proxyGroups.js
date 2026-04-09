import { onboardingApi } from './client'

// ── Proxy Group definitions (DB-backed) ──────────────────────────────────────
export const getProxyGroups    = () => onboardingApi.get('/api/proxy-groups').then(r => r.data)
export const getProxyGroup     = (id) => onboardingApi.get(`/api/proxy-groups/${id}`).then(r => r.data)
export const createProxyGroup  = (data) => onboardingApi.post('/api/proxy-groups', data).then(r => r.data)
export const updateProxyGroup  = (id, data) => onboardingApi.put(`/api/proxy-groups/${id}`, data).then(r => r.data)
export const deleteProxyGroup  = (id) => onboardingApi.delete(`/api/proxy-groups/${id}`).then(r => r.data)

// ── Live instance discovery (Redis-backed) ───────────────────────────────────
export const discoverProxyInstances = () => onboardingApi.get('/api/proxy-groups/discover').then(r => r.data)

// ── Filter by type ───────────────────────────────────────────────────────────
export const getProxyGroupsByType = (type) => onboardingApi.get(`/api/proxy-groups/by-type/${type}`).then(r => r.data)
