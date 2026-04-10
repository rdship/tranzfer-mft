import { configApi } from './client'
export const getExternalDestinations = () => configApi.get('/api/external-destinations').then(r => r.data)
export const createExternalDestination = (data) => configApi.post('/api/external-destinations', data).then(r => r.data)
export const deleteExternalDestination = (id) => configApi.delete(`/api/external-destinations/${id}`)
export const getPartnerships = () => configApi.get('/api/as2-partnerships').then(r => r.data)
export const createPartnership = (data) => configApi.post('/api/as2-partnerships', data).then(r => r.data)
export const togglePartnership = (id) => configApi.patch(`/api/as2-partnerships/${id}/toggle`).then(r => r.data)
export const deletePartnership = (id) => configApi.delete(`/api/as2-partnerships/${id}`)

// Folder Templates
export const getFolderTemplates = () => configApi.get('/api/folder-templates').then(r => r.data)
export const createFolderTemplate = (data) => configApi.post('/api/folder-templates', data).then(r => r.data)
export const updateFolderTemplate = (id, data) => configApi.put(`/api/folder-templates/${id}`, data).then(r => r.data)
export const deleteFolderTemplate = (id) => configApi.delete(`/api/folder-templates/${id}`)
export const exportAllFolderTemplates = () => configApi.get('/api/folder-templates/export').then(r => r.data)
export const importFolderTemplates = (data) => configApi.post('/api/folder-templates/import', data).then(r => r.data)

// VFS Storage Dashboard
export const getVfsDashboard = () => configApi.get('/api/vfs/dashboard').then(r => r.data)
export const getVfsRecentIntents = (limit = 100) => configApi.get(`/api/vfs/intents/recent?limit=${limit}`).then(r => r.data)
export const getVfsAccountUsage = (accountId) => configApi.get(`/api/vfs/accounts/${accountId}/usage`).then(r => r.data)
