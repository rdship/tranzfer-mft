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
import Sentinel from './pages/Sentinel'

// Partner portal is a separate small app — keep its shell eager.
import PartnerPortalLogin from './pages/PartnerPortalLogin'
import PartnerPortalLayout from './components/PartnerPortalLayout'
import PartnerPortalDashboard from './pages/PartnerPortalDashboard'
import PartnerPortalTransfers from './pages/PartnerPortalTransfers'
import PartnerPortalSettings from './pages/PartnerPortalSettings'

// ── LAZY IMPORTS ──────────────────────────────────────────────────────
// Cold-path pages split into their own chunks. Each chunk is downloaded
// on first navigation and cached for the session.
//
// safeLazy: catches BOTH async import errors AND synchronous module
// initialization errors ("Cannot access 'X' before initialization").
// The sync error happens during Vite's module linking when minified code
// references a shared chunk variable before it's initialized.
function safeLazy(importFn, name) {
  return lazy(async () => {
    try {
      const mod = await importFn()
      // Verify the module actually exported a default component
      if (!mod || !mod.default) {
        throw new Error(`Module ${name} has no default export`)
      }
      return mod
    } catch (err) {
      console.error(`[${name}] Module load/init failed:`, err)
      return { default: () => (
        <div className="p-8 text-center">
          <p className="text-lg font-semibold text-red-500 mb-2">{name} failed to load</p>
          <p className="text-sm opacity-60 mb-4">{err?.message || 'Unknown error'}</p>
          <button onClick={() => window.location.reload()}
            className="px-4 py-2 rounded-lg text-sm" style={{ background: 'rgb(var(--accent))', color: '#fff' }}>
            Reload Page
          </button>
        </div>
      )}
    }
  })
}

// ALL operations pages use safeLazy — these are the pages most likely to hit
// circular dependency issues because they import from shared contexts + APIs
const ActivityMonitor = safeLazy(() => import('./pages/ActivityMonitor'), 'Activity Monitor')
const FabricDashboard = safeLazy(() => import('./pages/FabricDashboard'), 'Fabric Dashboard')
const Journey = safeLazy(() => import('./pages/Journey'), 'Journey')
const Activity = safeLazy(() => import('./pages/Activity'), 'Activity')

