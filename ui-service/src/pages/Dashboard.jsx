import { Suspense, lazy } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, Link } from 'react-router-dom'
import { getDashboard, getPredictions, getFlowLiveStats } from '../api/analytics'
import { getAgentsDashboard } from '../api/ai'
import { getFabricQueues, getFabricStuck, getFabricInstances, getFabricLatency } from '../api/fabric'
import * as sentinelApi from '../api/sentinel'
import { onboardingApi } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { useServices } from '../context/ServiceContext'
import LoadingSpinner from '../components/LoadingSpinner'

// Lazy-load the chart sub-components so `recharts` is split out of the main
// bundle (it was the biggest single dependency at ~87 kB gzipped). On first
// paint, users see a lightweight spinner in place of the charts; once the
// chunk lands (~100 ms) the chart renders. Huge win for fresh-boot demo load
// time. See docs/plans/CONFIG-EXPORT-IMPORT.md and round-6 for context.
const DashboardVolumeChart = lazy(() => import('../components/dashboard/DashboardVolumeChart'))
const DashboardProtocolPie = lazy(() => import('../components/dashboard/DashboardProtocolPie'))

function ChartFallback({ height = 220 }) {
  return (
    <div
      className="flex items-center justify-center animate-pulse rounded-lg"
      style={{ height, background: 'rgba(139, 92, 246, 0.05)' }}
    >
      <div className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
        Loading chart…
      </div>
    </div>
  )
}
import {
  ArrowUpTrayIcon, CheckCircleIcon, ServerIcon, ChartBarIcon,
  ExclamationTriangleIcon, ArrowTrendingUpIcon, BoltIcon,
  BuildingOfficeIcon, ArrowsRightLeftIcon, CpuChipIcon,
  ClockIcon, ArrowPathIcon, ShieldCheckIcon,
  ArrowTopRightOnSquareIcon, ArrowRightIcon, Squares2X2Icon,
  RectangleStackIcon, FireIcon, ClockIcon as ClockOutline,
  DocumentMagnifyingGlassIcon, KeyIcon, CircleStackIcon,
  FolderIcon, BellAlertIcon,
} from '@heroicons/react/24/outline'
import { format } from 'date-fns'
import { NavLink } from 'react-router-dom'

/* Neon chart palette — pops on dark backgrounds */
const NEON = ['#8b5cf6', '#22d3ee', '#34d399', '#f87171', '#fbbf24']

const quickActions = [
  { label: 'Partner Mgmt',     to: '/partners',        icon: BuildingOfficeIcon, color: '#8b5cf6' },
  { label: 'Accounts',         to: '/accounts',        icon: ArrowsRightLeftIcon,color: '#22d3ee' },
  { label: 'Flows',            to: '/flows',           icon: CpuChipIcon,        color: '#34d399' },
  { label: 'AS2 Partners',     to: '/as2-partnerships',icon: ArrowPathIcon,      color: '#fbbf24' },
  { label: 'Journey',          to: '/journey',         icon: ArrowTrendingUpIcon,color: '#f87171' },
  { label: 'Audit Logs',       to: '/logs',            icon: ClockIcon,          color: '#94a3b8' },
  { label: 'Flow Fabric',      to: '/operations/fabric',  icon: Squares2X2Icon,          color: '#8b5cf6' },
  { label: 'Activity Monitor', to: '/operations/activity',icon: RectangleStackIcon,      color: '#22d3ee' },
  { label: 'Sentinel',         to: '/sentinel',           icon: ShieldCheckIcon,         color: '#f87171' },
  { label: 'EDI Convert',      to: '/edi',                icon: DocumentMagnifyingGlassIcon, color: '#34d399' },
  { label: 'Keystore',         to: '/keystore',           icon: KeyIcon,                 color: '#8b5cf6' },
  { label: 'Storage Manager',  to: '/storage',            icon: CircleStackIcon,         color: '#22d3ee' },
  { label: 'File Manager',     to: '/file-manager',       icon: FolderIcon,              color: '#34d399' },
]

/* Friendly labels for the service health grid (keys come from ServiceContext SERVICE_HEALTH_ENDPOINTS) */
const SERVICE_LABELS = {
  onboarding:   'Onboarding',
  config:       'Config',
  sftp:         'SFTP',
  ftp:          'FTP',
  ftpWeb:       'FTP Web',
  gateway:      'Gateway',
  encryption:   'Encryption',
  forwarder:    'Forwarder',
  dmz:          'DMZ Proxy',
  license:      'License',
  analytics:    'Analytics',
  aiEngine:     'AI Engine',
  screening:    'Screening',
  keystore:     'Keystore',
  sentinel:     'Sentinel',
  ediConverter: 'EDI Converter',
  notification: 'Notification',
  storage:      'Storage',
  as2:          'AS2',
}

function getGreeting() {
  const h = new Date().getHours()
  if (h < 5)  return 'Still up?'
  if (h < 12) return 'Good morning'
  if (h < 17) return 'Good afternoon'
  return 'Good evening'
}

