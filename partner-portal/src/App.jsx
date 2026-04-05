import { Routes, Route, Navigate } from 'react-router-dom'
import { useState } from 'react'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Transfers from './pages/Transfers'
import Track from './pages/Track'
import Settings from './pages/Settings'
import Layout from './components/Layout'

export default function App() {
  const [user, setUser] = useState(() => {
    try { return JSON.parse(localStorage.getItem('partner-user')) } catch { return null }
  })

  if (!user) return <Login onLogin={u => { localStorage.setItem('partner-user', JSON.stringify(u)); setUser(u) }} />

  return (
    <Routes>
      <Route path="/" element={<Layout user={user} onLogout={() => { localStorage.removeItem('partner-user'); setUser(null) }} />}>
        <Route index element={<Dashboard username={user.username} />} />
        <Route path="transfers" element={<Transfers username={user.username} />} />
        <Route path="track" element={<Track username={user.username} />} />
        <Route path="track/:trackId" element={<Track username={user.username} />} />
        <Route path="settings" element={<Settings username={user.username} />} />
      </Route>
      <Route path="*" element={<Navigate to="/" />} />
    </Routes>
  )
}
