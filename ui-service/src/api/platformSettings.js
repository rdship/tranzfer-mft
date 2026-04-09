import { configApi, onboardingApi } from './client'

// ── Platform Settings CRUD ──────────────────────────────────────────────────

export const getPlatformSettings = (params) =>
  configApi.get('/api/platform-settings', { params }).then(r => r.data)

export const getPlatformSetting = (id) =>
  configApi.get(`/api/platform-settings/${id}`).then(r => r.data)

export const createPlatformSetting = (data) =>
  configApi.post('/api/platform-settings', data).then(r => r.data)

export const updatePlatformSetting = (id, data) =>
  configApi.put(`/api/platform-settings/${id}`, data).then(r => r.data)

export const updatePlatformSettingValue = (id, value) =>
  configApi.patch(`/api/platform-settings/${id}/value`, { value }).then(r => r.data)

export const deletePlatformSetting = (id) =>
  configApi.delete(`/api/platform-settings/${id}`)

// ── Metadata ────────────────────────────────────────────────────────────────

export const getEnvironments = () =>
  configApi.get('/api/platform-settings/environments').then(r => r.data)

export const getServiceNames = () =>
  configApi.get('/api/platform-settings/services').then(r => r.data)

export const getCategories = () =>
  configApi.get('/api/platform-settings/categories').then(r => r.data)

// ── Clone ───────────────────────────────────────────────────────────────────

export const cloneEnvironment = (source, target) =>
  configApi.post(`/api/platform-settings/clone?source=${source}&target=${target}`).then(r => r.data)

// ── Snapshot Retention ──────────────────────────────────────────────────────

export const getSnapshotRetention = () =>
  onboardingApi.get('/api/snapshot-retention').then(r => r.data)

export const updateSnapshotRetention = (retentionDays) =>
  onboardingApi.put('/api/snapshot-retention', { retentionDays }).then(r => r.data)

export const purgeSnapshotsNow = () =>
  onboardingApi.post('/api/snapshot-retention/purge-now').then(r => r.data)