// DarkTooltip lives inside DashboardVolumeChart.jsx / DashboardProtocolPie.jsx
// now so it can import from recharts in the lazy chunk instead of the main
// bundle. Don't re-add it here.

/* KPI tile — supports optional `to` prop for clickable navigation */
function KpiTile({ label, value, icon: Icon, color, sub, to, navigate }) {
  const handleClick = () => { if (to && navigate) navigate(to) }
  return (
    <div
      className={`flex items-center gap-3 p-4 rounded-xl transition-all duration-200 group ${to ? 'cursor-pointer' : 'cursor-default'}`}
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
      onMouseEnter={e => { e.currentTarget.style.borderColor = `${color}44`; e.currentTarget.style.boxShadow = `0 0 20px ${color}18` }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'rgb(var(--border))'; e.currentTarget.style.boxShadow = 'none' }}
      onClick={handleClick}
    >
      <div className="p-2 rounded-lg flex-shrink-0 transition-transform duration-200 group-hover:scale-110"
        style={{ background: `${color}18` }}>
        <Icon className="w-4 h-4" style={{ color }} />
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {label}
        </p>
        <p className="font-mono font-bold text-xl leading-tight" style={{ color: 'rgb(var(--tx-primary))' }}>
          {value}
        </p>
        {sub && <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>{sub}</p>}
      </div>
      {to && (
        <ArrowTopRightOnSquareIcon className="w-3.5 h-3.5 opacity-0 group-hover:opacity-60 transition-opacity flex-shrink-0" style={{ color }} />
      )}
    </div>
  )
}

/* Live gauge pill — a single real-time counter */
function LivePill({ label, value, color, pulse }) {
  return (
    <div className="flex items-center gap-2">
      <div className="relative flex-shrink-0">
        <div className="w-1.5 h-1.5 rounded-full" style={{ background: color }} />
        {pulse && (
          <div
            className="absolute inset-0 rounded-full animate-ping"
            style={{ background: color, opacity: 0.5 }}
          />
        )}
      </div>
      <span
        className="font-mono font-bold text-sm"
        style={{ color, fontFamily: "'JetBrains Mono', monospace" }}
      >
        {value ?? '—'}
      </span>
      <span className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>{label}</span>
    </div>
  )
}

/* Live activity strip — agents + flows + refresh cadence indicator */
function LiveActivityStrip({ agentsData, flowStats, agentsLoading, flowsLoading, agentsError, flowsError }) {
  const agentsRunning = agentsData?.agentsRunning ?? 0
  const agentsTotal   = (agentsData?.agentsRunning ?? 0) + (agentsData?.agentsIdle ?? 0) +
                        (agentsData?.agentsInError ?? 0) + (agentsData?.agentsDisabled ?? 0)
  const processing    = flowStats?.processing ?? 0
  const pending       = flowStats?.pending ?? 0
  const paused        = flowStats?.paused ?? 0
  const failed        = flowStats?.failed ?? 0

  const isLoading = agentsLoading || flowsLoading
  const hasError  = agentsError && flowsError

  return (
    <div
      className="flex items-center gap-5 px-4 py-2.5 rounded-xl overflow-x-auto"
      style={{
        background: 'rgb(var(--surface))',
        border: '1px solid rgb(var(--border))',
      }}
    >
      {/* LIVE badge */}
      <div
        className="flex items-center gap-1.5 px-2 py-0.5 rounded-full flex-shrink-0"
        style={{ background: 'rgb(239 68 68 / 0.15)', border: '1px solid rgb(239 68 68 / 0.3)' }}
      >
        <span
          className="w-1.5 h-1.5 rounded-full animate-pulse"
          style={{ background: '#ef4444' }}
        />
        <span className="text-[10px] font-bold tracking-widest uppercase" style={{ color: '#ef4444' }}>
          Live
        </span>
      </div>

      <div className="w-px h-4 flex-shrink-0" style={{ background: 'rgb(var(--border))' }} />

      {isLoading ? (
        <span className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>Loading live data…</span>
      ) : hasError ? (
        <span className="text-xs" style={{ color: '#f87171' }}>Live data unavailable</span>
      ) : (
        <>
          <LivePill
            label={`/ ${agentsTotal} AI agents`}
            value={agentsRunning}
            color={agentsRunning > 0 ? '#8b5cf6' : '#52525b'}
            pulse={agentsRunning > 0}
          />

          <div className="w-px h-4 flex-shrink-0" style={{ background: 'rgb(var(--border))' }} />

          <LivePill
            label="flows processing"
            value={processing}
            color={processing > 0 ? '#22d3ee' : '#52525b'}
            pulse={processing > 0}
          />
          <LivePill
            label="flows pending"
            value={pending}
            color={pending > 0 ? '#fbbf24' : '#52525b'}
            pulse={false}
          />
          {paused > 0 && (
            <LivePill label="awaiting approval" value={paused} color="#f59e0b" pulse />
          )}
          {failed > 0 && (
            <LivePill label="failed" value={failed} color="#ef4444" pulse={false} />
          )}
        </>
      )}

      {/* Refresh cadence */}
      <span className="ml-auto text-[10px] flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }}>
        ↻ 5 s
      </span>
    </div>
  )
}

