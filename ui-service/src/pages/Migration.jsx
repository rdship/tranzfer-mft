import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { format, formatDistanceToNow } from 'date-fns'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import {
  ArrowPathIcon, ArrowsRightLeftIcon, CheckCircleIcon,
  ChartBarIcon, ClockIcon, ExclamationTriangleIcon,
  MagnifyingGlassIcon, ServerIcon, ShieldCheckIcon,
  SignalIcon, UserGroupIcon, XMarkIcon, PlayIcon,
  EyeIcon, StopIcon, ArrowUturnLeftIcon, ChevronRightIcon,
  FunnelIcon, BoltIcon, GlobeAltIcon, LinkIcon,
  DocumentCheckIcon, WrenchIcon, InformationCircleIcon,
  CheckIcon,
} from '@heroicons/react/24/outline'
import * as migrationApi from '../api/migration'

/* ═══════════════════════════════════════════════════════════
   CONSTANTS
   ═══════════════════════════════════════════════════════════ */

const TABS = [
  { key: 'overview', label: 'Overview', icon: ChartBarIcon },
  { key: 'partners', label: 'Partners', icon: UserGroupIcon },
  { key: 'detail',   label: 'Partner Detail', icon: EyeIcon },
]

const STATUS_ORDER = ['NOT_STARTED', 'DISCOVERED', 'IN_PROGRESS', 'SHADOW_MODE', 'VERIFIED', 'COMPLETED']

const STATUS_BADGE = {
  NOT_STARTED:  'badge-gray',
  DISCOVERED:   'badge-blue',
  IN_PROGRESS:  'badge-yellow',
  SHADOW_MODE:  'badge-purple',
  VERIFIED:     'badge-green',
  COMPLETED:    'badge-teal',
}

const STATUS_LABEL = {
  NOT_STARTED:  'Not Started',
  DISCOVERED:   'Discovered',
  IN_PROGRESS:  'In Progress',
  SHADOW_MODE:  'Shadow Mode',
  VERIFIED:     'Verified',
  COMPLETED:    'Completed',
}

const STATUS_FILTER_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'NOT_STARTED', label: 'Not Started' },
  { value: 'DISCOVERED', label: 'Discovered' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'SHADOW_MODE', label: 'Shadow Mode' },
  { value: 'VERIFIED', label: 'Verified' },
  { value: 'COMPLETED', label: 'Completed' },
]

const EVENT_BADGE = {
  STARTED:        'badge-blue',
  SHADOW_ENABLED: 'badge-purple',
  SHADOW_DISABLED:'badge-gray',
  VERIFICATION_STARTED: 'badge-yellow',
  VERIFICATION_RECORDED:'badge-green',
  COMPLETED:      'badge-teal',
  ROLLED_BACK:    'badge-red',
  DISCOVERED:     'badge-blue',
  CONNECTION:     'badge-gray',
}

const STEPPER_COLORS = {
  done:    { bg: 'rgb(20 60 40)',  border: 'rgb(40 100 70)',  text: 'rgb(120 220 160)', line: 'rgb(40 100 70)' },
  active:  { bg: 'rgb(25 30 50)',  border: 'rgb(100 140 255)', text: 'rgb(100 140 255)', line: 'rgb(var(--border))' },
  pending: { bg: 'rgb(var(--hover))', border: 'rgb(var(--border))', text: 'rgb(var(--tx-muted))', line: 'rgb(var(--border))' },
}

/* ═══════════════════════════════════════════════════════════
   HELPERS
   ═══════════════════════════════════════════════════════════ */

function fmtTime(ts) {
  if (!ts) return '--'
  try {
    return formatDistanceToNow(new Date(ts), { addSuffix: true })
  } catch { return '--' }
}

function fmtDate(ts) {
  if (!ts) return '--'
  try {
    return format(new Date(ts), 'MMM d, yyyy HH:mm')
  } catch { return '--' }
}

function pct(n, total) {
  if (!total) return '0%'
  return `${((n / total) * 100).toFixed(1)}%`
}

/* ═══════════════════════════════════════════════════════════
   SUB-COMPONENTS
   ═══════════════════════════════════════════════════════════ */

/* ── Progress Donut (SVG) ── */
function ProgressDonut({ percentage, size = 140, strokeWidth = 12 }) {
  const radius = (size - strokeWidth) / 2
  const circumference = 2 * Math.PI * radius
  const offset = circumference - (percentage / 100) * circumference
  const color = percentage >= 75 ? 'rgb(120 220 160)' : percentage >= 40 ? 'rgb(240 200 100)' : 'rgb(240 120 120)'

  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle
          cx={size / 2} cy={size / 2} r={radius}
          fill="none" stroke="rgb(var(--border))" strokeWidth={strokeWidth}
        />
        <circle
          cx={size / 2} cy={size / 2} r={radius}
          fill="none" stroke={color} strokeWidth={strokeWidth}
          strokeDasharray={circumference} strokeDashoffset={offset}
          strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.6s ease' }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="stat-number text-2xl" style={{ color }}>{percentage.toFixed(0)}%</span>
        <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>migrated</span>
      </div>
    </div>
  )
}

/* ── Stat Card ── */
function StatCard({ label, value, percentage, icon: Icon, color }) {
  return (
    <div className="card !p-4">
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-lg flex-shrink-0" style={{ background: `${color}18` }}>
          <Icon className="w-5 h-5" style={{ color }} />
        </div>
        <div className="min-w-0 flex-1">
          <p className="stat-number text-xl">{value}</p>
          <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>{label}</p>
          {percentage != null && (
            <p className="text-[10px] font-mono mt-0.5" style={{ color: 'rgb(var(--tx-secondary))' }}>{percentage}</p>
          )}
        </div>
      </div>
    </div>
  )
}

