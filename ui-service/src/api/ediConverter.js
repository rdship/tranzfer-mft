import { ediApi } from './client'

// Map-based conversion (new map system on edi-converter :8095)
export const convertWithMap = (content, sourceType, targetType, partnerId) =>
  ediApi.post('/api/v1/convert/convert/map', { content, sourceType, targetType, partnerId }).then(r => r.data)

// Map listing & detail
export const getAvailableMaps = () => ediApi.get('/api/v1/convert/maps').then(r => r.data)
export const getMapDetail = (mapId) => ediApi.get(`/api/v1/convert/maps/${mapId}`).then(r => r.data)

// Document type detection
export const detectDocumentType = (content) => ediApi.post('/api/v1/convert/detect/type', { content }).then(r => r.data)