/* ────────────────────────────────────────────────────────────────────────
   Panel A — Flow Fabric strip
   ──────────────────────────────────────────────────────────────────────── */
function FabricKpiCard({ to, label, value, icon: Icon, color, loading, error }) {
  const display = loading ? '…' : error ? '—' : (value ?? '—')
  return (
    <Link
      to={to}
      className="flex items-center gap-3 p-4 rounded-xl transition-all duration-200 group"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
      onMouseEnter={e => { e.currentTarget.style.borderColor = `${color}44`; e.currentTarget.style.boxShadow = `0 0 18px ${color}18` }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'rgb(var(--border))'; e.currentTarget.style.boxShadow = 'none' }}
    >
      <div className="p-2 rounded-lg flex-shrink-0 transition-transform duration-200 group-hover:scale-110"
        style={{ background: `${color}18` }}>
        <Icon className="w-4 h-4" style={{ color }} />
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {label}
        </p>
        <p className="font-mono font-bold text-xl leading-tight" style={{ color: 'rgb(var(--tx-primary))' }}>
          {display}
        </p>
      </div>
      <ArrowRightIcon className="w-3.5 h-3.5 opacity-0 group-hover:opacity-60 transition-opacity flex-shrink-0" style={{ color }} />
    </Link>
  )
}

function FlowFabricStrip() {
  // The fabric strip is a cosmetic snapshot — if the analytics service
  // isn't ready yet (fast page load during boot), these queries shouldn't
  // toast. meta.silent opts them out of the global onError handler.
  const queuesQ = useQuery({
    queryKey: ['dash-fabric-queues'],
    queryFn: getFabricQueues,
    meta: { silent: true }, refetchInterval: 10_000,
    retry: 0,
    staleTime: 8_000,
    meta: { silent: true },
  })
  const instancesQ = useQuery({
    queryKey: ['dash-fabric-instances'],
    queryFn: getFabricInstances,
    meta: { silent: true }, refetchInterval: 10_000,
    retry: 0,
    staleTime: 8_000,
    meta: { silent: true },
  })
  const stuckQ = useQuery({
    queryKey: ['dash-fabric-stuck'],
    queryFn: () => getFabricStuck({ page: 0, size: 1 }),
    meta: { silent: true }, refetchInterval: 10_000,
    retry: 0,
    staleTime: 8_000,
    meta: { silent: true },
  })
  const latencyQ = useQuery({
    queryKey: ['dash-fabric-latency'],
    queryFn: () => getFabricLatency({ hours: 1, sample: 2000 }),
    meta: { silent: true }, refetchInterval: 10_000,
    retry: 0,
    staleTime: 8_000,
    meta: { silent: true },
  })

  const inProgress = (() => {
    const byStep = queuesQ.data?.inProgressByStepType
    if (!byStep) return null
    if (Array.isArray(byStep)) {
      return byStep.reduce((sum, row) => sum + (row?.count ?? row?.value ?? 0), 0)
    }
    if (typeof byStep === 'object') {
      return Object.values(byStep).reduce((sum, v) => sum + (typeof v === 'number' ? v : (v?.count ?? 0)), 0)
    }
    return null
  })()

  const activeInstances = (() => {
    const a = instancesQ.data?.active
    if (Array.isArray(a)) return a.length
    if (typeof a === 'number') return a
    return null
  })()

  const stuckTotal = stuckQ.data?.totalElements ?? stuckQ.data?.total ?? null

  const p95 = (() => {
    const byStep = latencyQ.data?.byStepType
    if (!byStep) return null
    const rows = Array.isArray(byStep) ? byStep : Object.values(byStep)
    if (!rows.length) return null
    const max = rows.reduce((m, r) => {
      const v = r?.p95 ?? r?.p95Ms ?? 0
      return v > m ? v : m
    }, 0)
    return max ? `${max.toLocaleString()} ms` : '0 ms'
  })()

  const stuckColor = (stuckTotal && stuckTotal > 0) ? '#ef4444' : '#22c55e'

  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
      <FabricKpiCard
        to="/operations/fabric"
        label="In-Progress"
        value={inProgress?.toLocaleString?.() ?? inProgress}
        icon={Squares2X2Icon}
        color="#8b5cf6"
        loading={queuesQ.isLoading}
        error={queuesQ.isError}
      />
      <FabricKpiCard
        to="/operations/fabric"
        label="Active Instances"
        value={activeInstances?.toLocaleString?.() ?? activeInstances}
        icon={CpuChipIcon}
        color="#22d3ee"
        loading={instancesQ.isLoading}
        error={instancesQ.isError}
      />
      <FabricKpiCard
        to="/operations/activity?stuckOnly=true"
        label="Stuck Files"
        value={stuckTotal?.toLocaleString?.() ?? stuckTotal}
        icon={FireIcon}
        color={stuckColor}
        loading={stuckQ.isLoading}
        error={stuckQ.isError}
      />
      <FabricKpiCard
        to="/operations/fabric"
        label="p95 Latency"
        value={p95}
        icon={ClockOutline}
        color="#fbbf24"
        loading={latencyQ.isLoading}
        error={latencyQ.isError}
      />
    </div>
  )
}

