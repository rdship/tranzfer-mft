import { onboardingApi } from './client'

export const getClusters = () =>
  onboardingApi.get('/api/clusters').then(r => r.data)

export const getCluster = (id) =>
  onboardingApi.get(`/api/clusters/${id}`).then(r => r.data)

export const updateCluster = (id, data) =>
  onboardingApi.put(`/api/clusters/${id}`, data).then(r => r.data)

export const getCommunicationMode = () =>
  onboardingApi.get('/api/clusters/communication-mode').then(r => r.data)

export const setCommunicationMode = (mode) =>
  onboardingApi.put('/api/clusters/communication-mode', { mode }).then(r => r.data)

export const getTopology = () =>
  onboardingApi.get('/api/clusters/topology').then(r => r.data)

export const getLiveRegistry = () =>
  onboardingApi.get('/api/clusters/live').then(r => r.data)