const Partners           = safeLazy(() => import('./pages/Partners'), 'Partners')
const PartnerDetail      = safeLazy(() => import('./pages/PartnerDetail'), 'Partner Detail')
const PartnerSetup       = safeLazy(() => import('./pages/PartnerSetup'), 'Partner Setup')
const ServiceManagement  = safeLazy(() => import('./pages/ServiceManagement'), 'Service Management')
const Accounts           = safeLazy(() => import('./pages/Accounts'), 'Accounts')
const Users              = safeLazy(() => import('./pages/Users'), 'Users')
const FolderMappings     = safeLazy(() => import('./pages/FolderMappings'), 'Folder Mappings')
const ServerInstances    = safeLazy(() => import('./pages/ServerInstances'), 'Server Instances')
const FolderTemplates    = safeLazy(() => import('./pages/FolderTemplates'), 'Folder Templates')
const SecurityProfiles   = safeLazy(() => import('./pages/SecurityProfiles'), 'Security Profiles')
const ExternalDestinations = safeLazy(() => import('./pages/ExternalDestinations'), 'External Destinations')
const Partnerships       = safeLazy(() => import('./pages/Partnerships'), 'Partnerships')
const Analytics          = safeLazy(() => import('./pages/Analytics'), 'Analytics')
const Predictions        = safeLazy(() => import('./pages/Predictions'), 'Predictions')
const Logs               = safeLazy(() => import('./pages/Logs'), 'Logs')
const Flows              = safeLazy(() => import('./pages/Flows'), 'Flows')
const GatewayStatus      = safeLazy(() => import('./pages/GatewayStatus'), 'Gateway')
const DmzProxy           = safeLazy(() => import('./pages/DmzProxy'), 'DMZ Proxy')
const ProxyGroups        = safeLazy(() => import('./pages/ProxyGroups'), 'Proxy Groups')
const Keystore           = safeLazy(() => import('./pages/Keystore'), 'Keystore')
const Scheduler          = safeLazy(() => import('./pages/Scheduler'), 'Scheduler')
const FunctionQueues     = safeLazy(() => import('./pages/FunctionQueues'), 'Function Queues')
const Listeners          = safeLazy(() => import('./pages/Listeners'), 'Listeners')
const Sla                = safeLazy(() => import('./pages/Sla'), 'SLA')
const Screening          = safeLazy(() => import('./pages/Screening'), 'Screening')
const Recommendations    = safeLazy(() => import('./pages/Recommendations'), 'Recommendations')
const Storage            = safeLazy(() => import('./pages/Storage'), 'Storage')
const CasDedup           = safeLazy(() => import('./pages/CasDedup'), 'CAS Dedup')
const VfsStorage         = safeLazy(() => import('./pages/VfsStorage'), 'VFS Storage')
const CircuitBreakers    = safeLazy(() => import('./pages/CircuitBreakers'), 'Circuit Breakers')
const Observatory        = safeLazy(() => import('./pages/Observatory'), 'Observatory')
const TwoFactor          = safeLazy(() => import('./pages/TwoFactor'), 'Two Factor')
const ApiConsole         = safeLazy(() => import('./pages/ApiConsole'), 'API Console')
const Edi                = safeLazy(() => import('./pages/Edi'), 'Edi')
const MapBuilder         = safeLazy(() => import('./pages/MapBuilder'), 'MapBuilder')
const Tenants            = safeLazy(() => import('./pages/Tenants'), 'Tenants')
const Blockchain         = safeLazy(() => import('./pages/Blockchain'), 'Blockchain')
const Compliance         = safeLazy(() => import('./pages/Compliance'), 'Compliance')
const Notifications      = safeLazy(() => import('./pages/Notifications'), 'Notifications')
const Connectors         = safeLazy(() => import('./pages/Connectors'), 'Connectors')
const PlatformConfig     = safeLazy(() => import('./pages/PlatformConfig'), 'PlatformConfig')
const PeerTransfers      = safeLazy(() => import('./pages/PeerTransfers'), 'PeerTransfers')
const Terminal           = safeLazy(() => import('./pages/Terminal'), 'Terminal')
const License            = safeLazy(() => import('./pages/License'), 'License')
const DlqManager         = safeLazy(() => import('./pages/DlqManager'), 'DlqManager')
const Quarantine         = safeLazy(() => import('./pages/Quarantine'), 'Quarantine')
const FileManager        = safeLazy(() => import('./pages/FileManager'), 'FileManager')
const ClusterDashboard   = safeLazy(() => import('./pages/ClusterDashboard'), 'Cluster')
const AutoOnboarding     = safeLazy(() => import('./pages/AutoOnboarding'), 'AutoOnboarding')
const Migration          = safeLazy(() => import('./pages/Migration'), 'Migration')
const ThreatIntelligence = safeLazy(() => import('./pages/ThreatIntelligence'), 'ThreatIntelligence')
const EdiTraining        = safeLazy(() => import('./pages/EdiTraining'), 'EdiTraining')
const ProxyIntelligence  = safeLazy(() => import('./pages/ProxyIntelligence'), 'ProxyIntelligence')
const ConfigExport       = safeLazy(() => import('./pages/ConfigExport'), 'ConfigExport')
const Monitoring         = safeLazy(() => import('./pages/Monitoring'), 'Monitoring')
const DatabaseAdvisory   = safeLazy(() => import('./pages/DatabaseAdvisory'), 'DatabaseAdvisory')

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
              <Route path="fabric" element={<LazyRoute><FabricDashboard /></LazyRoute>} />
              <Route path="activity" element={<LazyRoute><ActivityMonitor /></LazyRoute>} />
              <Route path="live" element={<LazyRoute><Activity /></LazyRoute>} />
              <Route path="journey" element={<LazyRoute><Journey /></LazyRoute>} />
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
            <Route path="function-queues"       element={<LazyRoute><FunctionQueues /></LazyRoute>} />
            <Route path="listeners"             element={<LazyRoute><Listeners /></LazyRoute>} />
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
            <Route path="db-advisory"           element={<LazyRoute><DatabaseAdvisory /></LazyRoute>} />
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