/* ────────────────────────────────────────────────────────────────────────
   Panel B — Open Sentinel Findings preview
   ──────────────────────────────────────────────────────────────────────── */
const SEV_ORDER = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, INFO: 4 }
const SEV_BADGE_COLOR = {
  CRITICAL: { bg: 'rgb(127 29 29 / 0.35)', fg: '#fca5a5', border: '#7f1d1d' },
  HIGH:     { bg: 'rgb(154 52 18 / 0.35)', fg: '#fdba74', border: '#9a3412' },
  MEDIUM:   { bg: 'rgb(133 77 14 / 0.35)', fg: '#fcd34d', border: '#854d0e' },
  LOW:      { bg: 'rgb(30 58 138 / 0.35)', fg: '#93c5fd', border: '#1e3a8a' },
  INFO:     { bg: 'rgb(64 64 64 / 0.35)',  fg: '#d4d4d8', border: '#3f3f46' },
}

function SentinelFindingsPreview() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['dash-sentinel-findings'],
    queryFn: () => sentinelApi.getFindings({ status: 'OPEN', size: 5, sortBy: 'severity' }),
    meta: { silent: true }, refetchInterval: 30_000,
    retry: 1,
    staleTime: 20_000,
  })

  const raw = Array.isArray(data?.content) ? data.content
            : Array.isArray(data)         ? data
            : []
  const findings = [...raw].sort((a, b) => (SEV_ORDER[a.severity] ?? 9) - (SEV_ORDER[b.severity] ?? 9)).slice(0, 5)

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <p className="section-title flex items-center gap-2">
          <BellAlertIcon className="w-4 h-4" style={{ color: '#f87171' }} />
          Open Findings
        </p>
        <NavLink to="/sentinel" className="text-[10px] font-medium hover:underline" style={{ color: 'rgb(var(--accent))' }}>
          View All
        </NavLink>
      </div>

      {isLoading ? (
        <div className="py-6 text-center text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
          Loading findings…
        </div>
      ) : isError ? (
        <div className="py-6 text-center">
          <ExclamationTriangleIcon className="w-6 h-6 mx-auto mb-1" style={{ color: '#f87171' }} />
          <p className="text-xs" style={{ color: '#f87171' }}>Sentinel unavailable</p>
          <NavLink to="/sentinel" className="text-[10px] hover:underline" style={{ color: 'rgb(var(--accent))' }}>
            Open Sentinel
          </NavLink>
        </div>
      ) : findings.length === 0 ? (
        <div className="py-6 text-center">
          <CheckCircleIcon className="w-7 h-7 mx-auto mb-1" style={{ color: '#22c55e' }} />
          <p className="text-xs font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>All clear</p>
          <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
            No open findings — platform is healthy.
          </p>
          <NavLink to="/sentinel" className="text-[10px] hover:underline inline-block mt-1" style={{ color: 'rgb(var(--accent))' }}>
            Open Sentinel →
          </NavLink>
        </div>
      ) : (
        <div className="space-y-1.5">
          {findings.map(f => {
            const sev = SEV_BADGE_COLOR[f.severity] || SEV_BADGE_COLOR.INFO
            return (
              <Link
                key={f.id}
                to={`/sentinel?findingId=${encodeURIComponent(f.id)}`}
                className="flex items-start gap-2 p-2 rounded-lg transition-colors"
                style={{ background: 'rgb(var(--hover))', border: '1px solid transparent' }}
                onMouseEnter={e => { e.currentTarget.style.borderColor = 'rgb(var(--border))' }}
                onMouseLeave={e => { e.currentTarget.style.borderColor = 'transparent' }}
              >
                <span
                  className="text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wide flex-shrink-0 mt-0.5"
                  style={{ background: sev.bg, color: sev.fg, border: `1px solid ${sev.border}` }}
                >
                  {f.severity || 'INFO'}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-medium truncate" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {f.title || 'Untitled finding'}
                  </p>
                  {f.description && (
                    <p className="text-[10px] truncate" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {f.description}
                    </p>
                  )}
                  {f.affectedService && (
                    <p className="text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {f.affectedService}
                    </p>
                  )}
                </div>
              </Link>
            )
          })}
        </div>
      )}
    </div>
  )
}

/* ────────────────────────────────────────────────────────────────────────
   Panel D — Pipeline Health (Phase 6: every stage measured)
   ──────────────────────────────────────────────────────────────────────── */
