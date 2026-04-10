import { aiApi } from './client'

// Training sessions
export const getTrainingSessions = () => aiApi.get('/api/v1/edi/training/sessions').then(r => r.data)
export const getTrainingSession = (id) => aiApi.get(`/api/v1/edi/training/sessions/${id}`).then(r => r.data)

// Samples
export const getSamples = (params) => aiApi.get('/api/v1/edi/training/samples', { params }).then(r => r.data)
export const addSample = (data) => aiApi.post('/api/v1/edi/training/samples', data).then(r => r.data)
export const deleteSample = (id) => aiApi.delete(`/api/v1/edi/training/samples/${id}`)

// Training
export const trainModel = (config) => aiApi.post('/api/v1/edi/training/train', config).then(r => r.data)
export const quickTrain = (data) => aiApi.post('/api/v1/edi/training/quick-train', data).then(r => r.data)

// Maps
export const getMaps = () => aiApi.get('/api/v1/edi/training/maps').then(r => r.data)
export const getMap = (id) => aiApi.get(`/api/v1/edi/training/maps/${id}`).then(r => r.data)

// Corrections
export const getCorrectionSessions = () => aiApi.get('/api/v1/edi/correction/sessions').then(r => r.data)
export const submitCorrection = (sessionId, data) => aiApi.post(`/api/v1/edi/correction/${sessionId}/correct`, data).then(r => r.data)
export const approveCorrection = (sessionId) => aiApi.post(`/api/v1/edi/correction/${sessionId}/approve`).then(r => r.data)

// Health
export const getTrainingHealth = () => aiApi.get('/api/v1/edi/training/health').then(r => r.data)
