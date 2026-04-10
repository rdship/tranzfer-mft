import { onboardingApi } from './client'

export const getOnboardingSessions = (status) => {
  const params = status ? `?status=${status}` : ''
  return onboardingApi.get(`/api/v1/auto-onboard/sessions${params}`).then(r => r.data)
}

export const getOnboardingSession = (id) =>
  onboardingApi.get(`/api/v1/auto-onboard/sessions/${id}`).then(r => r.data)

export const approveSession = (id) =>
  onboardingApi.post(`/api/v1/auto-onboard/sessions/${id}/approve`).then(r => r.data)

export const getOnboardingStats = () =>
  onboardingApi.get('/api/v1/auto-onboard/stats').then(r => r.data)
