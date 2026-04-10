import axios from 'axios'

const GATEWAY_URL = import.meta.env.VITE_API_GATEWAY_URL

const ftpWebApi = axios.create({ baseURL: GATEWAY_URL || 'http://localhost:8083' })
ftpWebApi.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export const listFiles = (path = '/', accountId) =>
  ftpWebApi.get(`/api/files/list?path=${encodeURIComponent(path)}&accountId=${accountId}`).then(r => r.data)

export const uploadFile = (path, accountId, file) => {
  const form = new FormData()
  form.append('file', file)
  form.append('path', path)
  form.append('accountId', accountId)
  return ftpWebApi.post('/api/files/upload', form).then(r => r.data)
}

export const downloadFile = (path, accountId) =>
  ftpWebApi.get(`/api/files/download?path=${encodeURIComponent(path)}&accountId=${accountId}`, { responseType: 'blob' }).then(r => r.data)

export const deleteFile = (path, accountId) =>
  ftpWebApi.delete(`/api/files/delete?path=${encodeURIComponent(path)}&accountId=${accountId}`).then(r => r.data)

export const createDirectory = (path, accountId) =>
  ftpWebApi.post('/api/files/mkdir', { path, accountId }).then(r => r.data)

export const renameFile = (oldPath, newPath, accountId) =>
  ftpWebApi.post('/api/files/rename', { oldPath, newPath, accountId }).then(r => r.data)
