import { onboardingApi } from './client'

export const getDlqMessages = (page = 0, size = 20) =>
  onboardingApi.get(`/api/dlq/messages?page=${page}&size=${size}`).then(r => r.data)

export const retryDlqMessage = (id) =>
  onboardingApi.post(`/api/dlq/messages/${id}/retry`).then(r => r.data)

export const discardDlqMessage = (id) =>
  onboardingApi.delete(`/api/dlq/messages/${id}`).then(r => r.data)

export const retryAllDlq = () =>
  onboardingApi.post('/api/dlq/retry-all').then(r => r.data)