/* ── Connection Split Bar ── */
function ConnectionSplitBar({ platform, legacy }) {
  const total = (platform || 0) + (legacy || 0)
  const platformPct = total ? ((platform / total) * 100) : 0
  return (
    <div>
      <div className="flex items-center justify-between text-xs mb-2">
        <span style={{ color: 'rgb(100 140 255)' }}>Platform: {platform || 0}</span>
        <span style={{ color: 'rgb(240 180 80)' }}>Legacy: {legacy || 0}</span>
      </div>
      <div className="h-3 rounded-full overflow-hidden flex" style={{ background: 'rgb(var(--hover))' }}>
        {total > 0 && (
          <>
            <div
              className="h-full transition-all duration-500"
              style={{ width: `${platformPct}%`, background: 'rgb(100 140 255)' }}
            />
            <div
              className="h-full transition-all duration-500"
              style={{ width: `${100 - platformPct}%`, background: 'rgb(240 180 80)' }}
            />
          </>
        )}
      </div>
      <p className="text-[10px] mt-1 text-center" style={{ color: 'rgb(var(--tx-muted))' }}>
        {total > 0 ? `${platformPct.toFixed(1)}% on platform` : 'No connections in last 24h'}
      </p>
    </div>
  )
}

/* ── Event Timeline Item ── */
function EventItem({ event }) {
  const badgeClass = EVENT_BADGE[event.eventType] || 'badge-gray'
  return (
    <div className="flex gap-3 py-2.5">
      <div className="flex flex-col items-center flex-shrink-0">
        <div className="w-2 h-2 rounded-full mt-1.5" style={{ background: 'rgb(var(--accent))' }} />
        <div className="w-px flex-1 mt-1" style={{ background: 'rgb(var(--border))' }} />
      </div>
      <div className="min-w-0 flex-1 pb-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`badge ${badgeClass}`}>{event.eventType?.replace(/_/g, ' ')}</span>
          <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>{fmtTime(event.timestamp)}</span>
        </div>
        {event.details && (
          <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>{event.details}</p>
        )}
        {event.actor && (
          <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>by {event.actor}</p>
        )}
      </div>
    </div>
  )
}

/* ── Migration Stepper ── */
function MigrationStepper({ currentStatus }) {
  const currentIdx = STATUS_ORDER.indexOf(currentStatus)
  return (
    <div className="flex items-center gap-0 w-full">
      {STATUS_ORDER.map((step, i) => {
        const isDone = i < currentIdx
        const isActive = i === currentIdx
        const colors = isDone ? STEPPER_COLORS.done : isActive ? STEPPER_COLORS.active : STEPPER_COLORS.pending
        return (
          <div key={step} className="flex items-center flex-1 min-w-0">
            <div className="flex flex-col items-center flex-shrink-0">
              <div
                className="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-all"
                style={{ background: colors.bg, borderColor: colors.border, color: colors.text }}
              >
                {isDone ? <CheckIcon className="w-4 h-4" /> : i + 1}
              </div>
              <span
                className="text-[9px] font-semibold mt-1 whitespace-nowrap"
                style={{ color: colors.text }}
              >
                {STATUS_LABEL[step]}
              </span>
            </div>
            {i < STATUS_ORDER.length - 1 && (
              <div
                className="flex-1 h-0.5 mx-1 rounded transition-all"
                style={{ background: isDone ? STEPPER_COLORS.done.line : colors.line }}
              />
            )}
          </div>
        )
      })}
    </div>
  )
}

/* ═══════════════════════════════════════════════════════════
   TAB 1 — OVERVIEW DASHBOARD
   ═══════════════════════════════════════════════════════════ */

