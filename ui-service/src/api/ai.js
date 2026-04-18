import axios from 'axios'
import { aiApi as sharedAiApi } from './client'

// Standalone unauthenticated instance kept for non-admin NLP suggestions
// (legacy — pre-R107 callers). New Activity Copilot endpoints use the
// shared auth-wrapped aiApi so JWT flows through to ai-engine's
// @PreAuthorize(VIEWER) controllers.
const GATEWAY = import.meta.env.VITE_API_GATEWAY_URL
const aiApi = axios.create({ baseURL: GATEWAY || 'http://localhost:8091' })

export const nlpCommand = (query, context) =>
  aiApi.post('/api/v1/ai/nlp/command', { query, context }).then(r => r.data)

export const suggestFlow = (description) =>
  aiApi.post('/api/v1/ai/nlp/suggest-flow', { description }).then(r => r.data)

export const getAgentsDashboard = () =>
  aiApi.get('/api/v1/threats/dashboard').then(r => r.data)

// ── R107 Activity Copilot ─────────────────────────────────────────────────
// These use the auth-wrapped client so JWT + refresh-token flow apply.

export const analyzeActivity = (trackId) =>
  sharedAiApi.get(`/api/v1/ai/activity/analyze/${trackId}`).then(r => r.data)

export const diagnoseActivity = (trackId) =>
  sharedAiApi.get(`/api/v1/ai/activity/diagnose/${trackId}`).then(r => r.data)

export const suggestActivityActions = (trackId) =>
  sharedAiApi.get(`/api/v1/ai/activity/suggest/${trackId}`).then(r => r.data)

export const chatActivity = (trackId, message) =>
  sharedAiApi.post('/api/v1/ai/activity/chat', { trackId, message }).then(r => r.data)
