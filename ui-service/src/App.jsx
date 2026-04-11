import { Suspense, lazy } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { BrandingProvider } from './context/BrandingContext'
import { ServiceProvider } from './context/ServiceContext'
import { ThemeProvider } from './context/ThemeContext'
import ErrorBoundary from './components/ErrorBoundary'
import ChunkLoadErrorBoundary from './components/ChunkLoadErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import Layout from './components/Layout'
import Skeleton, { useDelayedFlag } from './components/Skeleton'
import OperationsLayout from './layouts/OperationsLayout'

// ── EAGER IMPORTS ─────────────────────────────────────────────────────
// Hot-path pages stay in the main bundle so the most-used screens open
// instantly after login. Rule of thumb: a page that's visited every day
// by every operator goes here; everything else is lazy.
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import ActivityMonitor from './pages/ActivityMonitor'
import FabricDashboard from './pages/FabricDashboard'
import Journey from './pages/Journey'
import Activity from './pages/Activity'
import Sentinel from './pages/Sentinel'

// Partner portal is a separate small app — keep its shell eager.
import PartnerPortalLogin from './pages/PartnerPortalLogin'
import PartnerPortalLayout from './components/PartnerPortalLayout'
import PartnerPortalDashboard from './pages/PartnerPortalDashboard'
import PartnerPortalTransfers from './pages/PartnerPortalTransfers'
import PartnerPortalSettings from './pages/PartnerPortalSettings'

// ── LAZY IMPORTS ──────────────────────────────────────────────────────
// Cold-path pages split into their own chunks. Each chunk is downloaded
// on first navigation and cached for the session. A ChunkLoadErrorBoundary
// catches the stale-HTML-after-deploy case and offers the user a refresh.
const Partners           = lazy(() => import('./pages/Partners'))
const PartnerDetail      = lazy(() => import('./pages/PartnerDetail'))
const PartnerSetup       = lazy(() => import('./pages/PartnerSetup'))
const ServiceManagement  = lazy(() => import('./pages/ServiceManagement'))
const Accounts           = lazy(() => import('./pages/Accounts'))
const Users              = lazy(() => import('./pages/Users'))
const FolderMappings     = lazy(() => import('./pages/FolderMappings'))
const ServerInstances    = lazy(() => import('./pages/ServerInstances'))
const FolderTemplates    = lazy(() => import('./pages/FolderTemplates'))
const SecurityProfiles   = lazy(() => import('./pages/SecurityProfiles'))
const ExternalDestinations = lazy(() => import('./pages/ExternalDestinations'))
const Partnerships       = lazy(() => import('./pages/Partnerships'))
const Analytics          = lazy(() => import('./pages/Analytics'))
const Predictions        = lazy(() => import('./pages/Predictions'))
const Logs               = lazy(() => import('./pages/Logs'))
const Flows              = lazy(() => import('./pages/Flows'))
const GatewayStatus      = lazy(() => import('./pages/GatewayStatus'))
const DmzProxy           = lazy(() => import('./pages/DmzProxy'))
const ProxyGroups        = lazy(() => import('./pages/ProxyGroups'))
const Keystore           = lazy(() => import('./pages/Keystore'))
const Scheduler          = lazy(() => import('./pages/Scheduler'))
const Sla                = lazy(() => import('./pages/Sla'))
const Screening          = lazy(() => import('./pages/Screening'))
const Recommendations    = lazy(() => import('./pages/Recommendations'))
const Storage            = lazy(() => import('./pages/Storage'))
const CasDedup           = lazy(() => import('./pages/CasDedup'))
const VfsStorage         = lazy(() => import('./pages/VfsStorage'))
const CircuitBreakers    = lazy(() => import('./pages/CircuitBreakers'))
const Observatory        = lazy(() => import('./pages/Observatory'))
const TwoFactor          = lazy(() => import('./pages/TwoFactor'))
const ApiConsole         = lazy(() => import('./pages/ApiConsole'))
const Edi                = lazy(() => import('./pages/Edi'))
const MapBuilder         = lazy(() => import('./pages/MapBuilder'))
const Tenants            = lazy(() => import('./pages/Tenants'))
const Blockchain         = lazy(() => import('./pages/Blockchain'))
const Compliance         = lazy(() => import('./pages/Compliance'))
const Notifications      = lazy(() => import('./pages/Notifications'))
const Connectors         = lazy(() => import('./pages/Connectors'))
const PlatformConfig     = lazy(() => import('./pages/PlatformConfig'))
const PeerTransfers      = lazy(() => import('./pages/PeerTransfers'))
const Terminal           = lazy(() => import('./pages/Terminal'))
const License            = lazy(() => import('./pages/License'))
const DlqManager         = lazy(() => import('./pages/DlqManager'))
const Quarantine         = lazy(() => import('./pages/Quarantine'))
const FileManager        = lazy(() => import('./pages/FileManager'))
const ClusterDashboard   = lazy(() => import('./pages/ClusterDashboard'))
const AutoOnboarding     = lazy(() => import('./pages/AutoOnboarding'))
const Migration          = lazy(() => import('./pages/Migration'))
const ThreatIntelligence = lazy(() => import('./pages/ThreatIntelligence'))
const EdiTraining        = lazy(() => import('./pages/EdiTraining'))
const ProxyIntelligence  = lazy(() => import('./pages/ProxyIntelligence'))
const ConfigExport       = lazy(() => import('./pages/ConfigExport'))
const Monitoring         = lazy(() => import('./pages/Monitoring'))

