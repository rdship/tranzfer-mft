import axios from 'axios'

const sentinelApi = axios.create({ baseURL: 'http://localhost:8098' })

sentinelApi.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export const getHealthScore = () => sentinelApi.get('/api/v1/sentinel/health-score').then(r => r.data)
export const getHealthScoreHistory = (hours = 24) => sentinelApi.get('/api/v1/sentinel/health-score/history', { params: { hours } }).then(r => r.data)
export const getFindings = (params) => sentinelApi.get('/api/v1/sentinel/findings', { params }).then(r => r.data)
export const getFinding = (id) => sentinelApi.get(`/api/v1/sentinel/findings/${id}`).then(r => r.data)
export const dismissFinding = (id) => sentinelApi.post(`/api/v1/sentinel/findings/${id}/dismiss`).then(r => r.data)
export const acknowledgeFinding = (id) => sentinelApi.post(`/api/v1/sentinel/findings/${id}/acknowledge`).then(r => r.data)
export const getCorrelations = () => sentinelApi.get('/api/v1/sentinel/correlations').then(r => r.data)
export const getRules = () => sentinelApi.get('/api/v1/sentinel/rules').then(r => r.data)
export const updateRule = (id, data) => sentinelApi.put(`/api/v1/sentinel/rules/${id}`, data).then(r => r.data)
export const getDashboard = () => sentinelApi.get('/api/v1/sentinel/dashboard').then(r => r.data)
export const triggerAnalysis = () => sentinelApi.post('/api/v1/sentinel/analyze').then(r => r.data)
export const getSentinelHealth = () => sentinelApi.get('/api/v1/sentinel/health').then(r => r.data)
