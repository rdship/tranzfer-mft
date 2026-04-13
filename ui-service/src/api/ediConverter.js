import { ediApi, aiApi } from './client'

// Map-based conversion (new map system on edi-converter :8095)
export const convertWithMap = (content, sourceType, targetType, partnerId) =>
  ediApi.post('/api/v1/convert/convert/map', { content, sourceType, targetType, partnerId }).then(r => r.data)

// Map listing & detail
export const getAvailableMaps = () => ediApi.get('/api/v1/convert/maps').then(r => r.data)
export const getMapDetail = (mapId) => ediApi.get(`/api/v1/convert/maps/${mapId}`).then(r => r.data)

// Document type detection
export const detectDocumentType = (content) => ediApi.post('/api/v1/convert/detect/type', { content }).then(r => r.data)

// ── Map testing (edi-converter :8095) ─────────────────────────────────────────
export const testMap = (mapId, content) =>
  ediApi.post(`/api/v1/convert/maps/${mapId}/test`, { content }).then(r => r.data)

// ── Partner map management (ai-engine :8091) ──────────────────────────────────
export const cloneMap = (sourceMapId, partnerId, name) =>
  aiApi.post('/api/v1/edi/maps/clone', { sourceMapId, partnerId, name }).then(r => r.data)

export const getPartnerMaps = (partnerId) =>
  aiApi.get(`/api/v1/edi/maps/partner/${partnerId}`).then(r => r.data)

export const updateMap = (mapId, mapDefinition) =>
  aiApi.put(`/api/v1/edi/maps/${mapId}`, mapDefinition).then(r => r.data)

export const activateMap = (mapId) =>
  aiApi.post(`/api/v1/edi/maps/${mapId}/activate`).then(r => r.data)

export const deactivateMap = (mapId) =>
  aiApi.post(`/api/v1/edi/maps/${mapId}/deactivate`).then(r => r.data)

export const deleteMap = (mapId) =>
  aiApi.delete(`/api/v1/edi/maps/${mapId}`)

// ── Conversational Map Builder (ai-engine :8091) ────────────────────────────
export const buildMapFromSamples = (samples, partnerId, name) =>
  aiApi.post('/api/v1/edi/maps/build-from-samples', { samples, partnerId, name }).then(r => r.data)

export const chatWithMap = (mapId, message, context) =>
  aiApi.post('/api/v1/edi/maps/chat', { mapId, message, context }).then(r => r.data)

export const submitMapFeedback = (mapId, approved, comments, corrections) =>
  aiApi.post(`/api/v1/edi/maps/${mapId}/feedback`, { approved, comments, corrections }).then(r => r.data)

// ── Create / Export / Import (ai-engine :8091) ──────────────────────────────
export const createMap = (map) =>
  aiApi.post('/api/v1/edi/maps', map).then(r => r.data)

export const exportMap = (mapId) =>
  aiApi.get(`/api/v1/edi/maps/${mapId}/export`).then(r => r.data)

export const importMap = (importData) =>
  aiApi.post('/api/v1/edi/maps/import', importData).then(r => r.data)

// ── Templates (edi-converter :8095) ─────────────────────────────────────────
export const getTemplates = () =>
  ediApi.get('/api/v1/convert/templates').then(r => r.data)

export const generateFromTemplate = (templateId, values) =>
  ediApi.post(`/api/v1/convert/templates/${templateId}/generate`, values).then(r => r.data)
