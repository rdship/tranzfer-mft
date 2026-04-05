import { NavLink } from 'react-router-dom'
import { useBranding } from '../context/BrandingContext'
import { useServices } from '../context/ServiceContext'
import { useAuth } from '../context/AuthContext'
import {
  HomeIcon, UsersIcon, ServerStackIcon, ArrowsRightLeftIcon,
  ShieldCheckIcon, GlobeAltIcon, ChartBarIcon,
  CpuChipIcon, DocumentTextIcon, KeyIcon,
  Cog6ToothIcon, WifiIcon, BoltIcon, CommandLineIcon,
  ArrowPathIcon, AdjustmentsHorizontalIcon
} from '@heroicons/react/24/outline'

/**
 * Each nav item has:
 * - 'to' path: only renders if isPageVisible(to) returns true
 * - 'role' (optional): 'ADMIN' = admin only, undefined = any authenticated user
 *
 * When LDAP is connected, the role comes from the LDAP group mapping.
 * For now, roles are USER or ADMIN from the local auth system.
 */
const navGroups = [
  {
    label: 'Overview',
    items: [
      { to: '/dashboard', icon: HomeIcon, label: 'Dashboard' },
      { to: '/journey', icon: DocumentTextIcon, label: 'Transfer Journey' },
      { to: '/activity', icon: WifiIcon, label: 'Live Activity' },
      { to: '/monitoring', icon: WifiIcon, label: 'Service Health' },
    ]
  },
  {
    label: 'File Transfer',
    items: [
      { to: '/accounts', icon: UsersIcon, label: 'Transfer Accounts' },
      { to: '/users', icon: UsersIcon, label: 'Users', role: 'ADMIN' },
      { to: '/folder-mappings', icon: ArrowsRightLeftIcon, label: 'Folder Mappings' },
      { to: '/flows', icon: ArrowPathIcon, label: 'Processing Flows' },
      { to: '/p2p', icon: ArrowsRightLeftIcon, label: 'P2P Transfers' },
      { to: '/external-destinations', icon: GlobeAltIcon, label: 'External Destinations' },
    ]
  },
  {
    label: 'Infrastructure',
    items: [
      { to: '/servers', icon: ServerStackIcon, label: 'Server Config', role: 'ADMIN' },
      { to: '/server-instances', icon: ServerStackIcon, label: 'Server Instances', role: 'ADMIN' },
      { to: '/security-profiles', icon: ShieldCheckIcon, label: 'Security Profiles', role: 'ADMIN' },
      { to: '/encryption', icon: KeyIcon, label: 'Encryption Keys' },
      { to: '/keystore', icon: KeyIcon, label: 'Keystore Manager', role: 'ADMIN' },
      { to: '/2fa', icon: ShieldCheckIcon, label: 'Two-Factor Auth' },
      { to: '/storage', icon: ServerStackIcon, label: 'Storage Manager', role: 'ADMIN' },
      { to: '/gateway', icon: BoltIcon, label: 'Gateway & DMZ', role: 'ADMIN' },
      { to: '/scheduler', icon: CpuChipIcon, label: 'Scheduler', role: 'ADMIN' },
    ]
  },
  {
    label: 'Compliance',
    items: [
      { to: '/screening', icon: ShieldCheckIcon, label: 'OFAC Screening' },
      { to: '/sla', icon: DocumentTextIcon, label: 'SLA Agreements' },
      { to: '/blockchain', icon: ShieldCheckIcon, label: 'Blockchain Proof' },
      { to: '/connectors', icon: BoltIcon, label: 'Connectors', role: 'ADMIN' },
    ]
  },
  {
    label: 'Developer',
    items: [
      { to: '/api-console', icon: CommandLineIcon, label: 'Transfer API v2' },
      { to: '/edi', icon: DocumentTextIcon, label: 'EDI Translation' },
      { to: '/tenants', icon: ServerStackIcon, label: 'Multi-Tenant', role: 'ADMIN' },
    ]
  },
  {
    label: 'Observability',
    items: [
      { to: '/recommendations', icon: ChartBarIcon, label: 'AI Recommendations' },
      { to: '/analytics', icon: ChartBarIcon, label: 'Analytics' },
      { to: '/predictions', icon: CpuChipIcon, label: 'Predictions' },
      { to: '/logs', icon: DocumentTextIcon, label: 'Logs' },
    ]
  },
  {
    label: 'Administration',
    items: [
      { to: '/platform-config', icon: AdjustmentsHorizontalIcon, label: 'Platform Config', role: 'ADMIN' },
      { to: '/terminal', icon: CommandLineIcon, label: 'Terminal', role: 'ADMIN' },
      { to: '/license', icon: KeyIcon, label: 'License', role: 'ADMIN' },
      { to: '/settings', icon: Cog6ToothIcon, label: 'Settings', role: 'ADMIN' },
    ]
  }
]

export default function Sidebar() {
  const { branding } = useBranding()
  const { isPageVisible, loading } = useServices()
  const { user } = useAuth()
  const userRole = user?.role || 'USER'

  // Filter nav groups: service visibility + role-based access
  const visibleGroups = navGroups.map(group => ({
    ...group,
    items: group.items.filter(item => {
      if (!isPageVisible(item.to)) return false
      if (item.role && item.role !== userRole) return false
      return true
    })
  })).filter(group => group.items.length > 0)

  return (
    <aside className="w-60 bg-slate-900 flex flex-col overflow-y-auto">
      {/* Logo */}
      <div className="p-4 border-b border-slate-700">
        {branding.logoUrl ? (
          <img src={branding.logoUrl} alt={branding.companyName} className="h-8 object-contain" />
        ) : (
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center">
              <BoltIcon className="w-5 h-5 text-white" />
            </div>
            <div>
              <span className="text-white font-bold text-sm block leading-tight">{branding.companyName}</span>
            </div>
          </div>
        )}
      </div>

      {/* Navigation — only visible pages for user's role */}
      <nav className="flex-1 p-3 space-y-4">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
          </div>
        ) : (
          visibleGroups.map(group => (
            <div key={group.label}>
              <p className="text-slate-500 text-xs font-semibold uppercase tracking-wider px-3 mb-1">
                {group.label}
              </p>
              {group.items.map(item => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    `sidebar-nav-item ${isActive ? 'active' : ''}`
                  }
                >
                  <item.icon className="w-4 h-4 flex-shrink-0" />
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))
        )}
      </nav>

      <div className="p-4 border-t border-slate-700">
        <p className="text-slate-500 text-xs">TranzFer MFT v3.0</p>
        <p className="text-slate-600 text-xs mt-0.5">{visibleGroups.reduce((n, g) => n + g.items.length, 0)} pages active</p>
      </div>
    </aside>
  )
}
