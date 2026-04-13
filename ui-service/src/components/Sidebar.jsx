import { NavLink, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useBranding } from '../context/BrandingContext'
import { useServices } from '../context/ServiceContext'
import { useAuth } from '../context/AuthContext'
import { useTheme } from '../context/ThemeContext'
import { useRecentlyViewed } from '../hooks/useRecentlyViewed'
import { configApi, onboardingApi, screeningApi, sentinelApi } from '../api/client'
import { getFabricStuck } from '../api/fabric'
import { SunIcon, MoonIcon } from '@heroicons/react/24/solid'
import {
  HomeIcon,
  BuildingOfficeIcon,
  RocketLaunchIcon,
  CircleStackIcon,
  MagnifyingGlassIcon,
  WifiIcon,
  ChartBarIcon,
  UsersIcon,
  ArrowsRightLeftIcon,
  CpuChipIcon,
  ArrowPathIcon,
  GlobeAltIcon,
  LinkIcon,
  ServerStackIcon,
  FolderIcon,
  ShieldCheckIcon,
  KeyIcon,
  BoltIcon,
  ClockIcon, QueueListIcon, SignalIcon,
  DocumentTextIcon,
  FingerPrintIcon,
  DocumentCheckIcon,
  CommandLineIcon,
  CodeBracketIcon,
  EyeIcon,
  LightBulbIcon,
  BeakerIcon,
  AdjustmentsHorizontalIcon,
  ArrowRightOnRectangleIcon,
  BellAlertIcon,
  InboxStackIcon,
  ExclamationTriangleIcon,
  FolderOpenIcon,
  ShareIcon,
  SparklesIcon,
} from '@heroicons/react/24/outline'

