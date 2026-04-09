import { analyticsApi } from './client'

export const getObservatoryData = () =>
  analyticsApi.get('/api/v1/analytics/observatory').then(r => r.data)

export const getStepLatency = (hours = 24) =>
  analyticsApi.get('/api/v1/analytics/observatory/step-latency', { params: { hours } }).then(r => r.data)
