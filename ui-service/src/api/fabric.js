import { onboardingApi } from './client'

export const getFabricTimeline = (trackId) =>
  onboardingApi.get(`/api/fabric/track/${trackId}/timeline`).then(r => r.data)

export const getFabricQueues = () =>
  onboardingApi.get('/api/fabric/queues').then(r => r.data)

export const getFabricInstances = () =>
  onboardingApi.get('/api/fabric/instances').then(r => r.data)

export const getFabricStuck = ({ page = 0, size = 50 } = {}) =>
  onboardingApi.get('/api/fabric/stuck', { params: { page, size } }).then(r => r.data)

export const getFabricLatency = ({ hours = 1, sample = 10000 } = {}) =>
  onboardingApi.get('/api/fabric/latency', { params: { hours, sample } }).then(r => r.data)
