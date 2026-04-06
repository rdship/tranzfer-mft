import { configApi } from './client'

export const getSecurityPolicies = () =>
  configApi.get('/api/listener-security-policies').then(r => r.data)

export const getSecurityPolicyForServer = (serverId) =>
  configApi.get(`/api/listener-security-policies/server/${serverId}`).then(r => r.data).catch(() => null)

export const getSecurityPolicyForDestination = (destId) =>
  configApi.get(`/api/listener-security-policies/destination/${destId}`).then(r => r.data).catch(() => null)

export const createSecurityPolicy = (data) =>
  configApi.post('/api/listener-security-policies', data).then(r => r.data)

export const updateSecurityPolicy = (id, data) =>
  configApi.put(`/api/listener-security-policies/${id}`, data).then(r => r.data)

export const deleteSecurityPolicy = (id) =>
  configApi.delete(`/api/listener-security-policies/${id}`)
