import { onboardingApi, storageApi } from './client'

// ── Execution details ───────────────────────────────────────────────────
export const getExecution = (trackId) =>
  onboardingApi.get(`/api/flow-executions/${trackId}`).then(r => r.data)

// ── Flow step snapshots ─────────────────────────────────────────────────
export const getFlowSteps = (trackId) =>
  onboardingApi.get(`/api/flow-steps/${trackId}`).then(r => r.data)

export const getFlowStep = (trackId, stepIndex) =>
  onboardingApi.get(`/api/flow-steps/${trackId}/${stepIndex}`).then(r => r.data)

// ── Event journal (immutable append-only log) ───────────────────────────
export const getFlowEvents = (trackId) =>
  onboardingApi.get(`/api/flow-executions/flow-events/${trackId}`).then(r => r.data)

// ── Execution history (attempt history) ─────────────────────────────────
export const getExecutionHistory = (trackId) =>
  onboardingApi.get(`/api/flow-executions/${trackId}/history`).then(r => r.data)

// ── Journey ─────────────────────────────────────────────────────────────
export const getJourney = (trackId) =>
  onboardingApi.get(`/api/journey/${trackId}`).then(r => r.data)

// ── File download by step (proxies through onboarding → storage-manager, zero-copy) ──
export const getStepFileUrl = (trackId, stepIndex, direction) =>
  `${onboardingApi.defaults.baseURL}/api/flow-steps/${trackId}/${stepIndex}/${direction}/content`

// ── File download by SHA-256 ────────────────────────────────────────────
export const getFileByHashUrl = (sha256) =>
  `${storageApi.defaults.baseURL}/api/v1/storage/stream/${sha256}`

// ── File download by trackId ────────────────────────────────────────────
export const getFileByTrackUrl = (trackId) =>
  `${storageApi.defaults.baseURL}/api/v1/storage/retrieve/${trackId}`

// ── Execution actions ───────────────────────────────────────────────────
export const restartExecution = (trackId) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/restart`).then(r => r.data)

export const restartFromStep = (trackId, step) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/restart/${step}`).then(r => r.data)

export const skipStep = (trackId, step) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/skip/${step}`).then(r => r.data)

export const terminateExecution = (trackId) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/terminate`).then(r => r.data)

// ── R106 Pause / Resume ─────────────────────────────────────────────────
export const pauseExecution = (trackId, reason) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/pause`, reason ? { reason } : {})
    .then(r => r.data)

export const resumeExecution = (trackId) =>
  onboardingApi.post(`/api/flow-executions/${trackId}/resume`).then(r => r.data)
