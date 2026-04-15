import { createContext, useContext, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { onboardingApi } from '../api/client'
import toast from 'react-hot-toast'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const navigate = useNavigate()
  const [user, setUser] = useState(() => {
    try { return JSON.parse(localStorage.getItem('user') || 'null') } catch { return null }
  })
  const [token, setToken] = useState(() => localStorage.getItem('token'))

  const login = useCallback(async (email, password) => {
    const res = await onboardingApi.post('/api/auth/login', { email, password })
    const { accessToken, refreshToken } = res.data
    // Decode JWT payload to extract user info (sub=email, role)
    const payload = JSON.parse(atob(accessToken.split('.')[1]))
    const u = { email: payload.sub, role: payload.role }
    localStorage.setItem('token', accessToken)
    if (refreshToken) localStorage.setItem('refreshToken', refreshToken)
    localStorage.setItem('user', JSON.stringify(u))
    setToken(accessToken)
    setUser(u)
    navigate('/dashboard')
  }, [navigate])

  const logout = useCallback(async () => {
    try {
      await onboardingApi.post('/api/auth/logout')
    } catch { /* ignore — server might be down */ }
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('user')
    setToken(null)
    setUser(null)
    navigate('/login')
    toast.success('Logged out')
  }, [navigate])

  return (
    <AuthContext.Provider value={{ user, token, login, logout, isAdmin: user?.role === 'ADMIN' }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
