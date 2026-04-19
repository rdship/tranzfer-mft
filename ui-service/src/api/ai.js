import { aiApi as sharedAiApi } from './client'

// R132: removed the standalone `axios.create({ baseURL: 'http://localhost:8091' })`
// instance that previously served nlpCommand / suggestFlow / getAgentsDashboard.
// Calling :8091 directly from the browser bypassed the gateway and was
// blocked by CORS — R130 UI audit caught the Threat Intelligence page
// firing 11× CORS errors. Every ai-engine endpoint now routes through
// sharedAiApi, which uses the authenticated, gateway-relative client
// (same path /api/v1/...; nginx routes /api/v1/ai/** + /api/v1/threats/**
// to ai-engine:8091 internally).

export const nlpCommand = (query, context) =>
  sharedAiApi.post('/api/v1/ai/nlp/command', { query, context }).then(r => r.data)

export const suggestFlow = (description) =>
  sharedAiApi.post('/api/v1/ai/nlp/suggest-flow', { description }).then(r => r.data)

export const getAgentsDashboard = () =>
  sharedAiApi.get('/api/v1/threats/dashboard').then(r => r.data)

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
