import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  BellIcon,
  MagnifyingGlassIcon,
  ChevronDownIcon,
  ArrowRightOnRectangleIcon,
  KeyIcon,
  Cog6ToothIcon,
  ShieldExclamationIcon,
} from '@heroicons/react/24/outline'
import { useQuery } from '@tanstack/react-query'
import { getDashboard } from '../api/analytics'
import { onboardingApi } from '../api/client'

/* R127: PROD chip was loud red and visible on every page — useful in
   production (safety signal) but distracting in dev/test. Lowered the
   visual weight of the non-prod chips; kept PROD saturated because that
   one MUST be noticed. */
const ENV_BADGE = {
  DEV:     { bg: 'transparent', text: 'rgb(var(--tx-muted))',       label: 'DEV',     border: '1px solid rgb(var(--border-subtle) / 0.08)' },
  TEST:    { bg: 'transparent', text: 'rgb(var(--tx-muted))',       label: 'TEST',    border: '1px solid rgb(var(--border-subtle) / 0.08)' },
  CERT:    { bg: 'rgb(var(--warning) / 0.12)', text: 'rgb(var(--warning))', label: 'CERT' },
  STAGING: { bg: 'rgb(var(--info) / 0.12)',    text: 'rgb(var(--info))',    label: 'STAGING' },
  PROD:    { bg: 'rgb(var(--danger) / 0.15)',  text: 'rgb(var(--danger))',  label: 'PROD' },
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
  '/cas-dedup':          'CAS Deduplication Savings',
  '/vfs-storage':        'VFS Storage',
  '/gateway':            'Gateway & DMZ',
  '/dmz-proxy':          'DMZ Proxy',
  '/proxy-groups':       'Proxy Groups',
  '/scheduler':          'Scheduler',
  '/api-console':        'Transfer API v2',
  '/edi':                'EDI Translation',
  '/tenants':            'Multi-Tenant',
  '/observatory':        'Observatory',
  '/recommendations':    'AI Recommendations',
  '/analytics':          'Analytics',
  '/predictions':        'Predictions',
  '/sentinel':           'Platform Sentinel',
  '/circuit-breakers':   'Circuit Breaker Status',
  '/logs':               'Audit Logs',
  '/platform-config':    'Platform Config',
  '/terminal':           'Terminal',
  '/license':            'License',
}

const CURRENT_ENV = import.meta.env.VITE_PLATFORM_ENVIRONMENT || 'PROD'
const envStyle    = ENV_BADGE[CURRENT_ENV] || ENV_BADGE.PROD

// Severity → color chip for Sentinel finding list
const SEVERITY_STYLE = {
  CRITICAL: { bg: '#3b0a0a', text: '#f87171' },
  HIGH:     { bg: '#3b1f00', text: '#fb923c' },
  MEDIUM:   { bg: '#3b2f00', text: '#fbbf24' },
  LOW:      { bg: '#1e3a5f', text: '#60a5fa' },
  INFO:     { bg: '#1f2937', text: '#9ca3af' },
}

