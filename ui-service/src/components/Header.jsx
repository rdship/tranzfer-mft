import { useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { BellIcon } from '@heroicons/react/24/outline'
import { useQuery } from '@tanstack/react-query'
import { getDashboard } from '../api/analytics'

const ENV_BADGE = {
  DEV:     { bg: '#1e3a5f', text: '#60a5fa', label: 'DEV' },
  TEST:    { bg: '#3b2f00', text: '#fbbf24', label: 'TEST' },
  CERT:    { bg: '#3b1f00', text: '#fb923c', label: 'CERT' },
  STAGING: { bg: '#2e1065', text: '#c084fc', label: 'STAGING' },
  PROD:    { bg: '#3b0a0a', text: '#f87171', label: 'PROD' },
}

// Human-readable page titles
const PAGE_TITLES = {
  '/dashboard':          'Dashboard',
  '/partners':           'Partner Management',
  '/partner-setup':      'Onboard Partner',
  '/services':           'Service Registry',
  '/journey':            'Transfer Journey',
  '/activity':           'Live Activity',
  '/activity-monitor':   'Activity Monitor',
  '/accounts':           'Transfer Accounts',
  '/users':              'Users',
  '/folder-mappings':    'Folder Mappings',
  '/flows':              'Processing Flows',
  '/p2p':                'P2P Transfers',
  '/external-destinations': 'External Destinations',
  '/as2-partnerships':   'AS2/AS4 Partnerships',
  '/screening':          'OFAC Screening',
  '/sla':                'SLA Agreements',
  '/blockchain':         'Blockchain Proof',
  '/connectors':         'Connectors',
  '/server-instances':   'Server Instances',
  '/folder-templates':   'Folder Templates',
  '/security-profiles':  'Security Profiles',
  '/keystore':           'Keystore Manager',
  '/2fa':                'Two-Factor Auth',
  '/storage':            'Storage Manager',
  '/vfs-storage':        'VFS Storage',
  '/gateway':            'Gateway & DMZ',
  '/dmz-proxy':          'DMZ Proxy',
  '/scheduler':          'Scheduler',
  '/api-console':        'Transfer API v2',
  '/edi':                'EDI Translation',
  '/tenants':            'Multi-Tenant',
  '/observatory':        'Observatory',
  '/recommendations':    'AI Recommendations',
  '/analytics':          'Analytics',
  '/predictions':        'Predictions',
  '/sentinel':           'Platform Sentinel',
  '/logs':               'Audit Logs',
  '/platform-config':    'Platform Config',
  '/terminal':           'Terminal',
  '/license':            'License',
}

const CURRENT_ENV = import.meta.env.VITE_PLATFORM_ENVIRONMENT || 'PROD'
const envStyle    = ENV_BADGE[CURRENT_ENV] || ENV_BADGE.PROD

export default function Header() {
  const { user }  = useAuth()
  const { pathname } = useLocation()
  const { data: dashboard } = useQuery({
    queryKey: ['dashboard'],
    queryFn: getDashboard,
    refetchInterval: 30000,
    staleTime: 20000,
  })
  const alertCount = dashboard?.alerts?.length || 0
  const pageTitle  = PAGE_TITLES[pathname] || 'TranzFer MFT'

  return (
    <header
      className="flex items-center justify-between px-6 flex-shrink-0"
      style={{
        height: '52px',
        background: 'rgb(var(--surface))',
        borderBottom: '1px solid rgb(var(--border))',
      }}
    >
      {/* Left — page title */}
      <div className="flex items-center gap-3">
        <h1
          className="font-semibold text-sm leading-none"
          style={{ color: 'rgb(var(--tx-primary))' }}
        >
          {pageTitle}
        </h1>

        {/* Env badge */}
        <span
          className="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-widest uppercase"
          style={{ background: envStyle.bg, color: envStyle.text }}
        >
          {envStyle.label}
        </span>
      </div>

      {/* Right — alerts + user */}
      <div className="flex items-center gap-3">
        {/* Alert bell */}
        <button
          className="relative p-1.5 rounded-lg transition-colors"
          style={{ color: 'rgb(var(--tx-secondary))' }}
          onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))'; e.currentTarget.style.color = 'rgb(var(--tx-primary))' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'rgb(var(--tx-secondary))' }}
          title={alertCount ? `${alertCount} active alert${alertCount !== 1 ? 's' : ''}` : 'No alerts'}
        >
          <BellIcon className="w-[18px] h-[18px]" />
          {alertCount > 0 && (
            <span className="absolute -top-0.5 -right-0.5 bg-red-500 text-white rounded-full flex items-center justify-center font-bold"
              style={{ fontSize: '9px', width: '14px', height: '14px' }}>
              {alertCount > 9 ? '9+' : alertCount}
            </span>
          )}
        </button>

        {/* Divider */}
        <div className="w-px h-5" style={{ background: 'rgb(var(--border))' }} />

        {/* User info */}
        <div className="flex items-center gap-2">
          <div
            className="w-7 h-7 rounded-full flex items-center justify-center text-white font-semibold text-xs flex-shrink-0"
            style={{ background: 'rgb(var(--accent))' }}
          >
            {(user?.email?.[0] || 'A').toUpperCase()}
          </div>
          <div className="hidden sm:block">
            <p className="text-xs font-medium leading-none mb-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
              {user?.email?.split('@')[0] || 'Admin'}
            </p>
            <p className="text-[10px] font-semibold uppercase tracking-wider leading-none" style={{ color: 'rgb(var(--tx-muted))' }}>
              {user?.role || 'USER'}
            </p>
          </div>
        </div>
      </div>
    </header>
  )
}
