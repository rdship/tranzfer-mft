import axios from 'axios'

const withAuth = (instance) => {
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem('token')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })
  instance.interceptors.response.use(
    (res) => res,
    (err) => {
      if (err.response?.status === 401) {
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        window.location.href = '/login'
      }
      return Promise.reject(err)
    }
  )
  return instance
}

export const onboardingApi = withAuth(axios.create({ baseURL: 'http://localhost:8080' }))
export const configApi = withAuth(axios.create({ baseURL: 'http://localhost:8084' }))
export const analyticsApi = withAuth(axios.create({ baseURL: 'http://localhost:8090' }))
export const licenseApi = axios.create({ baseURL: 'http://localhost:8089' })
export const gatewayApi = withAuth(axios.create({ baseURL: 'http://localhost:8085' }))
export const dmzApi = withAuth(axios.create({ baseURL: 'http://localhost:8088' }))
