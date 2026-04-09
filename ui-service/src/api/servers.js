import { configClient, onboardingClient, onboardingApi } from './client'

// ── Server instance CRUD ──────────────────────────────────────────────────────
export const listServers = ()          => onboardingApi.get('/api/servers').then(r => r.data)
export const getServer   = (id)        => onboardingApi.get(`/api/servers/${id}`).then(r => r.data)
export const createServer = (data)     => onboardingApi.post('/api/servers', data).then(r => r.data)
export const updateServer = (id, data) => onboardingApi.patch(`/api/servers/${id}`, data).then(r => r.data)
export const deleteServer = (id)       => onboardingApi.delete(`/api/servers/${id}`)

// Legacy helpers (kept for backward compat)
export const addServer    = (data)     => createServer(data)
export const toggleServer = (id, active) => updateServer(id, { active })

// ── Account ↔ server assignments (360-degree API) ────────────────────────────

// By server: who can connect to a given server
export const getServerAccounts    = (serverId) =>
  onboardingApi.get(`/api/servers/${serverId}/accounts`).then(r => r.data)

export const assignAccountToServer = (serverId, accountId, config = {}) =>
  onboardingApi.post(`/api/servers/${serverId}/accounts/${accountId}`, config).then(r => r.data)

export const updateServerAssignment = (serverId, accountId, config) =>
  onboardingApi.put(`/api/servers/${serverId}/accounts/${accountId}`, config).then(r => r.data)

export const revokeServerAccess = (serverId, accountId) =>
  onboardingApi.delete(`/api/servers/${serverId}/accounts/${accountId}`).then(r => r.data)

export const bulkAssignAccounts = (serverId, accountIds) =>
  onboardingApi.post(`/api/servers/${serverId}/accounts/bulk`, { accountIds }).then(r => r.data)

export const checkServerAccess = (serverId, username) =>
  onboardingApi.get(`/api/servers/${serverId}/access-check/${username}`).then(r => r.data)

// By account: which servers can an account connect to
export const getAccountServers      = (accountId) =>
  onboardingApi.get(`/api/accounts/${accountId}/servers`).then(r => r.data)

export const addServerToAccount     = (accountId, serverId, config = {}) =>
  onboardingApi.post(`/api/accounts/${accountId}/servers/${serverId}`, config).then(r => r.data)

export const removeServerFromAccount = (accountId, serverId) =>
  onboardingApi.delete(`/api/accounts/${accountId}/servers/${serverId}`).then(r => r.data)

// ── Server maintenance mode ───────────────────────────────────────────────────
export const toggleMaintenance = (serverId, enable, message = '') =>
  onboardingApi.post(`/api/servers/${serverId}/maintenance`, { enable, message }).then(r => r.data)

// ── Legacy endpoints (kept) ───────────────────────────────────────────────────
export const listLegacyServers  = () => configClient.get('/api/legacy-servers').then(r => r.data)
export const addLegacyServer    = (data) => configClient.post('/api/legacy-servers', data).then(r => r.data)
export const addDestination     = (data) => configClient.post('/api/external-destinations', data).then(r => r.data)
export const listDestinations   = ()     => configClient.get('/api/external-destinations').then(r => r.data)
export const serviceRegistry    = ()     => onboardingClient.get('/api/service-registry').then(r => r.data)
