import { configApi } from './client'
export const getExternalDestinations = () => configApi.get('/api/external-destinations').then(r => r.data)
export const createExternalDestination = (data) => configApi.post('/api/external-destinations', data).then(r => r.data)
export const updateExternalDestination = (id, data) => configApi.put(`/api/external-destinations/${id}`, data).then(r => r.data)
export const deleteExternalDestination = (id) => configApi.delete(`/api/external-destinations/${id}`)
export const getPartnerships = () => configApi.get('/api/as2-partnerships').then(r => r.data)
export const createPartnership = (data) => configApi.post('/api/as2-partnerships', data).then(r => r.data)
export const togglePartnership = (id) => configApi.patch(`/api/as2-partnerships/${id}/toggle`).then(r => r.data)
export const deletePartnership = (id) => configApi.delete(`/api/as2-partnerships/${id}`)

// Function Queues (per-step pipeline configuration)
export const getFunctionQueues = () => configApi.get('/api/function-queues').then(r => r.data)
export const getFunctionQueue = (id) => configApi.get(`/api/function-queues/${id}`).then(r => r.data)
export const createFunctionQueue = (data) => configApi.post('/api/function-queues', data).then(r => r.data)
export const updateFunctionQueue = (id, data) => configApi.put(`/api/function-queues/${id}`, data).then(r => r.data)
export const toggleFunctionQueue = (id) => configApi.patch(`/api/function-queues/${id}/toggle`).then(r => r.data)
export const deleteFunctionQueue = (id) => configApi.delete(`/api/function-queues/${id}`)

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
