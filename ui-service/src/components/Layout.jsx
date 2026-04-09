import { Outlet, useLocation } from 'react-router-dom'
import Sidebar from './Sidebar'
import Header from './Header'

/**
 * Maps route paths to visual themes.
 * Theme CSS variables are defined in index.css per [data-theme].
 *
 *  dark-ops   — Dashboard, Activity, Logs, Journey, Sentinel, Observatory
 *  elevated   — Management, Configuration, File Transfer
 *  compliance — Screening, SLA, Blockchain
 *  infra      — Servers, Storage, Security, Gateway, Scheduler
 *  developer  — API, EDI, Tenants, Terminal, Analytics
 */
const ROUTE_THEMES = {
  '/dashboard':          'dark-ops',
  '/activity':           'dark-ops',
  '/activity-monitor':   'dark-ops',
  '/sentinel':           'dark-ops',
  '/circuit-breakers':   'dark-ops',
  '/observatory':        'dark-ops',
  '/logs':               'dark-ops',
  '/journey':            'dark-ops',
  '/partners':           'elevated',
  '/partner-setup':      'elevated',
  '/services':           'elevated',
  '/accounts':           'elevated',
  '/users':              'elevated',
  '/folder-mappings':    'elevated',
  '/flows':              'elevated',
  '/p2p':                'elevated',
  '/external-destinations': 'elevated',
  '/as2-partnerships':   'elevated',
  '/screening':          'compliance',
  '/sla':                'compliance',
  '/blockchain':         'compliance',
  '/connectors':         'compliance',
  '/server-instances':   'infra',
  '/folder-templates':   'infra',
  '/security-profiles':  'infra',
  '/keystore':           'infra',
  '/2fa':                'infra',
  '/storage':            'infra',
  '/vfs-storage':        'infra',
  '/gateway':            'infra',
  '/dmz-proxy':          'infra',
  '/scheduler':          'infra',
  '/api-console':        'developer',
  '/edi':                'developer',
  '/tenants':            'developer',
  '/platform-config':    'developer',
  '/terminal':           'developer',
  '/license':            'developer',
  '/analytics':          'developer',
  '/predictions':        'developer',
  '/recommendations':    'developer',
}

export default function Layout() {
  const { pathname } = useLocation()

  // Match exact path, then try prefix match for nested routes (e.g. /partners/123)
  const theme =
    ROUTE_THEMES[pathname] ||
    ROUTE_THEMES[Object.keys(ROUTE_THEMES).find(k => pathname.startsWith(k + '/')) ?? ''] ||
    'elevated'

  return (
    <div className="flex h-screen overflow-hidden" data-theme={theme}>
      {/* Sidebar is always dark — uses hardcoded zinc palette, not theme vars */}
      <Sidebar />

      {/* Content area — driven by [data-theme] CSS variable tokens */}
      <div className="flex-1 flex flex-col overflow-hidden page-canvas">
        <Header />
        <main className="flex-1 overflow-y-auto p-6 animate-page">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