function PipelineHealthCard() {
  const { data, isLoading } = useQuery({
    queryKey: ['pipeline-health'],
    queryFn: () => onboardingApi.get('/api/pipeline/health').then(r => r.data),
    meta: { silent: true }, refetchInterval: 10000,
    retry: false,
  })

  if (isLoading || !data) return null

  const seda = data.seda || {}
  const writers = data.batchWriters || {}
  const rules = data.ruleEngine || {}
  const cache = data.partnerCache || {}

  const Stat = ({ label, value, sub }) => (
    <div className="text-center">
      <p className="text-lg font-bold" style={{ color: 'rgb(var(--accent))' }}>{value ?? '-'}</p>
      <p className="text-[10px] opacity-60">{label}</p>
      {sub && <p className="text-[9px] opacity-40">{sub}</p>}
    </div>
  )

  const SedaStage = ({ name, stats }) => {
    if (!stats) return null
    const saturation = stats.queueSize / (stats.queueSize + stats.queueRemaining + 1) * 100
    const color = saturation > 80 ? '#ef4444' : saturation > 50 ? '#fbbf24' : '#22c55e'
    return (
      <div className="flex items-center gap-3 py-1.5">
        <div className="w-16 text-[10px] font-medium uppercase opacity-70">{name}</div>
        <div className="flex-1 bg-white/5 rounded-full h-2 relative">
          <div className="h-full rounded-full transition-all" style={{ width: `${Math.min(saturation, 100)}%`, backgroundColor: color }} />
        </div>
        <div className="text-[9px] opacity-50 w-20 text-right">
          {stats.processed?.toLocaleString()} processed
        </div>
      </div>
    )
  }

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <p className="section-title flex items-center gap-2">
          <BoltIcon className="w-4 h-4" style={{ color: '#a78bfa' }} />
          Pipeline Health
        </p>
        {data.version && (
          <span className="text-[10px] px-2 py-0.5 rounded-full font-mono"
                style={{ background: 'rgba(var(--accent), 0.1)', color: 'rgb(var(--accent))' }}>
            v{data.version}
          </span>
        )}
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-5 gap-3 mb-4">
        <Stat label="Rules" value={rules.ruleCount} sub={`${rules.bucketCount || 0} buckets`} />
        <Stat label="Matched" value={rules.totalMatches?.toLocaleString()} sub={`${rules.totalUnmatched || 0} unmatched`} />
        <Stat label="Record Buffer" value={writers.records?.pending || 0} sub={`${(writers.records?.flushed || 0).toLocaleString()} flushed`} />
        <Stat label="Snapshot Buffer" value={writers.snapshots?.pending || 0} sub={`${(writers.snapshots?.flushed || 0).toLocaleString()} flushed`} />
        <Stat label="Partner Cache" value={cache.size || 0} sub={cache.hitRate ? `hit rate: ${cache.hitRate}` : 'entries (L1)'} />
      </div>

      {(seda.intake || seda.pipeline || seda.delivery) && (
        <div className="border-t border-white/5 pt-3">
          <p className="text-[10px] font-medium opacity-50 mb-2">SEDA STAGES</p>
          <SedaStage name="Intake" stats={seda.intake} />
          <SedaStage name="Pipeline" stats={seda.pipeline} />
          <SedaStage name="Delivery" stats={seda.delivery} />
        </div>
      )}
    </div>
  )
}

/* ────────────────────────────────────────────────────────────────────────
   Panel C — Service Health grid
   ──────────────────────────────────────────────────────────────────────── */
function ServiceHealthGrid() {
  const svcCtx = useServices()
  const services = svcCtx?.services || {}
  const serviceList = svcCtx?.serviceList || Object.keys(SERVICE_LABELS)

  // Merge list: union of known endpoints and anything present in the map
  const keys = Array.from(new Set([...(serviceList || []), ...Object.keys(services || {})]))
    .filter(k => k !== 'core')

  const statusOf = (k) => {
    const v = services[k]
    if (v === true)  return 'up'
    if (v === false) return 'down'
    return 'unknown'
  }
  const COLOR = { up: '#22c55e', down: '#ef4444', degraded: '#fbbf24', unknown: '#71717a' }

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <p className="section-title flex items-center gap-2">
          <ServerIcon className="w-4 h-4" style={{ color: '#22d3ee' }} />
          Service Health
        </p>
        <NavLink to="/services" className="text-[10px] font-medium hover:underline" style={{ color: 'rgb(var(--accent))' }}>
          View All
        </NavLink>
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
        {keys.map(k => {
          const status = statusOf(k)
          const color  = COLOR[status]
          const label  = SERVICE_LABELS[k] || k
          return (
            <Link
              key={k}
              to="/services"
              className="flex items-center gap-2 px-3 py-2 rounded-lg transition-colors"
              style={{ background: 'rgb(var(--hover))', border: '1px solid rgb(var(--border))' }}
              onMouseEnter={e => { e.currentTarget.style.borderColor = `${color}66` }}
              onMouseLeave={e => { e.currentTarget.style.borderColor = 'rgb(var(--border))' }}
              title={`${label} — ${status}`}
            >
              <span
                className="w-2 h-2 rounded-full flex-shrink-0"
                style={{
                  background: color,
                  boxShadow: status === 'up' ? `0 0 6px ${color}` : 'none',
                }}
              />
              <span className="text-[11px] truncate" style={{ color: 'rgb(var(--tx-secondary))' }}>
                {label}
              </span>
            </Link>
          )
        })}
        {keys.length === 0 && (
          <div className="col-span-full py-4 text-center text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
            No services detected yet…
          </div>
        )}
      </div>
    </div>
  )
}

