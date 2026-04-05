import { onboardingApi } from './client'

// Partner CRUD
export const getPartners = (status, type) => {
  const params = new URLSearchParams()
  if (status) params.append('status', status)
  if (type) params.append('type', type)
  const qs = params.toString()
  return onboardingApi.get(`/api/partners${qs ? '?' + qs : ''}`).then(r => r.data)
}
export const getPartner = (id) => onboardingApi.get(`/api/partners/${id}`).then(r => r.data)
export const createPartner = (data) => onboardingApi.post('/api/partners', data).then(r => r.data)
export const updatePartner = (id, data) => onboardingApi.put(`/api/partners/${id}`, data).then(r => r.data)
export const deletePartner = (id) => onboardingApi.delete(`/api/partners/${id}`)

// Partner lifecycle
export const activatePartner = (id) => onboardingApi.post(`/api/partners/${id}/activate`).then(r => r.data)
export const suspendPartner = (id) => onboardingApi.post(`/api/partners/${id}/suspend`).then(r => r.data)

// Partner stats
export const getPartnerStats = () => onboardingApi.get('/api/partners/stats').then(r => r.data)

// Partner sub-resources
export const getPartnerAccounts = (id) => onboardingApi.get(`/api/partners/${id}/accounts`).then(r => r.data)
export const createPartnerAccount = (id, data) => onboardingApi.post(`/api/partners/${id}/accounts`, data).then(r => r.data)
export const getPartnerFlows = (id) => onboardingApi.get(`/api/partners/${id}/flows`).then(r => r.data)
export const getPartnerEndpoints = (id) => onboardingApi.get(`/api/partners/${id}/endpoints`).then(r => r.data)
