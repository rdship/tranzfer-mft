import { Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { BrandingProvider } from './context/BrandingContext'
import ProtectedRoute from './components/ProtectedRoute'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Accounts from './pages/Accounts'
import Users from './pages/Users'
import FolderMappings from './pages/FolderMappings'
import Servers from './pages/Servers'
import SecurityProfiles from './pages/SecurityProfiles'
import ExternalDestinations from './pages/ExternalDestinations'
import Analytics from './pages/Analytics'
import Predictions from './pages/Predictions'
import Monitoring from './pages/Monitoring'
import Logs from './pages/Logs'
import License from './pages/License'
import GatewayStatus from './pages/GatewayStatus'
import Settings from './pages/Settings'
import Flows from './pages/Flows'
import Terminal from './pages/Terminal'
import Journey from './pages/Journey'
import Keystore from './pages/Keystore'
import Scheduler from './pages/Scheduler'
import Sla from './pages/Sla'
import Screening from './pages/Screening'
import Connectors from './pages/Connectors'
import PeerTransfers from './pages/PeerTransfers'

export default function App() {
  return (
    <BrandingProvider>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="accounts" element={<Accounts />} />
            <Route path="users" element={<Users />} />
            <Route path="folder-mappings" element={<FolderMappings />} />
            <Route path="servers" element={<Servers />} />
            <Route path="security-profiles" element={<SecurityProfiles />} />
            <Route path="external-destinations" element={<ExternalDestinations />} />
            <Route path="analytics" element={<Analytics />} />
            <Route path="predictions" element={<Predictions />} />
            <Route path="monitoring" element={<Monitoring />} />
            <Route path="logs" element={<Logs />} />
            <Route path="flows" element={<Flows />} />
            <Route path="gateway" element={<GatewayStatus />} />
            <Route path="journey" element={<Journey />} />
            <Route path="keystore" element={<Keystore />} />
            <Route path="scheduler" element={<Scheduler />} />
            <Route path="sla" element={<Sla />} />
            <Route path="screening" element={<Screening />} />
            <Route path="connectors" element={<Connectors />} />
            <Route path="p2p" element={<PeerTransfers />} />
            <Route path="terminal" element={<Terminal />} />
            <Route path="license" element={<License />} />
            <Route path="settings" element={<Settings />} />
          </Route>
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
    </BrandingProvider>
  )
}
