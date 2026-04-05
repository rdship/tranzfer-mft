import axios from 'axios'
const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080'
const api = axios.create({ baseURL: BASE })
export const partnerLogin = (username, password) =>
  api.post('/api/partner/login', { username, password }).then(r => r.data)
export const getDashboard = (username) =>
  api.get(`/api/partner/dashboard?username=${username}`).then(r => r.data)
export const getTransfers = (username, page = 0, size = 20) =>
  api.get(`/api/partner/transfers?username=${username}&page=${page}&size=${size}`).then(r => r.data)
export const trackTransfer = (trackId, username) =>
  api.get(`/api/partner/track/${trackId}?username=${username}`).then(r => r.data)
export const getReceipt = (trackId, username) =>
  api.get(`/api/partner/receipt/${trackId}?username=${username}`).then(r => r.data)
export const testConnection = (username) =>
  api.get(`/api/partner/test-connection?username=${username}`).then(r => r.data)
export const rotateKey = (username, publicKey) =>
  api.post(`/api/partner/rotate-key?username=${username}`, { publicKey }).then(r => r.data)
export const changePassword = (username, currentPassword, newPassword) =>
  api.post(`/api/partner/change-password?username=${username}`, { currentPassword, newPassword }).then(r => r.data)
export const getSla = (username) =>
  api.get(`/api/partner/sla?username=${username}`).then(r => r.data)
