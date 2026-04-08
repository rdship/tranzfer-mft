import { gatewayApi } from './client'

export const getGatewayStatus = () => gatewayApi.get('/internal/gateway/status').then(r => r.data)
export const getGatewayRoutes = () => gatewayApi.get('/internal/gateway/routes').then(r => r.data)
export const getGatewayStats = () => gatewayApi.get('/internal/gateway/stats').then(r => r.data)
export const getLegacyServers = (protocol) => {
  const params = protocol ? `?protocol=${protocol}` : ''
  return gatewayApi.get(`/internal/gateway/legacy-servers${params}`).then(r => r.data)
}
