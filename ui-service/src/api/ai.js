import axios from 'axios'

const GATEWAY = import.meta.env.VITE_API_GATEWAY_URL
const aiApi = axios.create({ baseURL: GATEWAY || 'http://localhost:8091' })

export const nlpCommand = (query, context) =>
  aiApi.post('/api/v1/ai/nlp/command', { query, context }).then(r => r.data)

export const suggestFlow = (description) =>
  aiApi.post('/api/v1/ai/nlp/suggest-flow', { description }).then(r => r.data)

export const getAgentsDashboard = () =>
  aiApi.get('/api/v1/threats/dashboard').then(r => r.data)
