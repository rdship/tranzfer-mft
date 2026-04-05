import { onboardingApi } from './client'

// The forwarder service (port 8087) is typically accessed via the onboarding API proxy
// or directly in dev mode. Using onboarding as a fallback since forwarder may not have
// its own withAuth instance — in production, all traffic goes through the gateway.

const FORWARDER_URL = import.meta.env.VITE_API_GATEWAY_URL || 'http://localhost:8087'
import axios from 'axios'

const forwarderApi = axios.create({ baseURL: FORWARDER_URL })

export const getActiveTransfers = () => forwarderApi.get('/api/forward/transfers/active').then(r => r.data)
export const getForwarderHealth = () => forwarderApi.get('/api/forward/health').then(r => r.data)