/**
 * RouteFallback — shown while a lazy route chunk is being fetched.
 *
 * Uses useDelayedFlag(100) so sub-100ms fetches (cache hits) don't flash a
 * skeleton. Only genuinely slow fetches show the loading row.
 */
function RouteFallback() {
  const show = useDelayedFlag(true, 100)
  if (!show) return null
  return (
    <div className="p-6">
      <Skeleton.Table rows={8} cols={[32, 180, 120, 100, 120, 80]} rowHeight={44} />
    </div>
  )
}

/**
 * LazyRoute — wraps any lazy-loaded element in:
 *   1. A ChunkLoadErrorBoundary (catches stale-HTML chunk 404s after a deploy)
 *   2. A per-page ErrorBoundary (catches render/runtime crashes inside the
 *      page so one broken tab doesn't wipe out the sidebar and leave the
 *      admin stranded)
 *   3. Suspense with a skeleton fallback
 *
 * The per-page boundary is the important one for the "never silently crash"
 * goal — before R15, any render error in any page bubbled all the way to
 * the root ErrorBoundary which replaced the entire <Layout> with a generic
 * error card. Now the sidebar + header stay intact and only the page body
 * shows the recovery card.
 */
function PageCrashCard({ error }) {
  return (
    <div className="p-6">
      <div
        className="max-w-xl mx-auto rounded-xl p-6"
        style={{
          background: 'rgb(127, 29, 29, 0.12)',
          border: '1px solid rgba(239, 68, 68, 0.35)',
        }}
      >
        <h2 className="text-lg font-bold mb-2" style={{ color: '#f87171' }}>
          This page crashed
        </h2>
        <p className="text-sm mb-3" style={{ color: 'rgb(148, 163, 184)' }}>
          The rest of the admin UI is still running — use the sidebar to
          navigate elsewhere, or retry this page.
        </p>
        <pre
          className="text-[11px] font-mono p-2 rounded mb-3 overflow-x-auto"
          style={{ background: 'rgb(var(--canvas))', color: 'rgb(148, 163, 184)' }}
        >
          {error?.message || 'Unknown error'}
        </pre>
        <button
          onClick={() => window.location.reload()}
          className="px-3 py-1.5 text-xs font-semibold rounded-lg"
          style={{ background: '#dc2626', color: 'white' }}
        >
          Reload
        </button>
      </div>
    </div>
  )
}

