import { NavLink } from 'react-router-dom'
import { useBranding } from '../context/BrandingContext'
import { useServices } from '../context/ServiceContext'
import {
  HomeIcon, UsersIcon, ServerStackIcon, ArrowsRightLeftIcon,
  ShieldCheckIcon, GlobeAltIcon, ChartBarIcon,
  CpuChipIcon, DocumentTextIcon, KeyIcon,
  Cog6ToothIcon, WifiIcon, BoltIcon, CommandLineIcon,
  ArrowPathIcon
} from '@heroicons/react/24/outline'

/**
 * Each nav item has a 'to' path. The Sidebar only renders items
 * where isPageVisible(to) returns true — meaning the required
 * microservice is both running AND licensed.
 */
const navGroups = [
  {
    label: 'Overview',
    items: [
      { to: '/dashboard', icon: HomeIcon, label: 'Dashboard' },
      { to: '/journey', icon: DocumentTextIcon, label: 'Transfer Journey' },
      { to: '/monitoring', icon: WifiIcon, label: 'Service Health' },
    ]
  },
  {
    label: 'File Transfer',
    items: [
      { to: '/accounts', icon: UsersIcon, label: 'Transfer Accounts' },
      { to: '/users', icon: UsersIcon, label: 'Users' },
      { to: '/folder-mappings', icon: ArrowsRightLeftIcon, label: 'Folder Mappings' },
      { to: '/flows', icon: ArrowPathIcon, label: 'Processing Flows' },
      { to: '/p2p', icon: ArrowsRightLeftIcon, label: 'P2P Transfers' },
      { to: '/external-destinations', icon: GlobeAltIcon, label: 'External Destinations' },
    ]
  },
  {
    label: 'Infrastructure',
    items: [
      { to: '/servers', icon: ServerStackIcon, label: 'Server Config' },
      { to: '/security-profiles', icon: ShieldCheckIcon, label: 'Security Profiles' },
      { to: '/keystore', icon: KeyIcon, label: 'Keystore Manager' },
      { to: '/gateway', icon: BoltIcon, label: 'Gateway & DMZ' },
      { to: '/scheduler', icon: CpuChipIcon, label: 'Scheduler' },
    ]
  },
  {
    label: 'Compliance',
    items: [
      { to: '/screening', icon: ShieldCheckIcon, label: 'OFAC Screening' },
      { to: '/sla', icon: DocumentTextIcon, label: 'SLA Agreements' },
      { to: '/connectors', icon: BoltIcon, label: 'Connectors' },
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
      { to: '/terminal', icon: CommandLineIcon, label: 'Terminal' },
      { to: '/license', icon: KeyIcon, label: 'License' },
      { to: '/settings', icon: Cog6ToothIcon, label: 'Settings' },
    ]
  }
]

export default function Sidebar() {
  const { branding } = useBranding()
  const { isPageVisible, loading } = useServices()

  // Filter nav groups: only show groups that have at least one visible item
  const visibleGroups = navGroups.map(group => ({
    ...group,
    items: group.items.filter(item => isPageVisible(item.to))
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
            <span className="text-white font-bold text-sm">{branding.companyName}</span>
          </div>
        )}
      </div>

      {/* Navigation — only visible pages */}
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