/* ────────────────────────────────────────────────────────────────────────
   Panel E — Recent Activity strip
   ──────────────────────────────────────────────────────────────────────── */
function RecentActivityStrip() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['dash-recent-activity'],
    queryFn: () => onboardingApi
      .get('/api/activity-monitor', { params: { page: 0, size: 5, sortBy: 'uploadedAt', sortDir: 'DESC' } })
      .then(r => r.data),
    meta: { silent: true }, refetchInterval: 5_000,
    retry: 0,
    staleTime: 4_000,
    // Cosmetic strip — the page itself handles isError via an inline
    // message, so the global toast handler is redundant noise.
    meta: { silent: true },
  })

  const rows = Array.isArray(data?.content) ? data.content
            : Array.isArray(data)         ? data
            : []

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <p className="section-title flex items-center gap-2">
          <ClockIcon className="w-4 h-4" style={{ color: '#22d3ee' }} />
          Recent Transfers
        </p>
        <NavLink to="/operations/activity" className="text-[10px] font-medium hover:underline" style={{ color: 'rgb(var(--accent))' }}>
          View All
        </NavLink>
      </div>
      {isLoading ? (
        <div className="py-4 text-center text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>Loading…</div>
      ) : isError ? (
        <div className="py-4 text-center text-xs" style={{ color: '#f87171' }}>Activity monitor unavailable</div>
      ) : rows.length === 0 ? (
        <div className="py-4 text-center">
          <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>No recent transfers</p>
          <NavLink to="/operations/activity" className="text-[10px] hover:underline" style={{ color: 'rgb(var(--accent))' }}>
            Open Activity Monitor →
          </NavLink>
        </div>
      ) : (
        <div className="space-y-1">
          {rows.map(r => {
            const trackId = r.trackId || r.id
            const filename = r.filename || r.fileName || r.originalFilename || '—'
            const status = r.status || r.state || '—'
            const when = r.uploadedAt || r.createdAt || r.timestamp
            const statusColor = /FAIL|ERROR|STUCK/i.test(status) ? '#f87171'
                              : /COMPLETE|SUCCESS|DONE/i.test(status) ? '#22c55e'
                              : '#fbbf24'
            return (
              <Link
                key={trackId || `${filename}-${when}`}
                to={trackId ? `/operations/journey?trackId=${encodeURIComponent(trackId)}` : '/operations/activity'}
                className="flex items-center gap-2 px-2 py-1.5 rounded-lg transition-colors"
                style={{ background: 'rgb(var(--hover))' }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--border))' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'rgb(var(--hover))' }}
              >
                <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: statusColor }} />
                <span className="text-[11px] font-mono truncate flex-1" style={{ color: 'rgb(var(--tx-primary))' }}>
                  {filename}
                </span>
                <span className="text-[10px] uppercase tracking-wider flex-shrink-0" style={{ color: statusColor }}>
                  {status}
                </span>
                <span className="text-[10px] flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {when ? format(new Date(when), 'HH:mm:ss') : ''}
                </span>
              </Link>
            )
          })}
        </div>
      )}
    </div>
  )
}