function LazyRoute({ children }) {
  return (
    <ChunkLoadErrorBoundary>
      <ErrorBoundary fallback={(err) => <PageCrashCard error={err} />}>
        <Suspense fallback={<RouteFallback />}>
          {children}
        </Suspense>
      </ErrorBoundary>
    </ChunkLoadErrorBoundary>
  )
}

/**
 * EagerRoute — same per-page error isolation for eager (non-lazy) routes.
 * Operations pages stay eager for speed but still get a local boundary so
 * a crash on Dashboard doesn't take out Fabric + Activity + Journey.
 */
function EagerRoute({ children }) {
  return (
    <ErrorBoundary fallback={(err) => <PageCrashCard error={err} />}>
      {children}
    </ErrorBoundary>
  )
}

export default function App() {
  return (
    <ErrorBoundary>
    <ThemeProvider>
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

            {/* Operations — the hot path. These stay eager (no Suspense)
                but get per-page ErrorBoundary isolation via EagerRoute so
                a crash in one tab doesn't take out the rest. */}
            <Route path="operations" element={<OperationsLayout />}>
              <Route index element={<EagerRoute><Dashboard /></EagerRoute>} />
              <Route path="fabric" element={<EagerRoute><FabricDashboard /></EagerRoute>} />
              <Route path="activity" element={<EagerRoute><ActivityMonitor /></EagerRoute>} />
              <Route path="live" element={<EagerRoute><Activity /></EagerRoute>} />
              <Route path="journey" element={<EagerRoute><Journey /></EagerRoute>} />
            </Route>

            {/* Back-compat redirects — old URLs still work, always */}
            <Route path="dashboard"        element={<Navigate to="/operations" replace />} />
            <Route path="fabric"           element={<Navigate to="/operations/fabric" replace />} />
            <Route path="activity-monitor" element={<Navigate to="/operations/activity" replace />} />
            <Route path="activity"         element={<Navigate to="/operations/live" replace />} />
            <Route path="journey"          element={<Navigate to="/operations/journey" replace />} />

            {/* Sentinel stays eager — it's part of the hot path for operators */}
            <Route path="sentinel" element={<EagerRoute><Sentinel /></EagerRoute>} />

            {/* Everything else is lazy. Each Route wraps its element in
                <LazyRoute> so every route gets Suspense + chunk error handling. */}
            <Route path="partners"              element={<LazyRoute><Partners /></LazyRoute>} />
            <Route path="partners/:id"          element={<LazyRoute><PartnerDetail /></LazyRoute>} />
            <Route path="partner-setup"         element={<LazyRoute><PartnerSetup /></LazyRoute>} />
            <Route path="services"              element={<LazyRoute><ServiceManagement /></LazyRoute>} />
            <Route path="accounts"              element={<LazyRoute><Accounts /></LazyRoute>} />
            <Route path="users"                 element={<LazyRoute><Users /></LazyRoute>} />
            <Route path="folder-mappings"       element={<LazyRoute><FolderMappings /></LazyRoute>} />
            <Route path="server-instances"      element={<LazyRoute><ServerInstances /></LazyRoute>} />
            <Route path="folder-templates"      element={<LazyRoute><FolderTemplates /></LazyRoute>} />
            <Route path="security-profiles"     element={<LazyRoute><SecurityProfiles /></LazyRoute>} />
            <Route path="external-destinations" element={<LazyRoute><ExternalDestinations /></LazyRoute>} />
            <Route path="as2-partnerships"      element={<LazyRoute><Partnerships /></LazyRoute>} />
            <Route path="analytics"             element={<LazyRoute><Analytics /></LazyRoute>} />
            <Route path="predictions"           element={<LazyRoute><Predictions /></LazyRoute>} />
            <Route path="logs"                  element={<LazyRoute><Logs /></LazyRoute>} />
            <Route path="flows"                 element={<LazyRoute><Flows /></LazyRoute>} />
            <Route path="gateway"               element={<LazyRoute><GatewayStatus /></LazyRoute>} />
            <Route path="dmz-proxy"             element={<LazyRoute><DmzProxy /></LazyRoute>} />
            <Route path="proxy-groups"          element={<LazyRoute><ProxyGroups /></LazyRoute>} />
            <Route path="keystore"              element={<LazyRoute><Keystore /></LazyRoute>} />
            <Route path="scheduler"             element={<LazyRoute><Scheduler /></LazyRoute>} />
            <Route path="sla"                   element={<LazyRoute><Sla /></LazyRoute>} />
            <Route path="screening"             element={<LazyRoute><Screening /></LazyRoute>} />
            <Route path="recommendations"       element={<LazyRoute><Recommendations /></LazyRoute>} />
            <Route path="storage"               element={<LazyRoute><Storage /></LazyRoute>} />
            <Route path="cas-dedup"             element={<LazyRoute><CasDedup /></LazyRoute>} />
            <Route path="vfs-storage"           element={<LazyRoute><VfsStorage /></LazyRoute>} />
            <Route path="circuit-breakers"      element={<LazyRoute><CircuitBreakers /></LazyRoute>} />
            <Route path="observatory"           element={<LazyRoute><Observatory /></LazyRoute>} />
            <Route path="2fa"                   element={<LazyRoute><TwoFactor /></LazyRoute>} />
            <Route path="api-console"           element={<LazyRoute><ApiConsole /></LazyRoute>} />
            <Route path="edi"                   element={<LazyRoute><Edi /></LazyRoute>} />
            <Route path="edi-mapping"           element={<LazyRoute><MapBuilder /></LazyRoute>} />
            <Route path="edi-partners"          element={<LazyRoute><Edi /></LazyRoute>} />
            <Route path="tenants"               element={<LazyRoute><Tenants /></LazyRoute>} />
            <Route path="blockchain"            element={<LazyRoute><Blockchain /></LazyRoute>} />
            <Route path="compliance"            element={<LazyRoute><Compliance /></LazyRoute>} />
            <Route path="notifications"         element={<LazyRoute><Notifications /></LazyRoute>} />
            <Route path="connectors"            element={<LazyRoute><Connectors /></LazyRoute>} />
            <Route path="platform-config"       element={<LazyRoute><PlatformConfig /></LazyRoute>} />
            <Route path="p2p"                   element={<LazyRoute><PeerTransfers /></LazyRoute>} />
            <Route path="terminal"              element={<LazyRoute><Terminal /></LazyRoute>} />
            <Route path="license"               element={<LazyRoute><License /></LazyRoute>} />
            <Route path="dlq"                   element={<LazyRoute><DlqManager /></LazyRoute>} />
            <Route path="quarantine"            element={<LazyRoute><Quarantine /></LazyRoute>} />
            <Route path="file-manager"          element={<LazyRoute><FileManager /></LazyRoute>} />
            <Route path="cluster"               element={<LazyRoute><ClusterDashboard /></LazyRoute>} />
            <Route path="auto-onboarding"       element={<LazyRoute><AutoOnboarding /></LazyRoute>} />
            <Route path="migration"             element={<LazyRoute><Migration /></LazyRoute>} />
            <Route path="threat-intelligence"   element={<LazyRoute><ThreatIntelligence /></LazyRoute>} />
            <Route path="edi-training"          element={<LazyRoute><EdiTraining /></LazyRoute>} />
            <Route path="proxy-intelligence"    element={<LazyRoute><ProxyIntelligence /></LazyRoute>} />
            <Route path="config-export"         element={<LazyRoute><ConfigExport /></LazyRoute>} />
            <Route path="monitoring"            element={<LazyRoute><Monitoring /></LazyRoute>} />
          </Route>
          <Route path="*" element={<Navigate to="/operations" replace />} />
        </Routes>
      </AuthProvider>
      </ServiceProvider>
    </BrandingProvider>
    </ThemeProvider>
    </ErrorBoundary>
  )
}
