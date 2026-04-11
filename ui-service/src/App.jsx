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
import ProxyGroups from './pages/ProxyGroups'
import Sentinel from './pages/Sentinel'
import Observatory from './pages/Observatory'
import CircuitBreakers from './pages/CircuitBreakers'
import CasDedup from './pages/CasDedup'
import Compliance from './pages/Compliance'
import Notifications from './pages/Notifications'
import DlqManager from './pages/DlqManager'
import Quarantine from './pages/Quarantine'
import FileManager from './pages/FileManager'
import ClusterDashboard from './pages/ClusterDashboard'
import AutoOnboarding from './pages/AutoOnboarding'
import Migration from './pages/Migration'
import ThreatIntelligence from './pages/ThreatIntelligence'
import EdiTraining from './pages/EdiTraining'
import ProxyIntelligence from './pages/ProxyIntelligence'
import FabricDashboard from './pages/FabricDashboard'
import PartnerPortalLogin from './pages/PartnerPortalLogin'
import PartnerPortalLayout from './components/PartnerPortalLayout'
import PartnerPortalDashboard from './pages/PartnerPortalDashboard'
import PartnerPortalTransfers from './pages/PartnerPortalTransfers'
import PartnerPortalSettings from './pages/PartnerPortalSettings'
import OperationsLayout from './layouts/OperationsLayout'

export default function App() {
  return (
    <ErrorBoundary>
    <BrandingProvider>
      <ServiceProvider>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          {/* Partner Portal — separate login + layout for external partners */}
          <Route path="/portal/login" element={<PartnerPortalLogin />} />
          <Route path="/portal" element={<PartnerPortalLayout />}>
            <Route index element={<PartnerPortalDashboard />} />
            <Route path="transfers" element={<PartnerPortalTransfers />} />
            <Route path="settings" element={<PartnerPortalSettings />} />
          </Route>
          <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
            <Route index element={<Navigate to="/operations" replace />} />

            {/* Operations — Fabric, Activity, Live, Journey as one unified layout */}
            <Route path="operations" element={<OperationsLayout />}>
              <Route index element={<Dashboard />} />
              <Route path="fabric" element={<FabricDashboard />} />
              <Route path="activity" element={<ActivityMonitor />} />
              <Route path="live" element={<Activity />} />
              <Route path="journey" element={<Journey />} />
            </Route>

            {/* Back-compat redirects — old URLs still work, always */}
            <Route path="dashboard"        element={<Navigate to="/operations" replace />} />
            <Route path="fabric"           element={<Navigate to="/operations/fabric" replace />} />
            <Route path="activity-monitor" element={<Navigate to="/operations/activity" replace />} />
            <Route path="activity"         element={<Navigate to="/operations/live" replace />} />
            <Route path="journey"          element={<Navigate to="/operations/journey" replace />} />
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
            <Route path="proxy-groups" element={<ProxyGroups />} />
            <Route path="keystore" element={<Keystore />} />
            <Route path="scheduler" element={<Scheduler />} />
            <Route path="sla" element={<Sla />} />
            <Route path="screening" element={<Screening />} />
            <Route path="recommendations" element={<Recommendations />} />
            <Route path="storage" element={<Storage />} />
            <Route path="cas-dedup" element={<CasDedup />} />
            <Route path="vfs-storage" element={<VfsStorage />} />
            <Route path="sentinel" element={<Sentinel />} />
            <Route path="circuit-breakers" element={<CircuitBreakers />} />
            <Route path="observatory" element={<Observatory />} />
            <Route path="2fa" element={<TwoFactor />} />
            <Route path="api-console" element={<ApiConsole />} />
            <Route path="edi" element={<Edi />} />
            <Route path="edi-mapping" element={<Edi />} />
            <Route path="edi-partners" element={<Edi />} />
            <Route path="tenants" element={<Tenants />} />
            <Route path="blockchain" element={<Blockchain />} />
            <Route path="compliance" element={<Compliance />} />
            <Route path="notifications" element={<Notifications />} />
            <Route path="connectors" element={<Connectors />} />
            <Route path="platform-config" element={<PlatformConfig />} />
            <Route path="p2p" element={<PeerTransfers />} />
            <Route path="terminal" element={<Terminal />} />
            <Route path="license" element={<License />} />
            <Route path="dlq" element={<DlqManager />} />
            <Route path="quarantine" element={<Quarantine />} />
            <Route path="file-manager" element={<FileManager />} />
            <Route path="cluster" element={<ClusterDashboard />} />
            <Route path="auto-onboarding" element={<AutoOnboarding />} />
            <Route path="migration" element={<Migration />} />
            <Route path="threat-intelligence" element={<ThreatIntelligence />} />
            <Route path="edi-training" element={<EdiTraining />} />
            <Route path="proxy-intelligence" element={<ProxyIntelligence />} />
          </Route>
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </AuthProvider>
      </ServiceProvider>
    </BrandingProvider>
    </ErrorBoundary>
  )
}
