import { analyticsApi } from './client'

export const getObservatoryData = () =>
  analyticsApi.get('/api/v1/analytics/observatory').then(r => r.data)
