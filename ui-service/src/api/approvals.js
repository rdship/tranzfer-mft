import { onboardingApi } from './client'

export const getPendingApprovals = () =>
  onboardingApi.get('/api/flow-executions/pending-approvals').then(r => r.data)

export const approveStep = (trackId, stepIndex, note = '') =>
  onboardingApi.post(`/api/flow-executions/${trackId}/approve`, { stepIndex, note }).then(r => r.data)

export const rejectStep = (trackId, stepIndex, note) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/reject`, { stepIndex, note }).then(r => r.data)

export const getApprovalsForTrack = (trackId) =>
  onboardingApi.get(`/api/flow-executions/${trackId}/approvals`).then(r => r.data)