const navGroups = [
  {
    label: 'Operations',
    items: [
      { to: '/operations',           icon: HomeIcon,            label: 'Dashboard' },
      { to: '/operations/fabric',    icon: BoltIcon,            label: 'Flow Fabric',      badge: 'fabricStuck' },
      { to: '/operations/activity',  icon: ChartBarIcon,        label: 'Activity Monitor' },
      { to: '/operations/live',      icon: WifiIcon,            label: 'Live Activity' },
      { to: '/operations/journey',   icon: MagnifyingGlassIcon, label: 'Transfer Journey' },
    ],
  },
  {
    label: 'Partners & Accounts',
    items: [
      { to: '/partners',          icon: BuildingOfficeIcon,   label: 'Partner Management' },
      { to: '/partner-setup',     icon: RocketLaunchIcon,     label: 'Onboard Partner',    role: 'ADMIN' },
      { to: '/accounts',          icon: UsersIcon,            label: 'Transfer Accounts' },
      { to: '/users',             icon: UsersIcon,            label: 'Users',              role: 'ADMIN' },
    ],
  },
  {
    label: 'File Processing',
    items: [
      { to: '/flows',                 icon: CpuChipIcon,         label: 'Processing Flows' },
      { to: '/folder-mappings',       icon: ArrowsRightLeftIcon, label: 'Folder Mappings' },
      { to: '/folder-templates',      icon: FolderIcon,          label: 'Folder Templates',  role: 'ADMIN' },
      { to: '/external-destinations', icon: GlobeAltIcon,        label: 'External Destinations' },
      { to: '/as2-partnerships',      icon: LinkIcon,            label: 'AS2/AS4 Partnerships' },
      { to: '/p2p',                   icon: ArrowPathIcon,       label: 'P2P Transfers' },
      { to: '/file-manager',           icon: FolderOpenIcon,      label: 'File Manager',     role: 'ADMIN' },
    ],
  },
  {
    // Infrastructure — the boxes everything runs on. Observatory lives here
    // because it's a service-topology view, not an intelligence product.
    label: 'Servers & Infrastructure',
    items: [
      { to: '/server-instances',  icon: ServerStackIcon,    label: 'Server Instances',  role: 'ADMIN' },
      { to: '/gateway',           icon: BoltIcon,           label: 'Gateway & DMZ',     role: 'ADMIN' },
      { to: '/dmz-proxy',         icon: ShieldCheckIcon,    label: 'DMZ Proxy',         role: 'ADMIN' },
      { to: '/proxy-groups',      icon: ArrowsRightLeftIcon,label: 'Proxy Groups',      role: 'ADMIN' },
      { to: '/storage',           icon: ServerStackIcon,    label: 'Storage Manager',   role: 'ADMIN' },
      { to: '/vfs-storage',       icon: CircleStackIcon,    label: 'VFS Storage',       role: 'ADMIN' },
      { to: '/cas-dedup',         icon: CircleStackIcon,    label: 'CAS Dedup',         role: 'ADMIN' },
      { to: '/cluster',           icon: ShareIcon,          label: 'Cluster',           role: 'ADMIN' },
      { to: '/observatory',       icon: EyeIcon,            label: 'Observatory',       role: 'ADMIN' },
    ],
  },
  {
    // Security — includes threat/proxy intelligence + sentinel (all security signals)
    label: 'Security & Compliance',
    items: [
      { to: '/compliance',          icon: ShieldCheckIcon,         label: 'Compliance Profiles', role: 'ADMIN', badge: 'compliance' },
      { to: '/security-profiles',   icon: ShieldCheckIcon,         label: 'Security Profiles',   role: 'ADMIN' },
      { to: '/keystore',            icon: KeyIcon,                 label: 'Keystore Manager',    role: 'ADMIN' },
      { to: '/screening',           icon: MagnifyingGlassIcon,     label: 'Screening & DLP' },
      { to: '/quarantine',          icon: ExclamationTriangleIcon, label: 'Quarantine',          role: 'ADMIN', badge: 'quarantine' },
      { to: '/sentinel',            icon: CpuChipIcon,             label: 'Platform Sentinel',   role: 'ADMIN', badge: 'sentinel' },
      { to: '/threat-intelligence', icon: ShieldCheckIcon,         label: 'Threat Intel',        role: 'ADMIN' },
      { to: '/proxy-intelligence',  icon: GlobeAltIcon,            label: 'Proxy Intel',         role: 'ADMIN' },
      { to: '/2fa',                 icon: ShieldCheckIcon,         label: 'Two-Factor Auth' },
      { to: '/blockchain',          icon: FingerPrintIcon,         label: 'Blockchain Proof' },
    ],
  },
  {
    label: 'Notifications & Integrations',
    items: [
      { to: '/notifications',     icon: BellAlertIcon,     label: 'Notifications',     role: 'ADMIN' },
      { to: '/connectors',        icon: BoltIcon,          label: 'Connectors',        role: 'ADMIN' },
      { to: '/sla',               icon: DocumentCheckIcon, label: 'SLA Agreements' },
      { to: '/scheduler',         icon: ClockIcon,         label: 'Scheduler',         role: 'ADMIN' },
      { to: '/function-queues',   icon: QueueListIcon,     label: 'Function Queues',   role: 'ADMIN' },
      { to: '/listeners',          icon: SignalIcon,         label: 'Service Listeners', role: 'ADMIN' },
    ],
  },
  {
    // Data + AI — everything that learns from / reports on platform state.
    // Dissolved the old "Intelligence" junk drawer; only AI/analytics left here.
    label: 'AI & Analytics',
    items: [
      { to: '/analytics',       icon: ChartBarIcon,  label: 'Analytics' },
      { to: '/predictions',     icon: BeakerIcon,    label: 'Predictions' },
      { to: '/recommendations', icon: LightBulbIcon, label: 'AI Recommendations' },
      { to: '/auto-onboarding', icon: SparklesIcon,  label: 'Auto-Onboarding',   role: 'ADMIN' },
      { to: '/edi-training',    icon: SparklesIcon,  label: 'EDI AI Training',   role: 'ADMIN' },
    ],
  },
  {
    // Monitoring — embedded Prometheus + Grafana + Alertmanager so the
    // admin never has to leave the UI to check platform health, run
    // PromQL queries, or silence alerts. Added in R19.
    label: 'Monitoring',
    items: [
      { to: '/monitoring',   icon: ChartBarIcon, label: 'Dashboards & Metrics', role: 'ADMIN' },
    ],
  },
  {
    label: 'EDI',
    items: [
      { to: '/edi',           icon: DocumentTextIcon,    label: 'EDI Convert' },
      { to: '/edi-partners',  icon: BuildingOfficeIcon,  label: 'EDI Partners' },
    ],
  },
  {
    label: 'Tools',
    items: [
      { to: '/api-console', icon: CodeBracketIcon,  label: 'API Console' },
      { to: '/terminal',    icon: CommandLineIcon,  label: 'Terminal',        role: 'ADMIN' },
    ],
  },
  {
    // Administration — platform-level ops, not product features.
    // Migration + Circuit Breakers live here because they're ops affordances.
    label: 'Administration',
    items: [
      { to: '/platform-config', icon: AdjustmentsHorizontalIcon, label: 'Platform Config',    role: 'ADMIN' },
      { to: '/tenants',         icon: ServerStackIcon,           label: 'Multi-Tenant',       role: 'ADMIN' },
      { to: '/license',         icon: KeyIcon,                   label: 'License',            role: 'ADMIN' },
      { to: '/services',        icon: CircleStackIcon,           label: 'Service Health',     role: 'ADMIN' },
      { to: '/circuit-breakers',icon: ArrowPathIcon,             label: 'Circuit Breakers',   role: 'ADMIN' },
      { to: '/migration',       icon: ArrowPathIcon,             label: 'Migration Center',   role: 'ADMIN' },
      { to: '/config-export',   icon: DocumentTextIcon,          label: 'Configuration Export', role: 'ADMIN' },
      { to: '/db-advisory',     icon: CircleStackIcon,           label: 'Database Advisory',  role: 'ADMIN' },
      { to: '/logs',            icon: DocumentTextIcon,          label: 'Logs' },
      { to: '/dlq',             icon: InboxStackIcon,            label: 'Dead Letter Queue',  role: 'ADMIN', badge: 'dlq' },
    ],
  },
]

