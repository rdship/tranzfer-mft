import { onboardingApi } from './client'

// Partner portal auth (separate from admin login)
export const partnerLogin = (username, password) =>
  onboardingApi.post('/api/partner/login', { username, password }).then(r => r.data)

// Dashboard
export const getPartnerDashboard = () =>
  onboardingApi.get('/api/partner/dashboard').then(r => r.data)

// Transfers
export const getPartnerTransfers = (page = 0, size = 20) =>
  onboardingApi.get(`/api/partner/transfers?page=${page}&size=${size}`).then(r => r.data)

// Track single transfer
export const trackTransfer = (trackId) =>
  onboardingApi.get(`/api/partner/track/${trackId}`).then(r => r.data)

// Download receipt
export const downloadReceipt = (trackId) =>
  onboardingApi.get(`/api/partner/receipt/${trackId}`, { responseType: 'blob' }).then(r => r.data)

// Test connection
export const testConnection = () =>
  onboardingApi.get('/api/partner/test-connection').then(r => r.data)

// Rotate SSH key
export const rotateKey = (publicKey) =>
  onboardingApi.post('/api/partner/rotate-key', { publicKey }).then(r => r.data)

// Change password
export const changePassword = (currentPassword, newPassword) =>
  onboardingApi.post('/api/partner/change-password', { currentPassword, newPassword }).then(r => r.data)

// SLA status
export const getPartnerSla = () =>
  onboardingApi.get('/api/partner/sla').then(r => r.data)
