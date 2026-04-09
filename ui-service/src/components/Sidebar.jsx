import { NavLink } from 'react-router-dom'
import { useBranding } from '../context/BrandingContext'
import { useServices } from '../context/ServiceContext'
import { useAuth } from '../context/AuthContext'
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
} from '@heroicons/react/24/outline'

const navGroups = [
  {
    label: 'Overview',
    items: [
      { to: '/dashboard',        icon: HomeIcon,             label: 'Dashboard' },
      { to: '/partners',         icon: BuildingOfficeIcon,   label: 'Partner Management' },
      { to: '/partner-setup',    icon: RocketLaunchIcon,     label: 'Onboard Partner',    role: 'ADMIN' },
      { to: '/services',         icon: CircleStackIcon,      label: 'Services',            role: 'ADMIN' },
      { to: '/journey',          icon: MagnifyingGlassIcon,  label: 'Transfer Journey' },
      { to: '/activity',         icon: WifiIcon,             label: 'Live Activity' },
      { to: '/activity-monitor', icon: ChartBarIcon,         label: 'Activity Monitor' },
    ],
  },
  {
    label: 'File Transfer',
    items: [
      { to: '/accounts',              icon: UsersIcon,           label: 'Transfer Accounts' },
      { to: '/users',                 icon: UsersIcon,           label: 'Users',                role: 'ADMIN' },
      { to: '/folder-mappings',       icon: ArrowsRightLeftIcon, label: 'Folder Mappings' },
      { to: '/flows',                 icon: CpuChipIcon,         label: 'Processing Flows' },
      { to: '/p2p',                   icon: ArrowPathIcon,       label: 'P2P Transfers' },
      { to: '/external-destinations', icon: GlobeAltIcon,        label: 'External Destinations' },
      { to: '/as2-partnerships',      icon: LinkIcon,            label: 'AS2/AS4 Partnerships' },
    ],
  },
  {
    label: 'Infrastructure',
    items: [
      { to: '/server-instances',  icon: ServerStackIcon,    label: 'Servers',           role: 'ADMIN' },
      { to: '/folder-templates',  icon: FolderIcon,         label: 'Folder Templates',  role: 'ADMIN' },
      { to: '/security-profiles', icon: ShieldCheckIcon,    label: 'Security Profiles', role: 'ADMIN' },
      { to: '/keystore',          icon: KeyIcon,            label: 'Keystore Manager',  role: 'ADMIN' },
      { to: '/2fa',               icon: ShieldCheckIcon,    label: 'Two-Factor Auth' },
      { to: '/storage',           icon: ServerStackIcon,    label: 'Storage Manager',   role: 'ADMIN' },
      { to: '/cas-dedup',         icon: CircleStackIcon,    label: 'CAS Dedup Savings', role: 'ADMIN' },
      { to: '/vfs-storage',       icon: CircleStackIcon,    label: 'VFS Storage',       role: 'ADMIN' },
      { to: '/gateway',           icon: BoltIcon,           label: 'Gateway & DMZ',     role: 'ADMIN' },
      { to: '/dmz-proxy',         icon: ShieldCheckIcon,    label: 'DMZ Proxy',         role: 'ADMIN' },
      { to: '/proxy-groups',      icon: ArrowsRightLeftIcon,label: 'Proxy Groups',       role: 'ADMIN' },
      { to: '/scheduler',         icon: ClockIcon,          label: 'Scheduler',         role: 'ADMIN' },
    ],
  },
  {
    label: 'Compliance',
    items: [
      { to: '/screening',  icon: MagnifyingGlassIcon, label: 'OFAC Screening' },
      { to: '/sla',        icon: DocumentCheckIcon,   label: 'SLA Agreements' },
      { to: '/blockchain', icon: FingerPrintIcon,     label: 'Blockchain Proof' },
      { to: '/connectors', icon: BoltIcon,            label: 'Connectors',  role: 'ADMIN' },
    ],
  },
  {
    label: 'Developer',
    items: [
      { to: '/api-console', icon: CodeBracketIcon,  label: 'Transfer API v2' },
      { to: '/edi',         icon: DocumentTextIcon, label: 'EDI Translation' },
      { to: '/tenants',     icon: ServerStackIcon,  label: 'Multi-Tenant',   role: 'ADMIN' },
    ],
  },
  {
    label: 'Observability',
    items: [
      { to: '/observatory',      icon: EyeIcon,         label: 'Observatory',          role: 'ADMIN' },
      { to: '/recommendations',  icon: LightBulbIcon,   label: 'AI Recommendations' },
      { to: '/analytics',        icon: ChartBarIcon,    label: 'Analytics' },
      { to: '/predictions',      icon: BeakerIcon,      label: 'Predictions' },
      { to: '/sentinel',         icon: CpuChipIcon,     label: 'Platform Sentinel',    role: 'ADMIN' },
      { to: '/circuit-breakers', icon: ArrowPathIcon,   label: 'Circuit Breakers',     role: 'ADMIN' },
      { to: '/logs',             icon: DocumentTextIcon,label: 'Logs' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { to: '/platform-config', icon: AdjustmentsHorizontalIcon, label: 'Platform Config', role: 'ADMIN' },
      { to: '/terminal',        icon: CommandLineIcon,            label: 'Terminal',        role: 'ADMIN' },
      { to: '/license',         icon: KeyIcon,                   label: 'License',         role: 'ADMIN' },
    ],
  },
]

export default function Sidebar() {
  const { branding }               = useBranding()
  const { isPageVisible, loading } = useServices()
  const { user, logout }           = useAuth()
  const userRole                   = user?.role || 'USER'
  const initials                   = (user?.email?.[0] || 'A').toUpperCase()

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
      style={{ background: '#09090b' }}
    >
      {/* ── Brand ── */}
      <div className="px-4 py-4 flex items-center gap-2.5" style={{ borderBottom: '1px solid #18181b' }}>
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
              <p className="text-[10px] leading-tight" style={{ color: '#3f3f46' }}>MFT Platform</p>
            </div>
          </>
        )}
      </div>

      {/* ── Nav ── */}
      <nav className="flex-1 overflow-y-auto px-2 py-3 space-y-5">
        {loading ? (
          <div className="flex items-center justify-center py-10">
            <div
              className="w-4 h-4 rounded-full border-2 animate-spin"
              style={{ borderColor: '#8b5cf6 transparent #8b5cf6 #8b5cf6' }}
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
                  color: '#3f3f46',
                }}
              >
                {group.label}
              </p>
              <div className="space-y-0.5">
                {group.items.map(item => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    className={({ isActive }) => `sidebar-nav-item ${isActive ? 'active' : ''}`}
                    title={item.label}
                  >
                    <item.icon className="w-[15px] h-[15px] flex-shrink-0" />
                    <span className="truncate">{item.label}</span>
                  </NavLink>
                ))}
              </div>
            </div>
          ))
        )}
      </nav>

      {/* ── User Footer ── */}
      <div className="px-3 py-3 flex items-center gap-2.5 flex-shrink-0" style={{ borderTop: '1px solid #18181b' }}>
        {/* Avatar */}
        <div
          className="w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 text-white font-semibold text-xs"
          style={{ background: 'rgb(var(--accent, 79 70 229))' }}
        >
          {initials}
        </div>

        {/* Info */}
        <div className="flex-1 min-w-0">
          <p className="text-xs font-medium truncate leading-tight" style={{ color: '#a1a1aa' }}>
            {user?.email?.split('@')[0] || 'Admin'}
          </p>
          <p style={{ fontSize: '9px', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: '#52525b' }}>
            {userRole}
          </p>
        </div>

        {/* Logout */}
        <button
          onClick={logout}
          title="Sign out"
          className="p-1.5 rounded-md transition-all flex-shrink-0"
          style={{ color: '#52525b' }}
          onMouseEnter={e => { e.currentTarget.style.background = '#18181b'; e.currentTarget.style.color = '#ef4444' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = '#52525b' }}
        >
          <ArrowRightOnRectangleIcon className="w-4 h-4" />
        </button>
      </div>
    </aside>
  )
}
