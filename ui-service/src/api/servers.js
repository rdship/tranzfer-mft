import { configClient, onboardingApi } from './client'

// ── Account ↔ server assignments ─────────────────────────────────────────────
export const getServerAccounts    = (serverId) =>
  onboardingApi.get(`/api/servers/${serverId}/accounts`).then(r => r.data)

export const updateServerAssignment = (serverId, accountId, config) =>
  onboardingApi.put(`/api/servers/${serverId}/accounts/${accountId}`, config).then(r => r.data)

export const revokeServerAccess = (serverId, accountId) =>
  onboardingApi.delete(`/api/servers/${serverId}/accounts/${accountId}`).then(r => r.data)

// ── Server maintenance mode ───────────────────────────────────────────────────
export const toggleMaintenance = (serverId, enable, message = '') =>
  onboardingApi.post(`/api/servers/${serverId}/maintenance`, { enable, message }).then(r => r.data)

// ── Legacy endpoints ─────────────────────────────────────────────────────────
export const listLegacyServers  = () => configClient.get('/api/legacy-servers').then(r => r.data)
export const addLegacyServer    = (data) => configClient.post('/api/legacy-servers', data).then(r => r.data)
export const updateLegacyServer = (id, data) => configClient.put(`/api/legacy-servers/${id}`, data).then(r => r.data)
export const deleteLegacyServer = (id) => configClient.delete(`/api/legacy-servers/${id}`)
