import { analyticsApi, onboardingApi } from './client'
export const getDashboard = () => analyticsApi.get('/api/v1/analytics/dashboard').then(r => r.data)
export const getPredictions = () => analyticsApi.get('/api/v1/analytics/predictions').then(r => r.data)
export const getTimeSeries = (service, hours) => analyticsApi.get(`/api/v1/analytics/timeseries`, { params: { service, hours } }).then(r => r.data)
export const getAlertRules = () => analyticsApi.get('/api/v1/analytics/alerts').then(r => r.data)
export const createAlertRule = (data) => analyticsApi.post('/api/v1/analytics/alerts', data).then(r => r.data)
export const deleteAlertRule = (id) => analyticsApi.delete(`/api/v1/analytics/alerts/${id}`)
export const getFlowLiveStats = () => onboardingApi.get('/api/flow-executions/live-stats').then(r => r.data)
