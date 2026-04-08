import { Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { BrandingProvider } from './context/BrandingContext'
import { ServiceProvider } from './context/ServiceContext'
import ErrorBoundary from './components/ErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Accounts from './pages/Accounts'
import Users from './pages/Users'
import FolderMappings from './pages/FolderMappings'
import SecurityProfiles from './pages/SecurityProfiles'
import ExternalDestinations from './pages/ExternalDestinations'
import Analytics from './pages/Analytics'
import Predictions from './pages/Predictions'
import Logs from './pages/Logs'
import License from './pages/License'
import GatewayStatus from './pages/GatewayStatus'
import Flows from './pages/Flows'
import Terminal from './pages/Terminal'
import Journey from './pages/Journey'
import Keystore from './pages/Keystore'
import Scheduler from './pages/Scheduler'
import Sla from './pages/Sla'
import Screening from './pages/Screening'
import Connectors from './pages/Connectors'
import PeerTransfers from './pages/PeerTransfers'
import Recommendations from './pages/Recommendations'
import Storage from './pages/Storage'
import Activity from './pages/Activity'
import TwoFactor from './pages/TwoFactor'
import ApiConsole from './pages/ApiConsole'
import Edi from './pages/Edi'
import Tenants from './pages/Tenants'
import Blockchain from './pages/Blockchain'
import ServerInstances from './pages/ServerInstances'
import FolderTemplates from './pages/FolderTemplates'
import PlatformConfig from './pages/PlatformConfig'
import Partnerships from './pages/Partnerships'
import Partners from './pages/Partners'
import PartnerDetail from './pages/PartnerDetail'
import PartnerSetup from './pages/PartnerSetup'
import ServiceManagement from './pages/ServiceManagement'
import VfsStorage from './pages/VfsStorage'
import DmzProxy from './pages/DmzProxy'
import ActivityMonitor from './pages/ActivityMonitor'

export default function App() {
  return (
    <ErrorBoundary>
    <BrandingProvider>
      <ServiceProvider>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="partners" element={<Partners />} />
            <Route path="partners/:id" element={<PartnerDetail />} />
            <Route path="partner-setup" element={<PartnerSetup />} />
            <Route path="services" element={<ServiceManagement />} />
            <Route path="accounts" element={<Accounts />} />
            <Route path="users" element={<Users />} />
            <Route path="folder-mappings" element={<FolderMappings />} />
            <Route path="server-instances" element={<ServerInstances />} />
            <Route path="folder-templates" element={<FolderTemplates />} />
            <Route path="security-profiles" element={<SecurityProfiles />} />
            <Route path="external-destinations" element={<ExternalDestinations />} />
            <Route path="as2-partnerships" element={<Partnerships />} />
            <Route path="analytics" element={<Analytics />} />
            <Route path="predictions" element={<Predictions />} />
            <Route path="logs" element={<Logs />} />
            <Route path="flows" element={<Flows />} />
            <Route path="gateway" element={<GatewayStatus />} />
            <Route path="dmz-proxy" element={<DmzProxy />} />
            <Route path="journey" element={<Journey />} />
            <Route path="keystore" element={<Keystore />} />
            <Route path="scheduler" element={<Scheduler />} />
            <Route path="sla" element={<Sla />} />
            <Route path="screening" element={<Screening />} />
            <Route path="recommendations" element={<Recommendations />} />
            <Route path="storage" element={<Storage />} />
            <Route path="vfs-storage" element={<VfsStorage />} />
            <Route path="activity" element={<Activity />} />
            <Route path="activity-monitor" element={<ActivityMonitor />} />
            <Route path="2fa" element={<TwoFactor />} />
            <Route path="api-console" element={<ApiConsole />} />
            <Route path="edi" element={<Edi />} />
            <Route path="tenants" element={<Tenants />} />
            <Route path="blockchain" element={<Blockchain />} />
            <Route path="connectors" element={<Connectors />} />
            <Route path="platform-config" element={<PlatformConfig />} />
            <Route path="p2p" element={<PeerTransfers />} />
            <Route path="terminal" element={<Terminal />} />
            <Route path="license" element={<License />} />
          </Route>
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
      </ServiceProvider>
    </BrandingProvider>
    </ErrorBoundary>
  )
}