export default function Dashboard() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: getDashboard,
    meta: { silent: true }, refetchInterval: 30000,
  })
  const { data: predictions } = useQuery({
    queryKey: ['predictions'],
    queryFn: getPredictions,
  })
  const {
    data: agentsData, isLoading: agentsLoading, isError: agentsError,
  } = useQuery({
    queryKey: ['agents-dashboard'],
    queryFn: getAgentsDashboard,
    meta: { silent: true }, refetchInterval: 15_000,   // was 5s — AI agent states change slowly
    staleTime: 12_000,
    retry: false,
  })
  const {
    data: flowStats, isLoading: flowsLoading, isError: flowsError,
  } = useQuery({
    queryKey: ['flow-live-stats'],
    queryFn: getFlowLiveStats,
    meta: { silent: true }, refetchInterval: 10_000,   // was 5s — halves DB load while keeping "live" feel
    staleTime: 8_000,
    retry: false,
  })

  if (isLoading) return <LoadingSpinner text="Loading dashboard..." />

  const successRate   = ((dashboard?.successRateToday || 1) * 100).toFixed(1)
  const protocolData  = Object.entries(dashboard?.transfersByProtocol || {}).map(([name, value]) => ({ name, value }))
  const hasAlerts     = (dashboard?.alerts?.length || 0) > 0
  const hasRecs       = predictions?.some(p => p.recommendedReplicas > 1)
  const transferData  = dashboard?.transfersPerHour || []

  return (
    <div className="space-y-5 animate-page">

      {/* ── Panel A: Flow Fabric strip ── */}
      <FlowFabricStrip />

      {/* ── Top: greeting + timestamp ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
            {getGreeting()},{' '}
            <span style={{ color: 'rgb(var(--accent))' }}>
              {user?.name || user?.email?.split('@')[0] || 'Admin'}
            </span>
          </h1>
          <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
            {format(new Date(), "EEEE, MMMM d, yyyy 'at' HH:mm")}
          </p>
        </div>

        {/* Platform status pill */}
        <div
          className="flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium"
          style={{
            background: hasAlerts ? 'rgb(127 29 29 / 0.35)' : 'rgb(20 83 45 / 0.35)',
            border: `1px solid ${hasAlerts ? '#7f1d1d' : '#14532d'}`,
            color: hasAlerts ? '#f87171' : '#4ade80',
          }}
        >
          <span
            className="w-1.5 h-1.5 rounded-full"
            style={{
              background: hasAlerts ? '#f87171' : '#22c55e',
              animation: 'pulse-dot 2.2s ease-in-out infinite',
            }}
          />
          {hasAlerts ? `${dashboard.alerts.length} alert${dashboard.alerts.length !== 1 ? 's' : ''}` : 'All systems operational'}
        </div>
      </div>

      {/* ── KPI Row ── */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
        <KpiTile
          label="Transfers Today"
          value={(dashboard?.totalTransfersToday || 0).toLocaleString()}
          icon={ArrowUpTrayIcon}
          color="#8b5cf6"
          to="/activity-monitor"
          navigate={navigate}
        />
        <KpiTile
          label="Success Rate"
          value={`${successRate}%`}
          icon={CheckCircleIcon}
          color="#22c55e"
          sub={dashboard?.totalTransfersToday ? `${Math.round(dashboard.totalTransfersToday * (dashboard.successRateToday || 1))} succeeded` : undefined}
          to="/activity-monitor?status=FAILED"
          navigate={navigate}
        />
        <KpiTile
          label="Data Moved"
          value={`${(dashboard?.totalGbToday || 0).toFixed(2)} GB`}
          icon={ServerIcon}
          color="#22d3ee"
          to="/activity-monitor"
          navigate={navigate}
        />
        <KpiTile
          label="Last Hour"
          value={(dashboard?.totalTransfersLastHour || 0).toLocaleString()}
          icon={ChartBarIcon}
          color="#fbbf24"
          to="/activity-monitor"
          navigate={navigate}
        />
        <KpiTile
          label="Protocols Active"
          value={protocolData.length || 0}
          icon={BoltIcon}
          color="#f87171"
          sub={protocolData.map(p => p.name).join(' · ') || 'None yet'}
          to="/flows"
          navigate={navigate}
        />
      </div>

      {/* ── Live Activity Strip ── */}
      <LiveActivityStrip
        agentsData={agentsData}
        flowStats={flowStats}
        agentsLoading={agentsLoading}
        flowsLoading={flowsLoading}
        agentsError={agentsError}
        flowsError={flowsError}
      />

      {/* ── Alerts ── */}
      {hasAlerts && (
        <div
          className="rounded-xl p-4"
          style={{
            background: 'rgb(127 29 29 / 0.25)',
            border: '1px solid rgb(127 29 29 / 0.5)',
          }}
        >
          <div className="flex items-center justify-between">
            <h3 className="font-semibold text-sm flex items-center gap-2" style={{ color: '#f87171' }}>
              <ExclamationTriangleIcon className="w-4 h-4" />
              Active Alerts ({dashboard.alerts.length})
            </h3>
            <NavLink to="/sentinel" className="text-xs font-medium hover:underline" style={{ color: '#fca5a5' }}>
              View All in Sentinel
            </NavLink>
          </div>
          <div className="mt-2 space-y-1">
            {dashboard.alerts.map((alert, i) => (
              <div key={i} className="flex items-center gap-2 text-xs" style={{ color: '#fca5a5' }}>
                <span className="font-mono font-semibold">{alert.ruleName}</span>
                <span>{' — '}{alert.serviceType}: {alert.metric} = {alert.currentValue?.toFixed(3)} (threshold: {alert.threshold})</span>
                {alert.trackId && (
                  <button
                    onClick={() => navigate(`/journey?trackId=${encodeURIComponent(alert.trackId)}`)}
                    className="font-mono text-blue-400 hover:text-blue-300 hover:underline flex-shrink-0"
                  >
                    {alert.trackId.substring(0, 8)}...
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Main Grid: Chart + Quick Actions ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* Transfer Volume Chart */}
        <div className="card lg:col-span-2">
          <div className="flex items-center justify-between mb-4">
            <p className="section-title">Transfer Volume</p>
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-medium px-2 py-0.5 rounded-full"
                style={{ background: 'rgb(var(--accent) / 0.12)', color: 'rgb(var(--accent))' }}>
                Last 24 hours
              </span>
              <NavLink to="/activity-monitor" className="text-[10px] font-medium hover:underline" style={{ color: 'rgb(var(--accent))' }}>
                View All
              </NavLink>
            </div>
          </div>

          {transferData.length > 0 ? (
            <Suspense fallback={<ChartFallback height={220} />}>
              <DashboardVolumeChart data={transferData} />
            </Suspense>
          ) : (
            <div className="h-52 flex flex-col items-center justify-center gap-2">
              <ChartBarIcon className="w-8 h-8" style={{ color: 'rgb(var(--tx-muted))' }} />
              <p className="text-sm" style={{ color: 'rgb(var(--tx-muted))' }}>No data yet</p>
              <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>Transfers will appear after the first hour</p>
            </div>
          )}
        </div>

        {/* Quick Actions */}
        <div className="card">
          <p className="section-title mb-4">Quick Access</p>
          <div className="grid grid-cols-2 gap-2">
            {quickActions.map(action => (
              <NavLink
                key={action.to}
                to={action.to}
                className="flex flex-col items-center gap-2 p-3 rounded-xl text-center transition-all duration-150 group"
                style={{ background: 'rgb(var(--hover))', border: '1px solid transparent' }}
                onMouseEnter={e => {
                  e.currentTarget.style.background = `${action.color}14`
                  e.currentTarget.style.borderColor = `${action.color}40`
                  const icon = e.currentTarget.querySelector('.__icon')
                  if (icon) icon.style.transform = 'scale(1.15)'
                }}
                onMouseLeave={e => {
                  e.currentTarget.style.background = 'rgb(var(--hover))'
                  e.currentTarget.style.borderColor = 'transparent'
                  const icon = e.currentTarget.querySelector('.__icon')
                  if (icon) icon.style.transform = 'scale(1)'
                }}
              >
                <div
                  className="__icon p-2 rounded-lg transition-transform duration-150"
                  style={{ background: `${action.color}20` }}
                >
                  <action.icon className="w-4 h-4" style={{ color: action.color }} />
                </div>
                <span className="text-[11px] font-medium leading-tight" style={{ color: 'rgb(var(--tx-secondary))' }}>
                  {action.label}
                </span>
              </NavLink>
            ))}
          </div>
        </div>
      </div>

      {/* ── Panels B + E: Sentinel Findings + Recent Activity ── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <SentinelFindingsPreview />
        <RecentActivityStrip />
      </div>

      {/* ── Panel C: Service Health grid ── */}
      <ServiceHealthGrid />

      {/* ── Panel D: Pipeline Health (Phase 6 — observability) ── */}
      <PipelineHealthCard />

      {/* ── Bottom Grid: Protocol + Recommendations ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* Protocol Breakdown */}
        <div className="card">
          <div className="flex items-center justify-between mb-3">
            <p className="section-title">By Protocol</p>
            <NavLink to="/analytics" className="text-[10px] font-medium hover:underline" style={{ color: 'rgb(var(--accent))' }}>
              View All
            </NavLink>
          </div>
          {protocolData.length > 0 ? (
            <>
              <Suspense fallback={<ChartFallback height={150} />}>
                <DashboardProtocolPie data={protocolData} />
              </Suspense>
              <div className="space-y-2 mt-2">
                {protocolData.map((p, i) => (
                  <div key={p.name} className="flex items-center justify-between text-xs">
                    <div className="flex items-center gap-2">
                      <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: NEON[i % NEON.length] }} />
                      <span style={{ color: 'rgb(var(--tx-secondary))' }}>{p.name}</span>
                    </div>
                    <span className="font-mono font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
                      {p.value.toLocaleString()}
                    </span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="h-40 flex flex-col items-center justify-center gap-2">
              <ArrowsRightLeftIcon className="w-7 h-7" style={{ color: 'rgb(var(--tx-muted))' }} />
              <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>No protocol data yet</p>
            </div>
          )}
        </div>

        {/* Scaling Recommendations */}
        {hasRecs ? (
          <div className="card lg:col-span-2">
            <p className="section-title mb-3 flex items-center gap-2">
              <ArrowTrendingUpIcon className="w-4 h-4" style={{ color: '#fbbf24' }} />
              Scaling Recommendations
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
              {predictions.filter(p => p.recommendedReplicas > 1).map(rec => (
                <div
                  key={rec.serviceType}
                  className="p-3 rounded-xl"
                  style={{
                    background: 'rgb(120 53 15 / 0.20)',
                    border: '1px solid rgb(120 53 15 / 0.40)',
                  }}
                >
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                      {rec.serviceType}
                    </span>
                    <span className={`badge ${rec.trend === 'INCREASING' ? 'badge-red' : 'badge-yellow'}`}>
                      {rec.trend}
                    </span>
                  </div>
                  <p className="text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                    Predicted: <span className="font-mono font-bold" style={{ color: '#fbbf24' }}>{Math.round(rec.predictedLoad24h)}</span> transfers
                  </p>
                  <p className="text-xs font-semibold mt-1" style={{ color: '#fbbf24' }}>
                    → {rec.recommendedReplicas} replicas
                  </p>
                  {rec.reason && (
                    <p className="text-[10px] mt-1.5 leading-snug" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {rec.reason}
                    </p>
                  )}
                </div>
              ))}
            </div>
          </div>
        ) : (
          <div className="card lg:col-span-2 flex items-center justify-center">
            <div className="text-center">
              <ShieldCheckIcon className="w-8 h-8 mx-auto mb-2" style={{ color: '#22c55e' }} />
              <p className="text-sm font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>Platform Healthy</p>
              <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
                No scaling recommendations — all services within normal parameters.
              </p>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
