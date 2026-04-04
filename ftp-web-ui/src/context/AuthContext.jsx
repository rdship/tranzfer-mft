import { createContext, useContext, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import toast from 'react-hot-toast'

const AuthContext = createContext(null)
export function AuthProvider({ children }) {
  const navigate = useNavigate()
  const [user, setUser] = useState(() => { try { return JSON.parse(localStorage.getItem('ftpweb-user') || 'null') } catch { return null } })
  const [token, setToken] = useState(() => localStorage.getItem('ftpweb-token'))

  const login = async (email, password) => {
    const res = await axios.post('/api/auth/login', { email, password })
    const { token: jwt, user: u } = res.data
    localStorage.setItem('ftpweb-token', jwt)
    localStorage.setItem('ftpweb-user', JSON.stringify(u))
    setToken(jwt); setUser(u)
    navigate('/')
  }

  const logout = () => {
    localStorage.removeItem('ftpweb-token')
    localStorage.removeItem('ftpweb-user')
    setToken(null); setUser(null)
    navigate('/login')
    toast.success('Logged out')
  }

  return <AuthContext.Provider value={{ user, token, login, logout }}>{children}</AuthContext.Provider>
}
export const useAuth = () => useContext(AuthContext)
