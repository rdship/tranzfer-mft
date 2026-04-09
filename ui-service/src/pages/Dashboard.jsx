import { useQuery } from '@tanstack/react-query'
import { getDashboard, getPredictions, getFlowLiveStats } from '../api/analytics'
import { getAgentsDashboard } from '../api/ai'
import { useAuth } from '../context/AuthContext'
import LoadingSpinner from '../components/LoadingSpinner'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, PieChart, Pie, Cell,
} from 'recharts'
import {
  ArrowUpTrayIcon, CheckCircleIcon, ServerIcon, ChartBarIcon,
  ExclamationTriangleIcon, ArrowTrendingUpIcon, BoltIcon,
  BuildingOfficeIcon, ArrowsRightLeftIcon, CpuChipIcon,
  ClockIcon, ArrowPathIcon, ShieldCheckIcon,
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
]

function getGreeting() {
  const h = new Date().getHours()
  if (h < 5)  return 'Still up?'
  if (h < 12) return 'Good morning'
  if (h < 17) return 'Good afternoon'
  return 'Good evening'
}

/* Custom dark tooltip for charts */
function DarkTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div style={{
      background: '#18181b', border: '1px solid #3f3f46',
      borderRadius: '8px', padding: '8px 12px', fontSize: '12px',
    }}>
      <p style={{ color: '#a1a1aa', marginBottom: 2 }}>{label}</p>
      {payload.map(p => (
        <p key={p.dataKey} style={{ color: p.color || '#8b5cf6', fontWeight: 600, fontFamily: 'JetBrains Mono, monospace' }}>
          {p.value?.toLocaleString()}
        </p>
      ))}
    </div>
  )
}

/* KPI tile */
function KpiTile({ label, value, icon: Icon, color, sub }) {
  return (
    <div
      className="flex items-center gap-3 p-4 rounded-xl transition-all duration-200 cursor-default group"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
      onMouseEnter={e => { e.currentTarget.style.borderColor = `${color}44`; e.currentTarget.style.boxShadow = `0 0 20px ${color}18` }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'rgb(var(--border))'; e.currentTarget.style.boxShadow = 'none' }}
    >
      <div className="p-2 rounded-lg flex-shrink-0 transition-transform duration-200 group-hover:scale-110"
        style={{ background: `${color}18` }}>
        <Icon className="w-4 h-4" style={{ color }} />
      </div>
      <div className="min-w-0">
        <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {label}
        </p>
        <p className="font-mono font-bold text-xl leading-tight" style={{ color: 'rgb(var(--tx-primary))' }}>
          {value}
        </p>
        {sub && <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>{sub}</p>}
      </div>
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

export default function Dashboard() {
  const { user } = useAuth()
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: getDashboard,
    refetchInterval: 30000,
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
    refetchInterval: 5000,
    staleTime: 4000,
    retry: false,
  })
  const {
    data: flowStats, isLoading: flowsLoading, isError: flowsError,
  } = useQuery({
    queryKey: ['flow-live-stats'],
    queryFn: getFlowLiveStats,
    refetchInterval: 5000,
    staleTime: 4000,
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
        />
        <KpiTile
          label="Success Rate"
          value={`${successRate}%`}
          icon={CheckCircleIcon}
          color="#22c55e"
          sub={dashboard?.totalTransfersToday ? `${Math.round(dashboard.totalTransfersToday * (dashboard.successRateToday || 1))} succeeded` : undefined}
        />
        <KpiTile
          label="Data Moved"
          value={`${(dashboard?.totalGbToday || 0).toFixed(2)} GB`}
          icon={ServerIcon}
          color="#22d3ee"
        />
        <KpiTile
          label="Last Hour"
          value={(dashboard?.totalTransfersLastHour || 0).toLocaleString()}
          icon={ChartBarIcon}
          color="#fbbf24"
        />
        <KpiTile
          label="Protocols Active"
          value={protocolData.length || 0}
          icon={BoltIcon}
          color="#f87171"
          sub={protocolData.map(p => p.name).join(' · ') || 'None yet'}
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
          <h3 className="font-semibold text-sm flex items-center gap-2" style={{ color: '#f87171' }}>
            <ExclamationTriangleIcon className="w-4 h-4" />
            Active Alerts ({dashboard.alerts.length})
          </h3>
          <div className="mt-2 space-y-1">
            {dashboard.alerts.map((alert, i) => (
              <p key={i} className="text-xs" style={{ color: '#fca5a5' }}>
                <span className="font-mono font-semibold">{alert.ruleName}</span>
                {' — '}{alert.serviceType}: {alert.metric} = {alert.currentValue?.toFixed(3)} (threshold: {alert.threshold})
              </p>
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
            <span className="text-[10px] font-medium px-2 py-0.5 rounded-full"
              style={{ background: 'rgb(var(--accent) / 0.12)', color: 'rgb(var(--accent))' }}>
              Last 24 hours
            </span>
          </div>

          {transferData.length > 0 ? (
            <ResponsiveContainer width="100%" height={220}>
              <AreaChart data={transferData} margin={{ top: 4, right: 4, left: -20, bottom: 0 }}>
                <defs>
                  <linearGradient id="gradViolet" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%"   stopColor="#8b5cf6" stopOpacity={0.35} />
                    <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="rgb(var(--border))" vertical={false} />
                <XAxis
                  dataKey="hour"
                  tick={{ fontSize: 10, fill: 'rgb(var(--tx-muted))' }}
                  axisLine={false}
                  tickLine={false}
                />
                <YAxis
                  tick={{ fontSize: 10, fill: 'rgb(var(--tx-muted))' }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip content={<DarkTooltip />} />
                <Area
                  type="monotone"
                  dataKey="transfers"
                  stroke="#8b5cf6"
                  strokeWidth={2.5}
                  fill="url(#gradViolet)"
                  dot={false}
                  activeDot={{ r: 4, fill: '#8b5cf6', strokeWidth: 0 }}
                />
              </AreaChart>
            </ResponsiveContainer>
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

      {/* ── Bottom Grid: Protocol + Recommendations ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* Protocol Breakdown */}
        <div className="card">
          <p className="section-title mb-3">By Protocol</p>
          {protocolData.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={150}>
                <PieChart>
                  <Pie
                    data={protocolData}
                    cx="50%" cy="50%"
                    innerRadius={42} outerRadius={68}
                    dataKey="value"
                    strokeWidth={0}
                  >
                    {protocolData.map((_, i) => (
                      <Cell key={i} fill={NEON[i % NEON.length]} />
                    ))}
                  </Pie>
                  <Tooltip content={<DarkTooltip />} />
                </PieChart>
              </ResponsiveContainer>
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
