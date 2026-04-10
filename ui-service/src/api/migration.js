import { configApi } from './client'

export const getMigrationDashboard = () =>
  configApi.get('/api/v1/migration/dashboard').then(r => r.data)

export const getMigrationPartners = (status) => {
  const params = status ? `?status=${status}` : ''
  return configApi.get(`/api/v1/migration/partners${params}`).then(r => r.data)
}

export const getMigrationPartnerDetail = (id) =>
  configApi.get(`/api/v1/migration/partners/${id}`).then(r => r.data)

export const startMigration = (id, source, notes) =>
  configApi.post(`/api/v1/migration/partners/${id}/start`, { source, notes }).then(r => r.data)

export const enableShadowMode = (id, legacyHost, legacyPort, legacyUsername) =>
  configApi.post(`/api/v1/migration/partners/${id}/shadow`, { legacyHost, legacyPort, legacyUsername }).then(r => r.data)

export const disableShadowMode = (id) =>
  configApi.delete(`/api/v1/migration/partners/${id}/shadow`).then(r => r.data)

export const startVerification = (id) =>
  configApi.post(`/api/v1/migration/partners/${id}/verify`).then(r => r.data)

export const recordVerification = (id, transferCount, passed, details) =>
  configApi.post(`/api/v1/migration/partners/${id}/verify/record`, { transferCount, passed, details }).then(r => r.data)

export const completeMigration = (id) =>
  configApi.post(`/api/v1/migration/partners/${id}/complete`).then(r => r.data)

export const rollbackMigration = (id, reason) =>
  configApi.post(`/api/v1/migration/partners/${id}/rollback`, { reason }).then(r => r.data)

export const getPartnerEvents = (id) =>
  configApi.get(`/api/v1/migration/partners/${id}/events`).then(r => r.data)

export const getPartnerConnections = (id, limit = 100) =>
  configApi.get(`/api/v1/migration/partners/${id}/connections?limit=${limit}`).then(r => r.data)

export const getConnectionStats = () =>
  configApi.get('/api/v1/migration/connection-stats').then(r => r.data)