export default function Sidebar() {
  const { branding }               = useBranding()
  const { isPageVisible, loading } = useServices()
  const { user, logout }           = useAuth()
  const { theme, toggle: toggleTheme } = useTheme()
  const { entries: recentEntries } = useRecentlyViewed()
  const userRole                   = user?.role || 'USER'
  const initials                   = (user?.email?.[0] || 'A').toUpperCase()

  // ── Live notification counts — drive the colored badges on nav items ──
  // All fetches are best-effort; a backend outage just means the badge disappears
  // for that item. No error UI, no retries (Sidebar must never block rendering).
  const { data: complianceCount } = useQuery({
    queryKey: ['sidebar-compliance-count'],
    queryFn: () => configApi.get('/api/compliance/violations/count').then(r => r.data?.unresolved || 0).catch(() => 0),
    refetchInterval: 30000,
    retry: 0,
  })

  const { data: sentinelOpenCount } = useQuery({
    queryKey: ['sidebar-sentinel-count'],
    queryFn: () =>
      sentinelApi.get('/api/v1/sentinel/findings', { params: { status: 'OPEN', size: 1 } })
        .then(r => r.data?.totalElements ?? r.data?.total ?? (Array.isArray(r.data) ? r.data.length : 0))
        .catch(() => 0),
    refetchInterval: 60000,
    retry: 0,
  })

  const { data: fabricStuckCount } = useQuery({
    queryKey: ['sidebar-fabric-stuck'],
    queryFn: () =>
      getFabricStuck({ page: 0, size: 1 })
        .then(r => r?.totalElements ?? (Array.isArray(r) ? r.length : r?.items?.length ?? 0))
        .catch(() => 0),
    refetchInterval: 15000,
    retry: 0,
  })

  const { data: dlqCount } = useQuery({
    queryKey: ['sidebar-dlq-count'],
    queryFn: () =>
      onboardingApi.get('/api/dlq/messages', { params: { size: 1 } })
        .then(r => r.data?.totalElements ?? r.data?.total ?? 0)
        .catch(() => 0),
    refetchInterval: 60000,
    retry: 0,
  })

  const { data: quarantineCount } = useQuery({
    queryKey: ['sidebar-quarantine-count'],
    // Quarantine count lives on the screening service — onboarding-api has
    // no /api/quarantine. Use the same stats endpoint the Screening page
    // uses, but surface just the total count. screeningApi base URL is
    // http://localhost:8092 in dev.
    queryFn: () =>
      screeningApi.get('/api/v1/quarantine/stats')
        .then(r => r.data?.total ?? r.data?.count ?? 0)
        .catch(() => 0),
    refetchInterval: 60000,
    retry: 0,
  })

  // Map badge kind → numeric count. Nav items reference by `badge: 'kind'`.
  const badgeCountByKind = {
    compliance:  complianceCount ?? 0,
    sentinel:    sentinelOpenCount ?? 0,
    fabricStuck: fabricStuckCount ?? 0,
    dlq:         dlqCount ?? 0,
    quarantine:  quarantineCount ?? 0,
  }

  // Colors per badge kind — severity-ish mapping so the sidebar "breathes"
  // with the platform. Red = needs attention, yellow = informational.
  const badgeStyleByKind = {
    compliance:  { bg: 'rgb(239, 68, 68)',   color: '#fff' }, // red
    sentinel:    { bg: 'rgb(192, 132, 252)', color: '#fff' }, // purple (Sentinel brand)
    fabricStuck: { bg: 'rgb(234, 179, 8)',   color: '#0b0b0e' }, // yellow
    dlq:         { bg: 'rgb(100, 116, 139)', color: '#fff' }, // slate (just informational)
    quarantine:  { bg: 'rgb(248, 113, 113)', color: '#0b0b0e' }, // red-ish
  }

  const visibleGroups = navGroups
    .map(g => ({
      ...g,
      items: g.items.filter(item => {
        if (!isPageVisible(item.to)) return false
        if (item.role && item.role !== userRole) return false
        return true
      }),
    }))
    .filter(g => g.items.length > 0)

  return (
    <aside
      className="w-56 flex flex-col overflow-hidden flex-shrink-0"
      style={{ background: 'rgb(var(--canvas))' }}
    >
      {/* Brand */}
      <div className="px-4 py-4 flex items-center gap-2.5" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
        {branding.logoUrl ? (
          <img src={branding.logoUrl} alt={branding.companyName} className="h-7 object-contain" />
        ) : (
          <>
            <div
              className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0"
              style={{ background: 'rgb(var(--accent, 79 70 229))' }}
            >
              <BoltIcon className="w-4 h-4 text-white" />
            </div>
            <div className="min-w-0">
              <p className="text-white font-bold text-sm leading-tight truncate">{branding.companyName}</p>
              <p className="text-[10px] leading-tight" style={{ color: 'rgb(70, 75, 85)' }}>MFT Platform</p>
            </div>
          </>
        )}
      </div>

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto px-2 py-3 space-y-5">
        {/* ── Recently Viewed — the last 5 pages the user visited ──
            Speeds up common workflows: finance team jumps to partners,
            then flows, then back to partners. No scroll-hunting. */}
        {recentEntries && recentEntries.length > 0 && (
          <div>
            <p
              className="px-2 mb-1.5 flex items-center gap-1.5"
              style={{
                fontSize: '0.6rem',
                fontWeight: 700,
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                color: 'rgb(70, 75, 85)',
              }}
            >
              <ClockIcon className="w-3 h-3" />
              Recently Viewed
            </p>
            <div className="space-y-0.5">
              {recentEntries.slice(0, 5).map(e => (
                <Link
                  key={e.path}
                  to={e.path}
                  className="sidebar-nav-item"
                  title={e.path}
                >
                  <span className="w-[15px] flex-shrink-0" />
                  <span className="truncate text-[12px]">{labelForPath(e.path)}</span>
                </Link>
              ))}
            </div>
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-10">
            <div
              className="w-4 h-4 rounded-full border-2 animate-spin"
              style={{ borderColor: 'rgb(100, 140, 255) transparent rgb(100, 140, 255) rgb(100, 140, 255)' }}
            />
          </div>
        ) : (
          visibleGroups.map(group => (
            <div key={group.label}>
              <p
                className="px-2 mb-1.5"
                style={{
                  fontSize: '0.6rem',
                  fontWeight: 700,
                  letterSpacing: '0.1em',
                  textTransform: 'uppercase',
                  color: 'rgb(70, 75, 85)',
                }}
              >
                {group.label}
              </p>
              <div className="space-y-0.5">
                {group.items.map(item => {
                  // Live count driven by one of the polling queries above.
                  // null means "no badge ever"; 0 means "no badge right now".
                  const badgeCount = item.badge ? badgeCountByKind[item.badge] : null
                  const badgeStyle = item.badge ? badgeStyleByKind[item.badge] : null
                  return (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      className={({ isActive }) => `sidebar-nav-item ${isActive ? 'active' : ''}`}
                      title={item.label}
                    >
                      <item.icon className="w-[15px] h-[15px] flex-shrink-0" />
                      <span className="truncate">{item.label}</span>
                      {badgeCount > 0 && badgeStyle && (
                        <span
                          className="ml-auto text-[10px] font-bold rounded-full px-1.5 py-0.5 min-w-[18px] text-center leading-none"
                          style={{ background: badgeStyle.bg, color: badgeStyle.color }}
                        >
                          {badgeCount > 99 ? '99+' : badgeCount}
                        </span>
                      )}
                    </NavLink>
                  )
                })}
              </div>
            </div>
          ))
        )}
      </nav>

      {/* User Footer */}
      <div className="px-3 py-3 flex items-center gap-2.5 flex-shrink-0" style={{ borderTop: '1px solid rgb(var(--border))' }}>
        {/* Avatar */}
        <div
          className="w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 text-white font-semibold text-xs"
          style={{ background: 'rgb(var(--accent, 79 70 229))' }}
        >
          {initials}
        </div>

        {/* Info */}
        <div className="flex-1 min-w-0">
          <p className="text-xs font-medium truncate leading-tight" style={{ color: 'rgb(160, 165, 175)' }}>
            {user?.email?.split('@')[0] || 'Admin'}
          </p>
          <p style={{ fontSize: '9px', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'rgb(90, 95, 105)' }}>
            {userRole}
          </p>
        </div>

        {/* Theme toggle — small, discoverable but unobtrusive. Pops a
            360° spin on click to make the switch feel playful, and shows
            the destination theme name on hover so users know what they'll
            get. R20 addition. */}
        <button
          onClick={toggleTheme}
          title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
          aria-label="Toggle theme"
          className="p-1.5 rounded-md transition-all flex-shrink-0 theme-toggle-btn"
          style={{
            color: theme === 'dark' ? 'rgb(250, 204, 21)' : 'rgb(79, 70, 229)',
          }}
          onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
        >
          {theme === 'dark' ? (
            <SunIcon className="w-4 h-4" />
          ) : (
            <MoonIcon className="w-4 h-4" />
          )}
        </button>
        {/* Logout */}
        <button
          onClick={logout}
          title="Sign out"
          aria-label="Sign out"
          className="p-1.5 rounded-md transition-all flex-shrink-0"
          style={{ color: 'rgb(90, 95, 105)' }}
          onMouseEnter={e => { e.currentTarget.style.background = 'rgb(25, 25, 30)'; e.currentTarget.style.color = 'rgb(240, 120, 120)' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'rgb(90, 95, 105)' }}
        >
          <ArrowRightOnRectangleIcon className="w-4 h-4" />
        </button>
      </div>
    </aside>
  )
}

// ── Helpers ───────────────────────────────────────────────────────────
// Fallback label resolver for Recently Viewed. Walks the same navGroups
// array used for the sidebar so labels stay in sync. For detail routes
// like /partners/:id, falls back to a prettified segment.
function labelForPath(path) {
  // Strip querystring first — recent-viewed tracks pathname only, but
  // be defensive in case something ever changes upstream.
  const clean = path.split('?')[0]
  for (const g of navGroups) {
    for (const it of g.items) {
      if (it.to === clean) return it.label
    }
  }
  // Detail-page fallback: /partners/abc12345 → "Partner abc12345"
  const segs = clean.split('/').filter(Boolean)
  if (segs.length === 2 && /^[0-9a-f]{8,}$/i.test(segs[1])) {
    const parent = segs[0].replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
    const singular = parent.endsWith('s') ? parent.slice(0, -1) : parent
    return `${singular} ${segs[1].slice(0, 8)}`
  }
  // Generic fallback
  return clean.replace(/-/g, ' ').replace(/^\/+/, '').replace(/\b\w/g, c => c.toUpperCase()) || 'Home'
}