export default function Header() {
  const { user, logout } = useAuth()
  const { pathname }     = useLocation()

  const [bellOpen, setBellOpen] = useState(false)
  const [userOpen, setUserOpen] = useState(false)
  const bellRef = useRef(null)
  const userRef = useRef(null)

  const { data: dashboard } = useQuery({
    queryKey: ['dashboard'],
    queryFn: getDashboard,
    meta: { silent: true }, refetchInterval: 30000,
    staleTime: 20000,
  })
  const alertCount = dashboard?.alerts?.length || 0

  // Sentinel open findings — same query-key as Sidebar for react-query dedupe,
  // but we request the top-5 list (Sidebar only asks for totalElements with size=1).
  const { data: sentinelData } = useQuery({
    queryKey: ['header-sentinel-findings-top5'],
    queryFn: () =>
      onboardingApi
        .get('/api/v1/sentinel/findings', { params: { status: 'OPEN', size: 5 } })
        .then(r => {
          const raw = r.data
          const items = Array.isArray(raw)
            ? raw
            : raw?.content ?? raw?.items ?? raw?.data ?? []
          const total = raw?.totalElements ?? raw?.total ?? items.length ?? 0
          return { items, total }
        })
        .catch(() => null), // silent degradation
    meta: { silent: true }, refetchInterval: 60000,
    retry: 0,
  })
  const sentinelItems = sentinelData?.items ?? []
  const sentinelTotal = sentinelData?.total ?? 0

  const pageTitle = PAGE_TITLES[pathname] || 'TranzFer MFT'

  // Close any open dropdown on outside-click or Escape
  useEffect(() => {
    if (!bellOpen && !userOpen) return
    const onClick = (e) => {
      if (bellOpen && bellRef.current && !bellRef.current.contains(e.target)) {
        setBellOpen(false)
      }
      if (userOpen && userRef.current && !userRef.current.contains(e.target)) {
        setUserOpen(false)
      }
    }
    const onKey = (e) => {
      if (e.key === 'Escape') {
        setBellOpen(false)
        setUserOpen(false)
      }
    }
    window.addEventListener('mousedown', onClick)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onClick)
      window.removeEventListener('keydown', onKey)
    }
  }, [bellOpen, userOpen])

  const openGlobalSearch = () => {
    window.dispatchEvent(new CustomEvent('global-search-open'))
  }
  const openShortcuts = () => {
    setUserOpen(false)
    window.dispatchEvent(new CustomEvent('keyboard-shortcuts-open'))
  }

  const badgeText = sentinelTotal > 99 ? '99+' : String(sentinelTotal)

  return (
    <header
      // R127: glassmorphism on the top nav per the dark-mode redesign spec.
      // Pulls in the login page aesthetic so the app feels like a sibling of
      // login, not a stranger. 72% opaque bg-raised + 12px backdrop blur —
      // page hints through without obscuring the table content below.
      className="top-nav-glass flex items-center justify-between px-6 flex-shrink-0 sticky top-0 z-20"
      style={{ height: '52px' }}
    >
      {/* Left — page title + env badge */}
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
          style={{ background: envStyle.bg, color: envStyle.text, border: envStyle.border || 'none' }}
        >
          {envStyle.label}
        </span>
      </div>

      {/* Center — global search trigger */}
      <div className="flex-1 flex justify-center px-6">
        <button
          type="button"
          onClick={openGlobalSearch}
          title="Press Cmd+K to search"
          className="flex items-center gap-2 h-8 px-3 rounded-lg transition-colors group"
          style={{
            width: '320px',
            maxWidth: '100%',
            background: 'rgb(var(--canvas))',
            border: '1px solid rgb(48, 48, 56)',
            color: 'rgb(148, 163, 184)',
          }}
          onMouseEnter={e => { e.currentTarget.style.borderColor = 'rgb(var(--accent))' }}
          onMouseLeave={e => { e.currentTarget.style.borderColor = 'rgb(48, 48, 56)' }}
        >
          <MagnifyingGlassIcon className="w-4 h-4 flex-shrink-0" />
          <span className="text-xs flex-1 text-left">Search everywhere...</span>
          <kbd
            className="font-mono px-1.5 py-0.5 rounded text-[9px]"
            style={{ background: 'rgb(var(--border))', color: 'rgb(148, 163, 184)' }}
          >
            ⌘K
          </kbd>
        </button>
      </div>

      {/* Right — dashboard alerts + sentinel bell + user */}
      <div className="flex items-center gap-3">
        {/* Dashboard alert bell (existing) */}
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

        {/* Sentinel findings bell + dropdown */}
        <div className="relative" ref={bellRef}>
          <button
            onClick={() => { setBellOpen(v => !v); setUserOpen(false) }}
            className="relative p-1.5 rounded-lg transition-colors"
            style={{ color: 'rgb(var(--tx-secondary))' }}
            onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))'; e.currentTarget.style.color = 'rgb(var(--tx-primary))' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'rgb(var(--tx-secondary))' }}
            title={sentinelTotal ? `${sentinelTotal} open Sentinel finding${sentinelTotal !== 1 ? 's' : ''}` : 'No open findings'}
            aria-label="Sentinel findings"
          >
            <ShieldExclamationIcon className="w-[18px] h-[18px]" />
            {sentinelTotal > 0 && (
              <span
                className="absolute -top-0.5 -right-0.5 text-white rounded-full flex items-center justify-center font-bold"
                style={{
                  background: 'rgb(239, 68, 68)',
                  fontSize: '9px',
                  minWidth: '14px',
                  height: '14px',
                  padding: '0 3px',
                }}
              >
                {badgeText}
              </span>
            )}
          </button>

          {bellOpen && (
            <div
              className="absolute right-0 mt-2 rounded-lg shadow-lg z-40 animate-in fade-in slide-in-from-top-1 duration-150"
              style={{
                width: '340px',
                background: 'rgb(var(--canvas))',
                border: '1px solid rgb(var(--border))',
              }}
            >
              <div
                className="px-4 py-2.5 flex items-center justify-between"
                style={{ borderBottom: '1px solid rgb(var(--border))' }}
              >
                <span className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-primary))' }}>
                  Sentinel findings
                </span>
                <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {sentinelTotal} open
                </span>
              </div>

              <div className="max-h-80 overflow-y-auto">
                {sentinelItems.length === 0 ? (
                  <div className="px-4 py-6 text-center text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
                    {sentinelData === null ? 'Sentinel unreachable' : 'No open findings'}
                  </div>
                ) : (
                  sentinelItems.slice(0, 5).map((f, i) => {
                    const sev = (f.severity || 'INFO').toUpperCase()
                    const sevStyle = SEVERITY_STYLE[sev] || SEVERITY_STYLE.INFO
                    const title = f.title || f.message || f.ruleId || f.rule || 'Untitled finding'
                    const key = f.id ?? f.findingId ?? i
                    return (
                      <Link
                        key={key}
                        to="/sentinel"
                        onClick={() => setBellOpen(false)}
                        className="flex items-start gap-2 px-4 py-2.5 transition-colors"
                        style={{
                          borderBottom: i < Math.min(4, sentinelItems.length - 1) ? '1px solid rgb(var(--border))' : 'none',
                        }}
                        onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
                        onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
                      >
                        <span
                          className="mt-0.5 px-1.5 py-0.5 rounded text-[9px] font-bold tracking-wider uppercase flex-shrink-0"
                          style={{ background: sevStyle.bg, color: sevStyle.text }}
                        >
                          {sev}
                        </span>
                        <span
                          className="text-xs leading-snug line-clamp-2"
                          style={{ color: 'rgb(var(--tx-primary))' }}
                        >
                          {title}
                        </span>
                      </Link>
                    )
                  })
                )}
              </div>

              <Link
                to="/sentinel"
                onClick={() => setBellOpen(false)}
                className="block px-4 py-2.5 text-center text-xs font-semibold transition-colors"
                style={{
                  borderTop: '1px solid rgb(var(--border))',
                  color: 'rgb(var(--accent))',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
              >
                View all findings →
              </Link>
            </div>
          )}
        </div>

        {/* Divider */}
        <div className="w-px h-5" style={{ background: 'rgb(var(--border))' }} />

        {/* User info + menu dropdown */}
        <div className="relative" ref={userRef}>
          <button
            onClick={() => { setUserOpen(v => !v); setBellOpen(false) }}
            className="flex items-center gap-2 pl-1 pr-2 py-1 rounded-lg transition-colors"
            onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
            aria-label="User menu"
          >
            <div
              className="w-7 h-7 rounded-full flex items-center justify-center text-white font-semibold text-xs flex-shrink-0"
              style={{ background: 'rgb(var(--accent))' }}
            >
              {(user?.email?.[0] || 'A').toUpperCase()}
            </div>
            <div className="hidden sm:block text-left">
              <p className="text-xs font-medium leading-none mb-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
                {user?.email?.split('@')[0] || 'Admin'}
              </p>
              <p className="text-[10px] font-semibold uppercase tracking-wider leading-none" style={{ color: 'rgb(var(--tx-muted))' }}>
                {user?.role || 'USER'}
              </p>
            </div>
            <ChevronDownIcon
              className="w-3.5 h-3.5 hidden sm:block transition-transform"
              style={{
                color: 'rgb(var(--tx-muted))',
                transform: userOpen ? 'rotate(180deg)' : 'none',
              }}
            />
          </button>

          {userOpen && (
            <div
              className="absolute right-0 mt-2 rounded-lg shadow-lg z-40 animate-in fade-in slide-in-from-top-1 duration-150"
              style={{
                width: '260px',
                background: 'rgb(var(--canvas))',
                border: '1px solid rgb(var(--border))',
              }}
            >
              <div className="px-4 py-3" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                <p className="text-xs font-medium truncate" style={{ color: 'rgb(var(--tx-primary))' }}>
                  {user?.email || 'admin@localhost'}
                </p>
                <p className="text-[10px] mt-0.5 font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {user?.role || 'USER'}
                </p>
              </div>

              <Link
                to="/users"
                onClick={() => setUserOpen(false)}
                className="flex items-center gap-2.5 px-4 py-2 text-xs transition-colors"
                style={{ color: 'rgb(var(--tx-primary))' }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
              >
                <Cog6ToothIcon className="w-4 h-4" />
                Profile &amp; Settings
              </Link>

              <div className="h-px mx-3" style={{ background: 'rgb(var(--border))' }} />

              <button
                type="button"
                onClick={openShortcuts}
                className="w-full flex items-center gap-2.5 px-4 py-2 text-xs transition-colors text-left"
                style={{ color: 'rgb(var(--tx-primary))' }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
              >
                <KeyIcon className="w-4 h-4" />
                Keyboard shortcuts
                <kbd
                  className="ml-auto font-mono px-1.5 py-0.5 rounded text-[9px]"
                  style={{ background: 'rgb(var(--border))', color: 'rgb(var(--tx-muted))' }}
                >
                  ?
                </kbd>
              </button>

              <div className="h-px mx-3" style={{ background: 'rgb(var(--border))' }} />

              <button
                type="button"
                onClick={() => { setUserOpen(false); logout() }}
                className="w-full flex items-center gap-2.5 px-4 py-2 text-xs transition-colors text-left"
                style={{ color: '#f87171' }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
              >
                <ArrowRightOnRectangleIcon className="w-4 h-4" />
                Sign out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
