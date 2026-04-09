import { useQuery } from '@tanstack/react-query'
import { getCircuitBreakers } from '../api/sentinel'
import {
  ShieldCheckIcon,
  ExclamationTriangleIcon,
  XCircleIcon,
  QuestionMarkCircleIcon,
  ArrowPathIcon,
  CpuChipIcon,
  SignalIcon,
} from '@heroicons/react/24/outline'

// ── Constants ──────────────────────────────────────────────────────────────────

const SERVICE_PORTS = {
  'onboarding-api':      8080,
  'sftp-service':        8081,
  'ftp-service':         8082,
  'ftp-web-service':     8083,
  'config-service':      8084,
  'gateway-service':     8085,
  'encryption-service':  8086,
  'forwarder-service':   8087,
  'dmz-proxy':           8088,
  'license-service':     8089,
  'analytics-service':   8090,
  'ai-engine':           8091,
  'screening-service':   8092,
  'keystore-manager':    8093,
  'as2-service':         8094,
  'edi-converter':       8095,
  'storage-manager':     8096,
  'notification-service':8097,
}

const STATE_CONFIG = {
  CLOSED:    { label: 'CLOSED',    color: '#22c55e', bg: 'rgb(20 83 45 / 0.25)',  icon: ShieldCheckIcon,         desc: 'Healthy — calls flowing normally' },
  OPEN:      { label: 'OPEN',      color: '#ef4444', bg: 'rgb(127 29 29 / 0.30)', icon: XCircleIcon,             desc: 'Tripped — blocking calls to protect downstream' },
  HALF_OPEN: { label: 'HALF-OPEN', color: '#f59e0b', bg: 'rgb(120 53 15 / 0.30)', icon: ExclamationTriangleIcon, desc: 'Recovering — probing with limited calls' },
  UNKNOWN:   { label: 'UNKNOWN',   color: '#71717a', bg: 'rgb(39 39 42 / 0.50)',  icon: QuestionMarkCircleIcon,  desc: 'Unreachable — sentinel cannot poll this service' },
}

// ── Sub-components ─────────────────────────────────────────────────────────────

function StatPill({ label, value, color, icon: Icon }) {
  return (
    <div
      className="flex items-center gap-3 rounded-xl px-4 py-3"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
    >
      <div
        className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0"
        style={{ background: `${color}22` }}
      >
        <Icon className="w-4.5 h-4.5" style={{ color }} />
      </div>
      <div>
        <p
          className="text-xl font-bold leading-none font-mono"
          style={{ color, fontFamily: "'JetBrains Mono', monospace" }}
        >
          {value}
        </p>
        <p className="text-[10px] font-semibold uppercase tracking-wider mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
          {label}
        </p>
      </div>
    </div>
  )
}

function CircuitBreakerRow({ cb }) {
  const state = cb.state || 'UNKNOWN'
  const cfg = STATE_CONFIG[state] || STATE_CONFIG.UNKNOWN
  const StateIcon = cfg.icon

  const failureRate = typeof cb.failureRate === 'number' && cb.failureRate >= 0
    ? `${cb.failureRate.toFixed(1)}%`
    : '—'
  const slowRate = typeof cb.slowCallRate === 'number' && cb.slowCallRate >= 0
    ? `${cb.slowCallRate.toFixed(1)}%`
    : '—'
  const total = (cb.successfulCalls || 0) + (cb.failedCalls || 0)

  return (
    <div
      className="flex items-center gap-4 px-4 py-2.5 rounded-lg transition-all"
      style={{ background: cfg.bg, border: `1px solid ${cfg.color}30` }}
    >
      {/* State badge */}
      <div className="flex items-center gap-1.5 w-28 flex-shrink-0">
        <StateIcon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: cfg.color }} />
        <span
          className="text-[10px] font-bold uppercase tracking-wider"
          style={{ color: cfg.color }}
        >
          {cfg.label}
        </span>
      </div>

      {/* CB name */}
      <p
        className="flex-1 text-xs font-medium font-mono truncate"
        style={{
          color: 'rgb(var(--tx-primary))',
          fontFamily: "'JetBrains Mono', monospace",
        }}
      >
        {cb.name}
      </p>

      {/* Metrics grid */}
      <div className="hidden md:flex items-center gap-6 flex-shrink-0">
        <Metric label="Failure rate" value={failureRate} warn={parseFloat(failureRate) > 30} />
        <Metric label="Slow call rate" value={slowRate} />
        <Metric label="Total calls" value={total >= 0 ? total : '—'} />
        <Metric label="Blocked" value={cb.notPermittedCalls >= 0 ? cb.notPermittedCalls : '—'} warn={(cb.notPermittedCalls || 0) > 0} />
      </div>
    </div>
  )
}

function Metric({ label, value, warn }) {
  return (
    <div className="text-right">
      <p
        className="text-sm font-semibold font-mono leading-none"
        style={{
          color: warn ? '#ef4444' : 'rgb(var(--tx-primary))',
          fontFamily: "'JetBrains Mono', monospace",
        }}
      >
        {value}
      </p>
      <p className="text-[9px] uppercase tracking-wider mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
        {label}
      </p>
    </div>
  )
}