function OverviewTab({ onSelectPartner }) {
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['migration-dashboard'],
    queryFn: migrationApi.getMigrationDashboard,
    refetchInterval: 30_000,
  })

  const { data: connStats } = useQuery({
    queryKey: ['migration-connection-stats'],
    queryFn: migrationApi.getConnectionStats,
    refetchInterval: 15_000,
  })

  if (isLoading) return <LoadingSpinner text="Loading migration dashboard..." />

  const d = dashboard || {}
  const total = d.totalPartners || 0
  const completed = d.completedPartners || 0
  const completionPct = total > 0 ? (completed / total) * 100 : 0

  const stats = [
    { label: 'Total Partners',  value: total,                         pct: null,                 icon: UserGroupIcon,         color: '#8b5cf6' },
    { label: 'Not Started',     value: d.notStartedPartners || 0,     pct: pct(d.notStartedPartners, total), icon: ClockIcon,            color: '#6b7280' },
    { label: 'In Progress',     value: d.inProgressPartners || 0,     pct: pct(d.inProgressPartners, total), icon: ArrowPathIcon,        color: '#f59e0b' },
    { label: 'Shadow Mode',     value: d.shadowModePartners || 0,     pct: pct(d.shadowModePartners, total), icon: ArrowsRightLeftIcon,  color: '#a855f7' },
    { label: 'Verified',        value: d.verifiedPartners || 0,       pct: pct(d.verifiedPartners, total),   icon: ShieldCheckIcon,      color: '#22c55e' },
    { label: 'Completed',       value: completed,                     pct: pct(completed, total),            icon: CheckCircleIcon,      color: '#14b8a6' },
  ]

  const events = d.recentEvents || []

  return (
    <div className="space-y-6">
      {/* ── Row 1: Donut + Stat Cards ── */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
        {/* Donut */}
        <div className="card flex flex-col items-center justify-center">
          <ProgressDonut percentage={completionPct} />
          <p className="section-label mt-3">Migration Progress</p>
          <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>
            {completed} of {total} partners migrated
          </p>
        </div>
        {/* Stat cards 3x2 grid */}
        <div className="lg:col-span-3 grid grid-cols-2 lg:grid-cols-3 gap-3">
          {stats.map(s => (
            <StatCard
              key={s.label}
              label={s.label}
              value={s.value}
              percentage={s.pct}
              icon={s.icon}
              color={s.color}
            />
          ))}
        </div>
      </div>

      {/* ── Row 2: Connection Split + Legacy Servers ── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="card">
          <p className="section-title mb-4 flex items-center gap-2">
            <SignalIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
            Connection Split (Last 24h)
          </p>
          <ConnectionSplitBar
            platform={connStats?.platformConnections24h || d.platformConnections24h || 0}
            legacy={connStats?.legacyConnections24h || d.legacyConnections24h || 0}
          />
          <div className="grid grid-cols-2 gap-3 mt-4">
            <div className="card-sm text-center">
              <p className="stat-number text-lg" style={{ color: 'rgb(100 140 255)' }}>
                {connStats?.platformConnections24h || d.platformConnections24h || 0}
              </p>
              <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>Platform</p>
            </div>
            <div className="card-sm text-center">
              <p className="stat-number text-lg" style={{ color: 'rgb(240 180 80)' }}>
                {connStats?.legacyConnections24h || d.legacyConnections24h || 0}
              </p>
              <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>Legacy</p>
            </div>
          </div>
        </div>

        <div className="card">
          <p className="section-title mb-4 flex items-center gap-2">
            <ServerIcon className="w-4 h-4" style={{ color: 'rgb(240 180 80)' }} />
            Legacy Infrastructure
          </p>
          <div className="flex items-center gap-4">
            <div className="card-sm flex-1 text-center">
              <p className="stat-number text-2xl">{d.legacyServerCount || 0}</p>
              <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>Legacy Servers</p>
            </div>
            <div className="card-sm flex-1 text-center">
              <p className="stat-number text-2xl">{d.shadowModePartners || 0}</p>
              <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>In Shadow Mode</p>
            </div>
          </div>
          <a
            href="/gateway"
            className="text-xs font-medium mt-3 inline-flex items-center gap-1 hover:underline"
            style={{ color: 'rgb(var(--accent))' }}
          >
            View Gateway <ChevronRightIcon className="w-3 h-3" />
          </a>
        </div>
      </div>

      {/* ── Row 3: Recent Migration Events ── */}
      <div className="card">
        <p className="section-title mb-3 flex items-center gap-2">
          <BoltIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
          Recent Migration Events
        </p>
        {events.length > 0 ? (
          <div className="max-h-80 overflow-y-auto">
            {events.slice(0, 10).map((ev, i) => (
              <EventItem key={ev.id || i} event={ev} />
            ))}
          </div>
        ) : (
          <div className="py-8 text-center">
            <ClockIcon className="w-8 h-8 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
            <p className="text-sm" style={{ color: 'rgb(var(--tx-muted))' }}>No migration events yet</p>
            <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
              Events will appear here as you migrate partners
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

/* ═══════════════════════════════════════════════════════════
   TAB 2 — PARTNERS LIST
   ═══════════════════════════════════════════════════════════ */

function PartnersTab({ onSelectPartner }) {
  const qc = useQueryClient()
  const [statusFilter, setStatusFilter] = useState('')
  const [search, setSearch] = useState('')
  const [startModal, setStartModal] = useState(null)
  const [shadowModal, setShadowModal] = useState(null)
  const [rollbackModal, setRollbackModal] = useState(null)
  const [startForm, setStartForm] = useState({ source: '', notes: '' })
  const [shadowForm, setShadowForm] = useState({ legacyHost: '', legacyPort: 22, legacyUsername: '' })
  const [rollbackReason, setRollbackReason] = useState('')

  const { data: partners = [], isLoading } = useQuery({
    queryKey: ['migration-partners', statusFilter],
    queryFn: () => migrationApi.getMigrationPartners(statusFilter || undefined),
    refetchInterval: 30_000,
  })

  const filtered = useMemo(() => {
    if (!search) return partners
    const q = search.toLowerCase()
    return partners.filter(p =>
      (p.partnerName || '').toLowerCase().includes(q) ||
      (p.partnerId || '').toLowerCase().includes(q)
    )
  }, [partners, search])

  /* ── Mutations ── */
  const startMut = useMutation({
    mutationFn: ({ id, source, notes }) => migrationApi.startMigration(id, source, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      qc.invalidateQueries({ queryKey: ['migration-dashboard'] })
      setStartModal(null)
      toast.success('Migration started')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to start migration'),
  })

  const enableShadowMut = useMutation({
    mutationFn: ({ id, host, port, username }) => migrationApi.enableShadowMode(id, host, port, username),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      qc.invalidateQueries({ queryKey: ['migration-dashboard'] })
      setShadowModal(null)
      toast.success('Shadow mode enabled')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to enable shadow mode'),
  })

  const disableShadowMut = useMutation({
    mutationFn: (id) => migrationApi.disableShadowMode(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      toast.success('Shadow mode disabled')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to disable shadow mode'),
  })

  const verifyMut = useMutation({
    mutationFn: (id) => migrationApi.startVerification(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      toast.success('Verification started')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to start verification'),
  })

  const completeMut = useMutation({
    mutationFn: (id) => migrationApi.completeMigration(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      qc.invalidateQueries({ queryKey: ['migration-dashboard'] })
      toast.success('Migration completed')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to complete migration'),
  })

  const rollbackMut = useMutation({
    mutationFn: ({ id, reason }) => migrationApi.rollbackMigration(id, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      qc.invalidateQueries({ queryKey: ['migration-dashboard'] })
      setRollbackModal(null)
      toast.success('Migration rolled back')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to rollback'),
  })

  function renderActions(p) {
    const s = p.migrationStatus
    return (
      <div className="flex items-center gap-1.5 flex-wrap">
        {(s === 'NOT_STARTED' || s === 'DISCOVERED') && (
          <button
            className="btn-primary !py-1 !px-2.5 !text-xs"
            onClick={(e) => { e.stopPropagation(); setStartModal(p); setStartForm({ source: '', notes: '' }) }}
          >
            <PlayIcon className="w-3.5 h-3.5" /> Start
          </button>
        )}
        {s === 'IN_PROGRESS' && (
          <button
            className="btn-primary !py-1 !px-2.5 !text-xs"
            onClick={(e) => { e.stopPropagation(); setShadowModal(p); setShadowForm({ legacyHost: '', legacyPort: 22, legacyUsername: '' }) }}
          >
            <ArrowsRightLeftIcon className="w-3.5 h-3.5" /> Shadow
          </button>
        )}
        {s === 'SHADOW_MODE' && (
          <>
            <button
              className="btn-primary !py-1 !px-2.5 !text-xs"
              onClick={(e) => { e.stopPropagation(); verifyMut.mutate(p.id) }}
              disabled={verifyMut.isPending}
            >
              <ShieldCheckIcon className="w-3.5 h-3.5" /> Verify
            </button>
            <button
              className="btn-secondary !py-1 !px-2.5 !text-xs"
              onClick={(e) => { e.stopPropagation(); disableShadowMut.mutate(p.id) }}
              disabled={disableShadowMut.isPending}
            >
              <StopIcon className="w-3.5 h-3.5" /> Stop Shadow
            </button>
          </>
        )}
        {s === 'VERIFIED' && (
          <button
            className="btn-primary !py-1 !px-2.5 !text-xs"
            onClick={(e) => { e.stopPropagation(); completeMut.mutate(p.id) }}
            disabled={completeMut.isPending}
          >
            <CheckCircleIcon className="w-3.5 h-3.5" /> Complete
          </button>
        )}
        {s !== 'COMPLETED' && (
          <button
            className="btn-danger !py-1 !px-2.5 !text-xs"
            onClick={(e) => { e.stopPropagation(); setRollbackModal(p); setRollbackReason('') }}
          >
            <ArrowUturnLeftIcon className="w-3.5 h-3.5" /> Rollback
          </button>
        )}
      </div>
    )
  }

  if (isLoading) return <LoadingSpinner text="Loading migration partners..." />

  return (
    <div className="space-y-4">
      {/* ── Filter Bar ── */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative flex-1 min-w-[200px] max-w-xs">
          <MagnifyingGlassIcon
            className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'rgb(var(--tx-muted))' }}
          />
          <input
            type="text"
            placeholder="Search partners..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="!pl-9"
          />
        </div>
        <div className="flex items-center gap-2">
          <FunnelIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="!w-auto !min-w-[160px]"
          >
            {STATUS_FILTER_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <span className="text-xs ml-auto" style={{ color: 'rgb(var(--tx-muted))' }}>
          {filtered.length} partner{filtered.length !== 1 ? 's' : ''}
        </span>
      </div>

      {/* ── Table ── */}
      {filtered.length > 0 ? (
        <div className="card !p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                  <th className="table-header">Partner</th>
                  <th className="table-header">Status</th>
                  <th className="table-header">Source</th>
                  <th className="table-header">Shadow</th>
                  <th className="table-header text-right">Platform (7d)</th>
                  <th className="table-header text-right">Legacy (7d)</th>
                  <th className="table-header">Last Platform</th>
                  <th className="table-header">Last Legacy</th>
                  <th className="table-header">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(p => (
                  <tr
                    key={p.id}
                    className="table-row cursor-pointer"
                    onClick={() => onSelectPartner(p)}
                  >
                    <td className="table-cell font-medium">{p.partnerName || p.partnerId}</td>
                    <td className="table-cell">
                      <span className={`badge ${STATUS_BADGE[p.migrationStatus] || 'badge-gray'}`}>
                        {STATUS_LABEL[p.migrationStatus] || p.migrationStatus}
                      </span>
                    </td>
                    <td className="table-cell text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
                      {p.migrationSource || '--'}
                    </td>
                    <td className="table-cell">
                      {p.shadowModeEnabled ? (
                        <span className="badge badge-purple">Active</span>
                      ) : (
                        <span style={{ color: 'rgb(var(--tx-muted))' }}>--</span>
                      )}
                    </td>
                    <td className="table-cell text-right font-mono text-sm">{p.platformConnections7d ?? 0}</td>
                    <td className="table-cell text-right font-mono text-sm" style={{ color: 'rgb(240 180 80)' }}>
                      {p.legacyConnections7d ?? 0}
                    </td>
                    <td className="table-cell text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {fmtTime(p.lastPlatformConnection)}
                    </td>
                    <td className="table-cell text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {fmtTime(p.lastLegacyConnection)}
                    </td>
                    <td className="table-cell" onClick={(e) => e.stopPropagation()}>
                      {renderActions(p)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <div className="card py-12 text-center">
          <UserGroupIcon className="w-10 h-10 mx-auto mb-3" style={{ color: 'rgb(var(--tx-muted))' }} />
          <p className="text-sm font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>No partners found</p>
          <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
            {search || statusFilter ? 'Try adjusting your filters' : 'Partners will appear once discovered or imported'}
          </p>
        </div>
      )}

      {/* ── Start Migration Modal ── */}
      {startModal && (
        <Modal title={`Start Migration — ${startModal.partnerName || startModal.partnerId}`} onClose={() => setStartModal(null)}>
          <div className="space-y-4">
            <div>
              <label>Migration Source</label>
              <input
                placeholder="e.g. Axway SecureTransport, GoAnywhere, IBM SFG"
                value={startForm.source}
                onChange={(e) => setStartForm(f => ({ ...f, source: e.target.value }))}
              />
            </div>
            <div>
              <label>Notes (optional)</label>
              <textarea
                rows={3}
                placeholder="Any migration notes..."
                value={startForm.notes}
                onChange={(e) => setStartForm(f => ({ ...f, notes: e.target.value }))}
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button className="btn-secondary" onClick={() => setStartModal(null)}>Cancel</button>
              <button
                className="btn-primary"
                disabled={!startForm.source || startMut.isPending}
                onClick={() => startMut.mutate({ id: startModal.id, source: startForm.source, notes: startForm.notes })}
              >
                {startMut.isPending ? 'Starting...' : 'Start Migration'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Enable Shadow Mode Modal ── */}
      {shadowModal && (
        <Modal title={`Enable Shadow Mode — ${shadowModal.partnerName || shadowModal.partnerId}`} onClose={() => setShadowModal(null)}>
          <div className="space-y-4">
            <div className="card-sm" style={{ background: 'rgb(40 20 60 / 0.3)', borderColor: 'rgb(190 140 255 / 0.3)' }}>
              <p className="text-xs" style={{ color: 'rgb(190 140 255)' }}>
                Shadow mode routes connections to both the platform and the legacy server simultaneously,
                allowing side-by-side comparison without disrupting existing workflows.
              </p>
            </div>
            <div>
              <label>Legacy Host</label>
              <input
                placeholder="e.g. legacy-sftp.company.com"
                value={shadowForm.legacyHost}
                onChange={(e) => setShadowForm(f => ({ ...f, legacyHost: e.target.value }))}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label>Legacy Port</label>
                <input
                  type="number"
                  value={shadowForm.legacyPort}
                  onChange={(e) => setShadowForm(f => ({ ...f, legacyPort: parseInt(e.target.value) || 22 }))}
                />
              </div>
              <div>
                <label>Legacy Username</label>
                <input
                  placeholder="Username for legacy server"
                  value={shadowForm.legacyUsername}
                  onChange={(e) => setShadowForm(f => ({ ...f, legacyUsername: e.target.value }))}
                />
              </div>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button className="btn-secondary" onClick={() => setShadowModal(null)}>Cancel</button>
              <button
                className="btn-primary"
                disabled={!shadowForm.legacyHost || !shadowForm.legacyUsername || enableShadowMut.isPending}
                onClick={() => enableShadowMut.mutate({
                  id: shadowModal.id,
                  host: shadowForm.legacyHost,
                  port: shadowForm.legacyPort,
                  username: shadowForm.legacyUsername,
                })}
              >
                {enableShadowMut.isPending ? 'Enabling...' : 'Enable Shadow Mode'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Rollback Confirmation Modal ── */}
      {rollbackModal && (
        <Modal title={`Rollback Migration — ${rollbackModal.partnerName || rollbackModal.partnerId}`} onClose={() => setRollbackModal(null)}>
          <div className="space-y-4">
            <div className="card-sm" style={{ background: 'rgb(60 20 20 / 0.3)', borderColor: 'rgb(240 120 120 / 0.3)' }}>
              <p className="text-xs" style={{ color: 'rgb(240 120 120)' }}>
                This will revert the partner to NOT_STARTED status. Shadow mode will be disabled
                and all migration progress will be reset. This action cannot be undone.
              </p>
            </div>
            <div>
              <label>Reason for rollback</label>
              <textarea
                rows={3}
                placeholder="Describe why the migration is being rolled back..."
                value={rollbackReason}
                onChange={(e) => setRollbackReason(e.target.value)}
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button className="btn-secondary" onClick={() => setRollbackModal(null)}>Cancel</button>
              <button
                className="btn-danger"
                disabled={!rollbackReason || rollbackMut.isPending}
                onClick={() => rollbackMut.mutate({ id: rollbackModal.id, reason: rollbackReason })}
              >
                {rollbackMut.isPending ? 'Rolling back...' : 'Confirm Rollback'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

/* ═══════════════════════════════════════════════════════════
   TAB 3 — PARTNER DETAIL
   ═══════════════════════════════════════════════════════════ */

function PartnerDetailTab({ partner, onBack }) {
  const qc = useQueryClient()
  const [verifyModal, setVerifyModal] = useState(false)
  const [verifyForm, setVerifyForm] = useState({ transferCount: '', passed: true, details: '' })
  const [shadowForm, setShadowForm] = useState({ legacyHost: '', legacyPort: 22, legacyUsername: '' })
  const [showShadowForm, setShowShadowForm] = useState(false)
  const [rollbackModal, setRollbackModal] = useState(false)
  const [rollbackReason, setRollbackReason] = useState('')

  const { data: detail, isLoading: detailLoading } = useQuery({
    queryKey: ['migration-partner-detail', partner.id],
    queryFn: () => migrationApi.getMigrationPartnerDetail(partner.id),
    refetchInterval: 15_000,
  })

  const { data: events = [] } = useQuery({
    queryKey: ['migration-partner-events', partner.id],
    queryFn: () => migrationApi.getPartnerEvents(partner.id),
    refetchInterval: 30_000,
  })

  const { data: connections = [] } = useQuery({
    queryKey: ['migration-partner-connections', partner.id],
    queryFn: () => migrationApi.getPartnerConnections(partner.id),
    refetchInterval: 15_000,
  })

  /* ── Mutations ── */
  const enableShadowMut = useMutation({
    mutationFn: ({ host, port, username }) => migrationApi.enableShadowMode(partner.id, host, port, username),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partner-detail', partner.id] })
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      setShowShadowForm(false)
      toast.success('Shadow mode enabled')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to enable shadow mode'),
  })

  const disableShadowMut = useMutation({
    mutationFn: () => migrationApi.disableShadowMode(partner.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partner-detail', partner.id] })
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      toast.success('Shadow mode disabled')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to disable shadow mode'),
  })

  const verifyStartMut = useMutation({
    mutationFn: () => migrationApi.startVerification(partner.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partner-detail', partner.id] })
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      toast.success('Verification started')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to start verification'),
  })

  const recordVerifyMut = useMutation({
    mutationFn: ({ transferCount, passed, details }) =>
      migrationApi.recordVerification(partner.id, transferCount, passed, details),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partner-detail', partner.id] })
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      setVerifyModal(false)
      toast.success('Verification recorded')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to record verification'),
  })

  const completeMut = useMutation({
    mutationFn: () => migrationApi.completeMigration(partner.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partner-detail', partner.id] })
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      qc.invalidateQueries({ queryKey: ['migration-dashboard'] })
      toast.success('Migration completed')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to complete migration'),
  })

  const rollbackMut = useMutation({
    mutationFn: (reason) => migrationApi.rollbackMigration(partner.id, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration-partner-detail', partner.id] })
      qc.invalidateQueries({ queryKey: ['migration-partners'] })
      qc.invalidateQueries({ queryKey: ['migration-dashboard'] })
      setRollbackModal(false)
      toast.success('Migration rolled back')
      onBack()
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to rollback'),
  })

  const d = detail || partner
  const status = d.migrationStatus || 'NOT_STARTED'

  if (detailLoading && !detail) return <LoadingSpinner text="Loading partner detail..." />

  /* ── Connection audit: aggregate platform vs legacy per day ── */
  const connByDay = useMemo(() => {
    const map = {}
    ;(connections || []).forEach(c => {
      const day = c.timestamp ? format(new Date(c.timestamp), 'EEE') : 'Unknown'
      if (!map[day]) map[day] = { platform: 0, legacy: 0 }
      if (c.routedTo === 'PLATFORM') map[day].platform++
      else map[day].legacy++
    })
    return Object.entries(map).map(([day, counts]) => ({ day, ...counts }))
  }, [connections])

  const maxConn = Math.max(1, ...connByDay.map(d => Math.max(d.platform, d.legacy)))

  return (
    <div className="space-y-6">
      {/* ── Header ── */}
      <div className="flex items-center gap-4">
        <button className="btn-ghost !px-2" onClick={onBack}>
          <ChevronRightIcon className="w-4 h-4 rotate-180" />
          Back
        </button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3 flex-wrap">
            <h2 className="text-xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {d.partnerName || d.partnerId}
            </h2>
            <span className={`badge ${STATUS_BADGE[status] || 'badge-gray'}`}>
              {STATUS_LABEL[status] || status}
            </span>
          </div>
          <div className="flex items-center gap-4 mt-1 text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
            {d.migrationSource && <span>Source: {d.migrationSource}</span>}
            {d.migrationStartedAt && <span>Started: {fmtDate(d.migrationStartedAt)}</span>}
            {d.migrationCompletedAt && <span>Completed: {fmtDate(d.migrationCompletedAt)}</span>}
          </div>
        </div>
      </div>

      {/* ── Migration Stepper ── */}
      <div className="card">
        <p className="section-label mb-4">Migration Progress</p>
        <MigrationStepper currentStatus={status} />
      </div>

      {/* ── Shadow Mode Card ── */}
      {(status === 'IN_PROGRESS' || status === 'SHADOW_MODE' || d.shadowModeEnabled) && (
        <div
          className="card"
          style={{
            borderColor: d.shadowModeEnabled ? 'rgb(190 140 255 / 0.3)' : undefined,
            background: d.shadowModeEnabled ? 'rgb(40 20 60 / 0.15)' : undefined,
          }}
        >
          <div className="flex items-center justify-between mb-4">
            <p className="section-title flex items-center gap-2">
              <ArrowsRightLeftIcon className="w-4 h-4" style={{ color: '#a855f7' }} />
              Shadow Mode
            </p>
            {d.shadowModeEnabled && (
              <span className="badge badge-purple">
                <span className="w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse" />
                Dual-write Active
              </span>
            )}
          </div>

          {d.shadowModeEnabled ? (
            <div className="space-y-3">
              <div className="grid grid-cols-3 gap-3">
                <div className="card-sm">
                  <p className="text-[10px] uppercase font-semibold" style={{ color: 'rgb(var(--tx-muted))' }}>Legacy Host</p>
                  <p className="font-mono text-sm mt-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {d.legacyHost || '--'}
                  </p>
                </div>
                <div className="card-sm">
                  <p className="text-[10px] uppercase font-semibold" style={{ color: 'rgb(var(--tx-muted))' }}>Legacy Port</p>
                  <p className="font-mono text-sm mt-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {d.legacyPort || '--'}
                  </p>
                </div>
                <div className="card-sm">
                  <p className="text-[10px] uppercase font-semibold" style={{ color: 'rgb(var(--tx-muted))' }}>Username</p>
                  <p className="font-mono text-sm mt-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {d.legacyUsername || '--'}
                  </p>
                </div>
              </div>
              <div className="flex gap-2">
                <button
                  className="btn-danger !text-xs"
                  onClick={() => disableShadowMut.mutate()}
                  disabled={disableShadowMut.isPending}
                >
                  <StopIcon className="w-3.5 h-3.5" />
                  {disableShadowMut.isPending ? 'Disabling...' : 'Disable Shadow Mode'}
                </button>
              </div>
            </div>
          ) : (
            <div>
              {showShadowForm ? (
                <div className="space-y-3">
                  <div>
                    <label>Legacy Host</label>
                    <input
                      placeholder="e.g. legacy-sftp.company.com"
                      value={shadowForm.legacyHost}
                      onChange={(e) => setShadowForm(f => ({ ...f, legacyHost: e.target.value }))}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label>Legacy Port</label>
                      <input
                        type="number"
                        value={shadowForm.legacyPort}
                        onChange={(e) => setShadowForm(f => ({ ...f, legacyPort: parseInt(e.target.value) || 22 }))}
                      />
                    </div>
                    <div>
                      <label>Legacy Username</label>
                      <input
                        placeholder="Username"
                        value={shadowForm.legacyUsername}
                        onChange={(e) => setShadowForm(f => ({ ...f, legacyUsername: e.target.value }))}
                      />
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <button className="btn-secondary" onClick={() => setShowShadowForm(false)}>Cancel</button>
                    <button
                      className="btn-primary"
                      disabled={!shadowForm.legacyHost || !shadowForm.legacyUsername || enableShadowMut.isPending}
                      onClick={() => enableShadowMut.mutate({
                        host: shadowForm.legacyHost,
                        port: shadowForm.legacyPort,
                        username: shadowForm.legacyUsername,
                      })}
                    >
                      {enableShadowMut.isPending ? 'Enabling...' : 'Enable Shadow Mode'}
                    </button>
                  </div>
                </div>
              ) : (
                <div className="text-center py-4">
                  <p className="text-sm" style={{ color: 'rgb(var(--tx-muted))' }}>Shadow mode is not active</p>
                  <button
                    className="btn-primary mt-3"
                    onClick={() => { setShowShadowForm(true); setShadowForm({ legacyHost: '', legacyPort: 22, legacyUsername: '' }) }}
                  >
                    <ArrowsRightLeftIcon className="w-4 h-4" /> Enable Shadow Mode
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* ── Connection Audit Chart ── */}
      <div className="card">
        <p className="section-title mb-4 flex items-center gap-2">
          <ChartBarIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
          Connection Audit (Last 7 days)
        </p>
        {connByDay.length > 0 ? (
          <div className="space-y-2">
            <div className="flex items-center gap-4 text-[10px] mb-3" style={{ color: 'rgb(var(--tx-muted))' }}>
              <span className="flex items-center gap-1">
                <span className="w-2.5 h-2.5 rounded-sm" style={{ background: 'rgb(100 140 255)' }} /> Platform
              </span>
              <span className="flex items-center gap-1">
                <span className="w-2.5 h-2.5 rounded-sm" style={{ background: 'rgb(240 180 80)' }} /> Legacy
              </span>
            </div>
            {connByDay.map(d => (
              <div key={d.day} className="flex items-center gap-3">
                <span className="w-8 text-[10px] font-semibold flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }}>{d.day}</span>
                <div className="flex-1 flex gap-1 h-5">
                  <div
                    className="rounded-sm h-full transition-all"
                    style={{ width: `${(d.platform / maxConn) * 100}%`, background: 'rgb(100 140 255)', minWidth: d.platform > 0 ? 4 : 0 }}
                  />
                  <div
                    className="rounded-sm h-full transition-all"
                    style={{ width: `${(d.legacy / maxConn) * 100}%`, background: 'rgb(240 180 80)', minWidth: d.legacy > 0 ? 4 : 0 }}
                  />
                </div>
                <span className="text-[10px] font-mono w-16 text-right flex-shrink-0" style={{ color: 'rgb(var(--tx-secondary))' }}>
                  {d.platform} / {d.legacy}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <div className="py-6 text-center">
            <ChartBarIcon className="w-7 h-7 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
            <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>No connection data yet</p>
          </div>
        )}
      </div>

      {/* ── Recent Connections Table ── */}
      <div className="card !p-0 overflow-hidden">
        <div className="px-6 pt-5 pb-3">
          <p className="section-title flex items-center gap-2">
            <LinkIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
            Recent Connections
          </p>
        </div>
        {connections && connections.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                  <th className="table-header">Username</th>
                  <th className="table-header">Source IP</th>
                  <th className="table-header">Protocol</th>
                  <th className="table-header">Routed To</th>
                  <th className="table-header">Time</th>
                </tr>
              </thead>
              <tbody>
                {connections.slice(0, 20).map((c, i) => (
                  <tr key={c.id || i} className="table-row">
                    <td className="table-cell font-mono text-xs">{c.username || '--'}</td>
                    <td className="table-cell font-mono text-xs">{c.sourceIp || '--'}</td>
                    <td className="table-cell text-xs">{c.protocol || '--'}</td>
                    <td className="table-cell">
                      <span className={`badge ${c.routedTo === 'PLATFORM' ? 'badge-blue' : 'badge-yellow'}`}>
                        {c.routedTo || '--'}
                      </span>
                    </td>
                    <td className="table-cell text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {fmtTime(c.timestamp)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="py-8 text-center">
            <LinkIcon className="w-7 h-7 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
            <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>No connections recorded</p>
          </div>
        )}
      </div>

      {/* ── Migration Event Timeline ── */}
      <div className="card">
        <p className="section-title mb-3 flex items-center gap-2">
          <ClockIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
          Migration Event Timeline
        </p>
        {events.length > 0 ? (
          <div className="max-h-72 overflow-y-auto">
            {events.map((ev, i) => (
              <EventItem key={ev.id || i} event={ev} />
            ))}
          </div>
        ) : (
          <div className="py-6 text-center">
            <ClockIcon className="w-7 h-7 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
            <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>No events yet</p>
          </div>
        )}
      </div>

      {/* ── Verification Section ── */}
      <div className="card">
        <p className="section-title mb-4 flex items-center gap-2">
          <DocumentCheckIcon className="w-4 h-4" style={{ color: '#22c55e' }} />
          Verification
        </p>
        <div className="grid grid-cols-3 gap-3 mb-4">
          <div className="card-sm text-center">
            <p className="text-[10px] uppercase font-semibold" style={{ color: 'rgb(var(--tx-muted))' }}>Transfer Count</p>
            <p className="stat-number text-lg mt-0.5">{d.verificationTransferCount ?? '--'}</p>
          </div>
          <div className="card-sm text-center">
            <p className="text-[10px] uppercase font-semibold" style={{ color: 'rgb(var(--tx-muted))' }}>Last Verified</p>
            <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>{fmtDate(d.lastVerifiedAt)}</p>
          </div>
          <div className="card-sm text-center">
            <p className="text-[10px] uppercase font-semibold" style={{ color: 'rgb(var(--tx-muted))' }}>Status</p>
            <div className="mt-1">
              {d.verificationPassed === true && <span className="badge badge-green">Passed</span>}
              {d.verificationPassed === false && <span className="badge badge-red">Failed</span>}
              {d.verificationPassed == null && <span className="badge badge-gray">Not Verified</span>}
            </div>
          </div>
        </div>
        {(status === 'SHADOW_MODE' || status === 'VERIFIED') && (
          <div className="flex gap-2">
            {status === 'SHADOW_MODE' && (
              <button
                className="btn-primary"
                onClick={() => verifyStartMut.mutate()}
                disabled={verifyStartMut.isPending}
              >
                <ShieldCheckIcon className="w-4 h-4" />
                {verifyStartMut.isPending ? 'Starting...' : 'Start Verification'}
              </button>
            )}
            <button
              className="btn-secondary"
              onClick={() => { setVerifyModal(true); setVerifyForm({ transferCount: '', passed: true, details: '' }) }}
            >
              <DocumentCheckIcon className="w-4 h-4" /> Record Verification
            </button>
          </div>
        )}
      </div>

      {/* ── Action Buttons ── */}
      {status !== 'COMPLETED' && (
        <div className="card">
          <p className="section-title mb-4 flex items-center gap-2">
            <WrenchIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
            Actions
          </p>
          <div className="flex items-center gap-3 flex-wrap">
            {status === 'VERIFIED' && (
              <button
                className="btn-primary"
                onClick={() => completeMut.mutate()}
                disabled={completeMut.isPending}
              >
                <CheckCircleIcon className="w-4 h-4" />
                {completeMut.isPending ? 'Completing...' : 'Complete Migration'}
              </button>
            )}
            <button
              className="btn-danger"
              onClick={() => { setRollbackModal(true); setRollbackReason('') }}
            >
              <ArrowUturnLeftIcon className="w-4 h-4" /> Rollback Migration
            </button>
          </div>
        </div>
      )}

      {/* ── Verification Record Modal ── */}
      {verifyModal && (
        <Modal title="Record Verification Result" onClose={() => setVerifyModal(false)}>
          <div className="space-y-4">
            <div>
              <label>Transfer Count Tested</label>
              <input
                type="number"
                placeholder="e.g. 500"
                value={verifyForm.transferCount}
                onChange={(e) => setVerifyForm(f => ({ ...f, transferCount: e.target.value }))}
              />
            </div>
            <div>
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={verifyForm.passed}
                  onChange={(e) => setVerifyForm(f => ({ ...f, passed: e.target.checked }))}
                  className="w-4 h-4 rounded"
                  style={{ accentColor: 'rgb(var(--accent))' }}
                />
                <span className="text-sm" style={{ color: 'rgb(var(--tx-primary))' }}>Verification Passed</span>
              </label>
            </div>
            <div>
              <label>Details</label>
              <textarea
                rows={3}
                placeholder="Verification results, notes, discrepancies..."
                value={verifyForm.details}
                onChange={(e) => setVerifyForm(f => ({ ...f, details: e.target.value }))}
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button className="btn-secondary" onClick={() => setVerifyModal(false)}>Cancel</button>
              <button
                className="btn-primary"
                disabled={!verifyForm.transferCount || recordVerifyMut.isPending}
                onClick={() => recordVerifyMut.mutate({
                  transferCount: parseInt(verifyForm.transferCount),
                  passed: verifyForm.passed,
                  details: verifyForm.details,
                })}
              >
                {recordVerifyMut.isPending ? 'Recording...' : 'Record Result'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Rollback Confirmation Modal ── */}
      {rollbackModal && (
        <Modal title="Confirm Rollback" onClose={() => setRollbackModal(false)}>
          <div className="space-y-4">
            <div className="card-sm" style={{ background: 'rgb(60 20 20 / 0.3)', borderColor: 'rgb(240 120 120 / 0.3)' }}>
              <p className="text-xs" style={{ color: 'rgb(240 120 120)' }}>
                This will revert {d.partnerName || d.partnerId} to NOT_STARTED status. Shadow mode will be
                disabled and all migration progress will be reset. This action cannot be undone.
              </p>
            </div>
            <div>
              <label>Reason for rollback</label>
              <textarea
                rows={3}
                placeholder="Describe why the migration is being rolled back..."
                value={rollbackReason}
                onChange={(e) => setRollbackReason(e.target.value)}
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button className="btn-secondary" onClick={() => setRollbackModal(false)}>Cancel</button>
              <button
                className="btn-danger"
                disabled={!rollbackReason || rollbackMut.isPending}
                onClick={() => rollbackMut.mutate(rollbackReason)}
              >
                {rollbackMut.isPending ? 'Rolling back...' : 'Confirm Rollback'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

/* ═══════════════════════════════════════════════════════════
   MAIN PAGE
   ═══════════════════════════════════════════════════════════ */

export default function Migration() {
  const [activeTab, setActiveTab] = useState('overview')
  const [selectedPartner, setSelectedPartner] = useState(null)

  function handleSelectPartner(partner) {
    setSelectedPartner(partner)
    setActiveTab('detail')
  }

  function handleBackToPartners() {
    setSelectedPartner(null)
    setActiveTab('partners')
  }

  const visibleTabs = selectedPartner
    ? TABS
    : TABS.filter(t => t.key !== 'detail')

  return (
    <div className="space-y-6 animate-page">
      {/* ── Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="page-title">Migration Command Center</h1>
          <p className="text-sm mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>
            Migrate partners from legacy MFT products to TranzFer
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium"
            style={{
              background: 'rgb(var(--accent) / 0.12)',
              border: '1px solid rgb(var(--accent) / 0.25)',
              color: 'rgb(var(--accent))',
            }}
          >
            <InformationCircleIcon className="w-3.5 h-3.5" />
            Auto-refresh active
          </div>
        </div>
      </div>

      {/* ── Tabs ── */}
      <div style={{ borderBottom: '1px solid rgb(var(--border))' }}>
        <div className="flex gap-6">
          {visibleTabs.map(tab => {
            const Icon = tab.icon
            const isActive = activeTab === tab.key
            return (
              <button
                key={tab.key}
                onClick={() => {
                  if (tab.key === 'detail' && !selectedPartner) return
                  setActiveTab(tab.key)
                }}
                className="pb-3 text-sm font-medium flex items-center gap-2 transition-colors"
                style={{
                  borderBottom: `2px solid ${isActive ? 'rgb(var(--accent))' : 'transparent'}`,
                  color: isActive ? 'rgb(var(--accent))' : 'rgb(var(--tx-secondary))',
                  opacity: tab.key === 'detail' && !selectedPartner ? 0.4 : 1,
                  cursor: tab.key === 'detail' && !selectedPartner ? 'default' : 'pointer',
                }}
              >
                <Icon className="w-4 h-4" />
                {tab.label}
                {tab.key === 'detail' && selectedPartner && (
                  <span
                    className="text-[10px] px-1.5 py-0.5 rounded-full"
                    style={{ background: 'rgb(var(--accent) / 0.15)' }}
                  >
                    {selectedPartner.partnerName || selectedPartner.partnerId}
                  </span>
                )}
              </button>
            )
          })}
        </div>
      </div>

      {/* ── Tab Content ── */}
      {activeTab === 'overview' && (
        <OverviewTab onSelectPartner={handleSelectPartner} />
      )}
      {activeTab === 'partners' && (
        <PartnersTab onSelectPartner={handleSelectPartner} />
      )}
      {activeTab === 'detail' && selectedPartner && (
        <PartnerDetailTab partner={selectedPartner} onBack={handleBackToPartners} />
      )}
      {activeTab === 'detail' && !selectedPartner && (
        <div className="card py-12 text-center">
          <EyeIcon className="w-10 h-10 mx-auto mb-3" style={{ color: 'rgb(var(--tx-muted))' }} />
          <p className="text-sm font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>No partner selected</p>
          <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
            Select a partner from the Partners tab to view details
          </p>
          <button className="btn-primary mt-4" onClick={() => setActiveTab('partners')}>
            Go to Partners
          </button>
        </div>
      )}
    </div>
  )
}
