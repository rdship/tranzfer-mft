import { aiApi } from './client'

// Dashboard
export const getThreatDashboard = () => aiApi.get('/api/v1/threats/dashboard').then(r => r.data)

// Indicators (IOCs)
export const getIndicators = (params) => aiApi.get('/api/v1/threats/indicators', { params }).then(r => r.data)
export const createIndicator = (data) => aiApi.post('/api/v1/threats/indicators', data).then(r => r.data)
export const deleteIndicator = (id) => aiApi.delete(`/api/v1/threats/indicators/${id}`)

// Threat hunting
export const huntThreats = (query) => aiApi.post('/api/v1/threats/hunt', query).then(r => r.data)

// Analysis
export const analyzeNetwork = (data) => aiApi.post('/api/v1/threats/analyze/network', data).then(r => r.data)
export const analyzeAnomaly = (data) => aiApi.post('/api/v1/threats/analyze/anomaly', data).then(r => r.data)

// MITRE ATT&CK
export const getMitreMapping = () => aiApi.get('/api/v1/threats/mitre/mapping').then(r => r.data)
export const getMitreCoverage = () => aiApi.get('/api/v1/threats/mitre/coverage').then(r => r.data)

// Attack chains
export const getAttackChains = () => aiApi.get('/api/v1/threats/chains').then(r => r.data)

// Threat actors
export const getThreatActors = () => aiApi.get('/api/v1/threats/agents').then(r => r.data)

// Geo resolution
export const resolveGeo = (ip) => aiApi.get(`/api/v1/threats/geo/resolve/${ip}`).then(r => r.data)

// Incidents
export const getIncidents = () => aiApi.get('/api/v1/threats/incidents').then(r => r.data)

// Security graph
export const getSecurityGraph = () => aiApi.get('/api/v1/threats/graph').then(r => r.data)
