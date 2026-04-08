import axios from 'axios'

const GATEWAY = import.meta.env.VITE_API_GATEWAY_URL
const aiApi = axios.create({ baseURL: GATEWAY || 'http://localhost:8091' })

export const classifyFile = (file) => {
  const form = new FormData()
  form.append('file', file)
  return aiApi.post('/api/v1/ai/classify', form).then(r => r.data)
}

export const classifyText = (content, filename) =>
  aiApi.post('/api/v1/ai/classify/text', { content, filename }).then(r => r.data)

export const getAnomalies = () =>
  aiApi.get('/api/v1/ai/anomalies').then(r => r.data)

export const nlpCommand = (query, context) =>
  aiApi.post('/api/v1/ai/nlp/command', { query, context }).then(r => r.data)

export const suggestFlow = (description) =>
  aiApi.post('/api/v1/ai/nlp/suggest-flow', { description }).then(r => r.data)

export const explainEvent = (event, context) =>
  aiApi.post('/api/v1/ai/nlp/explain', { event, ...context }).then(r => r.data)

export const getRiskScore = (factors) =>
  aiApi.post('/api/v1/ai/risk-score', factors).then(r => r.data)

export const aiHealth = () =>
  aiApi.get('/api/v1/ai/health').then(r => r.data)