function ServiceCard({ serviceName, cbs }) {
  const port = SERVICE_PORTS[serviceName]

  // Determine worst state for the card header indicator
  const states = cbs.map(cb => cb.state || 'UNKNOWN')
  const worstState = states.includes('OPEN')
    ? 'OPEN'
    : states.includes('HALF_OPEN')
    ? 'HALF_OPEN'
    : states.includes('UNKNOWN')
    ? 'UNKNOWN'
    : 'CLOSED'
  const headerCfg = STATE_CONFIG[worstState] || STATE_CONFIG.UNKNOWN

  const openCount    = cbs.filter(cb => cb.state === 'OPEN').length
  const halfCount    = cbs.filter(cb => cb.state === 'HALF_OPEN').length
  const closedCount  = cbs.filter(cb => cb.state === 'CLOSED').length

  return (
    <div
      className="rounded-xl overflow-hidden"
      style={{
        background: 'rgb(var(--surface))',
        border: `1px solid ${headerCfg.color}50`,
        boxShadow: worstState === 'OPEN' ? `0 0 20px ${headerCfg.color}18` : 'none',
      }}
    >
      {/* Card header */}
      <div
        className="flex items-center justify-between px-4 py-3"
        style={{
          background: `${headerCfg.color}12`,
          borderBottom: `1px solid ${headerCfg.color}30`,
        }}
      >
        <div className="flex items-center gap-2.5">
          {/* Pulse dot */}
          <div className="relative flex-shrink-0">
            <div
              className="w-2.5 h-2.5 rounded-full"
              style={{ background: headerCfg.color }}
            />
            {(worstState === 'OPEN' || worstState === 'HALF_OPEN') && (
              <div
                className="absolute inset-0 rounded-full animate-ping"
                style={{ background: headerCfg.color, opacity: 0.4 }}
              />
            )}
          </div>

          <div>
            <p className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {serviceName}
            </p>
            {port && (
              <p className="text-[10px] font-mono mt-0.5" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono', monospace" }}>
                :{port}
              </p>
            )}
          </div>
        </div>

        {/* Per-state pill summary */}
        <div className="flex items-center gap-2">
          {closedCount > 0 && (
            <span className="px-2 py-0.5 rounded-full text-[10px] font-bold"
              style={{ background: 'rgb(20 83 45 / 0.3)', color: '#22c55e' }}>
              {closedCount} closed
            </span>
          )}
          {halfCount > 0 && (
            <span className="px-2 py-0.5 rounded-full text-[10px] font-bold"
              style={{ background: 'rgb(120 53 15 / 0.3)', color: '#f59e0b' }}>
              {halfCount} half-open
            </span>
          )}
          {openCount > 0 && (
            <span className="px-2 py-0.5 rounded-full text-[10px] font-bold animate-pulse"
              style={{ background: 'rgb(127 29 29 / 0.4)', color: '#ef4444' }}>
              {openCount} open
            </span>
          )}
          {cbs.length === 0 || (cbs.length === 1 && cbs[0].state === 'UNKNOWN') ? (
            <span className="px-2 py-0.5 rounded-full text-[10px] font-bold"
              style={{ background: 'rgb(39 39 42 / 0.5)', color: '#71717a' }}>
              unreachable
            </span>
          ) : null}
        </div>
      </div>

      {/* CB rows */}
      <div className="p-3 space-y-1.5">
        {/* Column headers */}
        <div className="flex items-center gap-4 px-4 pb-1">
          <p className="w-28 text-[9px] uppercase tracking-widest font-bold flex-shrink-0"
            style={{ color: 'rgb(var(--tx-muted))' }}>State</p>
          <p className="flex-1 text-[9px] uppercase tracking-widest font-bold"
            style={{ color: 'rgb(var(--tx-muted))' }}>Circuit Breaker</p>
          <div className="hidden md:flex items-center gap-6 flex-shrink-0">
            {['Failure rate', 'Slow call rate', 'Total calls', 'Blocked'].map(h => (
              <p key={h} className="w-16 text-right text-[9px] uppercase tracking-widest font-bold"
                style={{ color: 'rgb(var(--tx-muted))' }}>{h}</p>
            ))}
          </div>
        </div>

        {cbs.map((cb, i) => (
          <CircuitBreakerRow key={`${cb.name}-${i}`} cb={cb} />
        ))}
      </div>
    </div>
  )
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-24 text-center">
      <div
        className="w-16 h-16 rounded-2xl flex items-center justify-center mb-4"
        style={{ background: 'rgb(var(--hover))' }}
      >
        <SignalIcon className="w-8 h-8" style={{ color: 'rgb(var(--tx-muted))' }} />
      </div>
      <p className="font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
        No circuit breaker data yet
      </p>
      <p className="text-sm mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>
        Platform Sentinel polls services every 30 seconds.
      </p>
    </div>
  )
}

// ── Main Page ──────────────────────────────────────────────────────────────────

