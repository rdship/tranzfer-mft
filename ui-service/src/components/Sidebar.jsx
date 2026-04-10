import { NavLink } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useBranding } from '../context/BrandingContext'
import { useServices } from '../context/ServiceContext'
import { useAuth } from '../context/AuthContext'
import { configApi } from '../api/client'
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
  ClockIcon,
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
    label: 'Overview',
    items: [
      { to: '/dashboard',        icon: HomeIcon,             label: 'Dashboard' },
      { to: '/activity-monitor',  icon: ChartBarIcon,         label: 'Activity Monitor' },
      { to: '/activity',          icon: WifiIcon,             label: 'Live Activity' },
      { to: '/journey',           icon: MagnifyingGlassIcon,  label: 'Transfer Journey' },
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
    ],
  },
  {
    label: 'Security & Compliance',
    items: [
      { to: '/compliance',        icon: ShieldCheckIcon,     label: 'Compliance Profiles', role: 'ADMIN', badge: 'compliance' },
      { to: '/security-profiles', icon: ShieldCheckIcon,     label: 'Security Profiles',   role: 'ADMIN' },
      { to: '/keystore',          icon: KeyIcon,             label: 'Keystore Manager',    role: 'ADMIN' },
      { to: '/screening',         icon: MagnifyingGlassIcon, label: 'Screening & DLP' },
      { to: '/quarantine',        icon: ExclamationTriangleIcon, label: 'Quarantine',    role: 'ADMIN' },
      { to: '/2fa',               icon: ShieldCheckIcon,     label: 'Two-Factor Auth' },
      { to: '/blockchain',        icon: FingerPrintIcon,     label: 'Blockchain Proof' },
    ],
  },
  {
    label: 'Notifications & Integrations',
    items: [
      { to: '/notifications',     icon: BellAlertIcon,     label: 'Notifications',     role: 'ADMIN' },
      { to: '/connectors',        icon: BoltIcon,          label: 'Connectors',        role: 'ADMIN' },
      { to: '/sla',               icon: DocumentCheckIcon, label: 'SLA Agreements' },
      { to: '/scheduler',         icon: ClockIcon,         label: 'Scheduler',         role: 'ADMIN' },
    ],
  },
  {
    label: 'Intelligence',
    items: [
      { to: '/observatory',      icon: EyeIcon,         label: 'Observatory',          role: 'ADMIN' },
      { to: '/sentinel',         icon: CpuChipIcon,     label: 'Platform Sentinel',    role: 'ADMIN' },
      { to: '/recommendations',  icon: LightBulbIcon,   label: 'AI Recommendations' },
      { to: '/analytics',        icon: ChartBarIcon,    label: 'Analytics' },
      { to: '/predictions',      icon: BeakerIcon,      label: 'Predictions' },
      { to: '/circuit-breakers', icon: ArrowPathIcon,   label: 'Circuit Breakers',     role: 'ADMIN' },
      { to: '/auto-onboarding', icon: SparklesIcon,    label: 'Auto-Onboarding',      role: 'ADMIN' },
    ],
  },
  {
    label: 'Expert',
    items: [
      { to: '/migration',  icon: ArrowPathIcon,    label: 'Migration Center',  role: 'ADMIN' },
    ],
  },
  {
    label: 'Tools',
    items: [
      { to: '/edi',         icon: DocumentTextIcon, label: 'EDI Translation' },
      { to: '/api-console', icon: CodeBracketIcon,  label: 'API Console' },
      { to: '/terminal',    icon: CommandLineIcon,   label: 'Terminal',        role: 'ADMIN' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { to: '/platform-config', icon: AdjustmentsHorizontalIcon, label: 'Platform Config', role: 'ADMIN' },
      { to: '/tenants',          icon: ServerStackIcon,           label: 'Multi-Tenant',    role: 'ADMIN' },
      { to: '/license',          icon: KeyIcon,                   label: 'License',         role: 'ADMIN' },
      { to: '/services',         icon: CircleStackIcon,           label: 'Service Health',  role: 'ADMIN' },
      { to: '/logs',             icon: DocumentTextIcon,          label: 'Logs' },
      { to: '/dlq',              icon: InboxStackIcon,            label: 'Dead Letter Queue', role: 'ADMIN' },
    ],
  },
]

export default function Sidebar() {
  const { branding }               = useBranding()
  const { isPageVisible, loading } = useServices()
  const { user, logout }           = useAuth()
  const userRole                   = user?.role || 'USER'
  const initials                   = (user?.email?.[0] || 'A').toUpperCase()

  const { data: complianceCount } = useQuery({
    queryKey: ['compliance-violation-count-sidebar'],
    queryFn: () => configApi.get('/api/compliance/violations/count').then(r => r.data?.unresolved || 0).catch(() => 0),
    refetchInterval: 30000
  })

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
      style={{ background: 'rgb(12, 12, 15)' }}
    >
      {/* Brand */}
      <div className="px-4 py-4 flex items-center gap-2.5" style={{ borderBottom: '1px solid rgb(30, 30, 36)' }}>
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
                  const badgeCount = item.badge === 'compliance' ? complianceCount : null
                  return (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      className={({ isActive }) => `sidebar-nav-item ${isActive ? 'active' : ''}`}
                      title={item.label}
                    >
                      <item.icon className="w-[15px] h-[15px] flex-shrink-0" />
                      <span className="truncate">{item.label}</span>
                      {badgeCount > 0 && (
                        <span className="ml-auto bg-red-500 text-white text-[10px] font-bold rounded-full px-1.5 py-0.5 min-w-[18px] text-center leading-none">
                          {badgeCount}
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
      <div className="px-3 py-3 flex items-center gap-2.5 flex-shrink-0" style={{ borderTop: '1px solid rgb(30, 30, 36)' }}>
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

        {/* Logout */}
        <button
          onClick={logout}
          title="Sign out"
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
