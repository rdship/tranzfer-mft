import { onboardingApi } from './client'

export const getFabricTimeline = (trackId) =>
  onboardingApi.get(`/api/fabric/track/${trackId}/timeline`).then(r => r.data)

export const getFabricQueues = () =>
  onboardingApi.get('/api/fabric/queues').then(r => r.data)

export const getFabricInstances = () =>
  onboardingApi.get('/api/fabric/instances').then(r => r.data)

export const getFabricStuck = () =>
  onboardingApi.get('/api/fabric/stuck').then(r => r.data)

export const getFabricLatency = () =>
  onboardingApi.get('/api/fabric/latency').then(r => r.data)