export default function CircuitBreakers() {
  const { data, isLoading, isError, refetch, dataUpdatedAt } = useQuery({
    queryKey: ['circuit-breakers'],
    queryFn: getCircuitBreakers,
    refetchInterval: 30_000,
    staleTime: 25_000,
  })

  const cbs         = data?.circuitBreakers || []
  const totalCount  = data?.totalCount  || 0
  const closedCount = data?.closedCount || 0
  const openCount   = data?.openCount   || 0
  const halfCount   = data?.halfOpenCount || 0
  const unknownCount = data?.unknownCount || 0

  // Group CBs by service
  const grouped = cbs.reduce((acc, cb) => {
    const svc = cb.service || 'unknown'
    if (!acc[svc]) acc[svc] = []
    acc[svc].push(cb)
    return acc
  }, {})

  // Sort: OPEN first, then HALF_OPEN, then rest
  const sortedServices = Object.entries(grouped).sort(([, aCbs], [, bCbs]) => {
    const priority = s => s === 'OPEN' ? 0 : s === 'HALF_OPEN' ? 1 : s === 'UNKNOWN' ? 3 : 2
    const aWorst = Math.min(...aCbs.map(cb => priority(cb.state || 'UNKNOWN')))
    const bWorst = Math.min(...bCbs.map(cb => priority(cb.state || 'UNKNOWN')))
    return aWorst - bWorst
  })

  const secondsAgo = dataUpdatedAt
    ? Math.round((Date.now() - dataUpdatedAt) / 1000)
    : null

  return (
    <div className="space-y-6">

      {/* ── Page header ── */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <CpuChipIcon className="w-5 h-5" style={{ color: 'rgb(var(--accent))' }} />
            <h1 className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              Circuit Breaker Status
            </h1>
          </div>
          <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
            Live Resilience4j circuit breaker states across all 18 platform services · refreshes every 30 s
            {secondsAgo !== null && (
              <span className="ml-2 font-mono text-xs" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono', monospace" }}>
                · updated {secondsAgo}s ago
              </span>
            )}
          </p>
        </div>

        <button
          onClick={() => refetch()}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all"
          style={{
            background: 'rgb(var(--hover))',
            color: 'rgb(var(--tx-secondary))',
            border: '1px solid rgb(var(--border))',
          }}
          onMouseEnter={e => {
            e.currentTarget.style.background = 'rgb(var(--accent))'
            e.currentTarget.style.color = '#fff'
          }}
          onMouseLeave={e => {
            e.currentTarget.style.background = 'rgb(var(--hover))'
            e.currentTarget.style.color = 'rgb(var(--tx-secondary))'
          }}
        >
          <ArrowPathIcon className="w-3.5 h-3.5" />
          Refresh
        </button>
      </div>

      {/* ── Summary stats ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <StatPill label="Total CBs"    value={isLoading ? '—' : totalCount}  color="#60a5fa" icon={SignalIcon} />
        <StatPill label="Closed"       value={isLoading ? '—' : closedCount}  color="#22c55e" icon={ShieldCheckIcon} />
        <StatPill label="Half-Open"    value={isLoading ? '—' : halfCount}    color="#f59e0b" icon={ExclamationTriangleIcon} />
        <StatPill label="Open / Down"  value={isLoading ? '—' : openCount + unknownCount} color="#ef4444" icon={XCircleIcon} />
      </div>

      {/* ── Legend ── */}
      <div
        className="flex flex-wrap gap-4 px-4 py-2.5 rounded-lg"
        style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
      >
        {Object.entries(STATE_CONFIG).map(([state, cfg]) => {
          const Icon = cfg.icon
          return (
            <div key={state} className="flex items-center gap-1.5">
              <Icon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: cfg.color }} />
              <span className="text-xs font-semibold" style={{ color: cfg.color }}>{cfg.label}</span>
              <span className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>— {cfg.desc}</span>
            </div>
          )
        })}
      </div>

      {/* ── Content ── */}
      {isLoading ? (
        <div className="space-y-4">
          {[1, 2, 3, 4].map(i => (
            <div
              key={i}
              className="h-28 rounded-xl animate-pulse"
              style={{ background: 'rgb(var(--surface))' }}
            />
          ))}
        </div>
      ) : isError ? (
        <div
          className="flex items-center gap-3 p-4 rounded-xl"
          style={{ background: 'rgb(127 29 29 / 0.15)', border: '1px solid rgb(239 68 68 / 0.3)' }}
        >
          <XCircleIcon className="w-5 h-5 flex-shrink-0" style={{ color: '#ef4444' }} />
          <div>
            <p className="text-sm font-semibold" style={{ color: '#ef4444' }}>
              Failed to fetch circuit breaker data
            </p>
            <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
              Platform Sentinel (port 8098) may be unreachable. Check that it is running.
            </p>
          </div>
        </div>
      ) : sortedServices.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="space-y-4">
          {sortedServices.map(([serviceName, serviceCbs]) => (
            <ServiceCard
              key={serviceName}
              serviceName={serviceName}
              cbs={serviceCbs}
            />
          ))}
        </div>
      )}
    </div>
  )
}
