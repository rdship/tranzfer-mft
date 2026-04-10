import { screeningApi } from './client'

export const getQuarantinedFiles = (page = 0, size = 20) =>
  screeningApi.get(`/api/v1/quarantine?page=${page}&size=${size}`).then(r => r.data)

export const releaseFromQuarantine = (id) =>
  screeningApi.post(`/api/v1/quarantine/${id}/release`).then(r => r.data)

export const deleteFromQuarantine = (id) =>
  screeningApi.delete(`/api/v1/quarantine/${id}`).then(r => r.data)

export const getQuarantineDetails = (id) =>
  screeningApi.get(`/api/v1/quarantine/${id}`).then(r => r.data)

export const getQuarantineStats = () =>
  screeningApi.get('/api/v1/quarantine/stats').then(r => r.data)
