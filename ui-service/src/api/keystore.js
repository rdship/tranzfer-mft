import { keystoreApi } from './client'

const BASE = '/api/v1/keys'

// List & retrieve
export const getKeys = (type, service, partner) => {
  const params = new URLSearchParams()
  if (type) params.append('type', type)
  if (service) params.append('service', service)
  if (partner) params.append('partner', partner)
  const qs = params.toString()
  return keystoreApi.get(`${BASE}${qs ? '?' + qs : ''}`).then(r => r.data)
}
export const getKey = (alias) => keystoreApi.get(`${BASE}/${alias}`).then(r => r.data)
export const getPublicKey = (alias) => keystoreApi.get(`${BASE}/${alias}/public`).then(r => r.data)

// Generate
export const generateSshHost = (data) => keystoreApi.post(`${BASE}/generate/ssh-host`, data).then(r => r.data)
export const generateSshUser = (data) => keystoreApi.post(`${BASE}/generate/ssh-user`, data).then(r => r.data)
export const generateAes = (data) => keystoreApi.post(`${BASE}/generate/aes`, data).then(r => r.data)
export const generateTls = (data) => keystoreApi.post(`${BASE}/generate/tls`, data).then(r => r.data)
export const generateHmac = (data) => keystoreApi.post(`${BASE}/generate/hmac`, data).then(r => r.data)
export const generatePgp = (data) => keystoreApi.post(`${BASE}/generate/pgp`, data).then(r => r.data)

// Import
export const importKey = (data) => keystoreApi.post(`${BASE}/import`, data).then(r => r.data)

// Rotate
export const rotateKey = (alias, newAlias) =>
  keystoreApi.post(`${BASE}/${alias}/rotate`, { newAlias: newAlias || `${alias}-${Date.now()}` }).then(r => r.data)

// Deactivate
export const deactivateKey = (alias) => keystoreApi.delete(`${BASE}/${alias}`).then(r => r.data)

// Download
export const getDownloadUrl = (alias, part = 'public') =>
  `${keystoreApi.defaults.baseURL}${BASE}/${alias}/download?part=${part}`

// Stats & health
export const getKeyStats = () => keystoreApi.get(`${BASE}/stats`).then(r => r.data)
export const getExpiringKeys = (days = 30) => keystoreApi.get(`${BASE}/expiring?days=${days}`).then(r => r.data)
export const getKeyTypes = () => keystoreApi.get(`${BASE}/types`).then(r => r.data)
export const getKeystoreHealth = () => keystoreApi.get(`${BASE}/health`).then(r => r.data)
