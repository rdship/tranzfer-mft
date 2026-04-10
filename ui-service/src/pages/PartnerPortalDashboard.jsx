import { useQuery, useMutation } from '@tanstack/react-query'
import { getPartnerDashboard, getPartnerSla, testConnection } from '../api/partnerPortal'
import LoadingSpinner from '../components/LoadingSpinner'
import { format } from 'date-fns'
import {
  ArrowUpTrayIcon,
  CheckCircleIcon,
  ArrowPathIcon,
  ClockIcon,
  ShieldCheckIcon,
  ExclamationTriangleIcon,
  SignalIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

/* KPI tile matching the admin dashboard style */
function KpiTile({ label, value, icon: Icon, color, sub }) {
  return (
    <div
      className="flex items-center gap-3 p-4 rounded-xl transition-all duration-200 cursor-default group"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
      onMouseEnter={e => { e.currentTarget.style.borderColor = `${color}44`; e.currentTarget.style.boxShadow = `0 0 20px ${color}18` }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'rgb(var(--border))'; e.currentTarget.style.boxShadow = 'none' }}
    >
      <div
        className="p-2 rounded-lg flex-shrink-0 transition-transform duration-200 group-hover:scale-110"
        style={{ background: `${color}18` }}
      >
        <Icon className="w-4 h-4" style={{ color }} />
      </div>
      <div className="min-w-0">
        <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {label}
        </p>
        <p className="font-mono font-bold text-xl leading-tight" style={{ color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono', monospace" }}>
          {value}
        </p>
        {sub && <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>{sub}</p>}
      </div>
    </div>
  )
}

/* Status badge for transfer rows */
function StatusBadge({ status }) {
  const map = {
    COMPLETED:   'badge-green',
    FAILED:      'badge-red',
    IN_PROGRESS: 'badge-yellow',
    PENDING:     'badge-blue',
    QUEUED:      'badge-gray',
  }
  return <span className={`badge ${map[status] || 'badge-gray'}`}>{status}</span>
}

export default function PartnerPortalDashboard() {
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['partner-dashboard'],
    queryFn: getPartnerDashboard,
    refetchInterval: 30000,
  })

  const { data: sla } = useQuery({
    queryKey: ['partner-sla'],
    queryFn: getPartnerSla,
  })

  const connectionTest = useMutation({
    mutationFn: testConnection,
    onSuccess: (data) => {
      toast.success(data?.message || 'Connection successful — your endpoint is reachable')
    },
    onError: (err) => {
      toast.error(err.response?.data?.error || err.message || 'Connection test failed')
    },
  })

  if (isLoading) return <LoadingSpinner text="Loading dashboard..." />

  const partnerRaw = localStorage.getItem('partner-user')
  const partner = partnerRaw ? JSON.parse(partnerRaw) : {}
  const username = partner.username || partner.name || 'Partner'

  const totalTransfers  = dashboard?.totalTransfers ?? 0
  const successRate     = dashboard?.successRate != null ? (dashboard.successRate * 100).toFixed(1) : '0.0'
  const activeTransfers = dashboard?.activeTransfers ?? 0
  const avgTransferTime = dashboard?.avgTransferTime ?? '—'
  const recentTransfers = dashboard?.recentTransfers || []

  /* SLA data */
  const slaCompliance  = sla?.compliancePercent != null ? sla.compliancePercent.toFixed(1) : null
  const slaMet         = sla?.met ?? null
  const slaTarget      = sla?.targetPercent != null ? sla.targetPercent.toFixed(1) : '99.5'

  return (
    <div className="space-y-5 animate-page">

      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
            Welcome,{' '}
            <span style={{ color: 'rgb(var(--accent))' }}>{username}</span>
          </h1>
          <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
            {format(new Date(), "EEEE, MMMM d, yyyy 'at' HH:mm")}
          </p>
        </div>

        {/* Connection test */}
        <button
          className="btn-primary"
          onClick={() => connectionTest.mutate()}
          disabled={connectionTest.isPending}
        >
          {connectionTest.isPending ? (
            <span className="flex items-center gap-2">
              <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              Testing...
            </span>
          ) : (
            <>
              <SignalIcon className="w-4 h-4" />
              Test Connection
            </>
          )}
        </button>
      </div>

      {/* KPI Row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <KpiTile
          label="Total Transfers"
          value={totalTransfers.toLocaleString()}
          icon={ArrowUpTrayIcon}
          color="#8b5cf6"
        />
        <KpiTile
          label="Success Rate"
          value={`${successRate}%`}
          icon={CheckCircleIcon}
          color="#22c55e"
          sub={totalTransfers > 0 ? `${Math.round(totalTransfers * (dashboard?.successRate || 0))} succeeded` : undefined}
        />
        <KpiTile
          label="Active Transfers"
          value={activeTransfers.toLocaleString()}
          icon={ArrowPathIcon}
          color="#22d3ee"
        />
        <KpiTile
          label="Avg Transfer Time"
          value={typeof avgTransferTime === 'number' ? `${avgTransferTime}s` : avgTransferTime}
          icon={ClockIcon}
          color="#fbbf24"
        />
      </div>

      {/* SLA + Recent Transfers grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* SLA Compliance Card */}
        <div className="card">
          <p className="section-title mb-4 flex items-center gap-2">
            <ShieldCheckIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
            SLA Compliance
          </p>

          {slaCompliance != null ? (
            <div className="space-y-4">
              {/* Big number */}
              <div className="text-center">
                <p
                  className="text-4xl font-bold font-mono"
                  style={{
                    color: slaMet ? '#4ade80' : '#f87171',
                    fontFamily: "'JetBrains Mono', monospace",
                  }}
                >
                  {slaCompliance}%
                </p>
                <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
                  Target: {slaTarget}%
                </p>
              </div>

              {/* Status badge */}
              <div className="flex justify-center">
                {slaMet ? (
                  <div
                    className="flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium"
                    style={{ background: 'rgb(20 83 45 / 0.35)', border: '1px solid #14532d', color: '#4ade80' }}
                  >
                    <CheckCircleIcon className="w-3.5 h-3.5" />
                    SLA Met
                  </div>
                ) : (
                  <div
                    className="flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium"
                    style={{ background: 'rgb(127 29 29 / 0.35)', border: '1px solid #7f1d1d', color: '#f87171' }}
                  >
                    <ExclamationTriangleIcon className="w-3.5 h-3.5" />
                    Below Target
                  </div>
                )}
              </div>

              {/* Progress bar */}
              <div>
                <div className="w-full h-2 rounded-full overflow-hidden" style={{ background: 'rgb(var(--hover))' }}>
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${Math.min(parseFloat(slaCompliance), 100)}%`,
                      background: slaMet
                        ? 'linear-gradient(90deg, #22c55e, #4ade80)'
                        : 'linear-gradient(90deg, #ef4444, #f87171)',
                    }}
                  />
                </div>
              </div>

              {/* Extra metrics */}
              {sla?.uptimePercent != null && (
                <div className="flex justify-between text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                  <span>Uptime</span>
                  <span className="font-mono font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {sla.uptimePercent.toFixed(2)}%
                  </span>
                </div>
              )}
              {sla?.avgLatencyMs != null && (
                <div className="flex justify-between text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                  <span>Avg Latency</span>
                  <span className="font-mono font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {sla.avgLatencyMs}ms
                  </span>
                </div>
              )}
            </div>
          ) : (
            <div className="h-40 flex flex-col items-center justify-center gap-2">
              <ShieldCheckIcon className="w-7 h-7" style={{ color: 'rgb(var(--tx-muted))' }} />
              <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>SLA data unavailable</p>
            </div>
          )}
        </div>

        {/* Recent Transfers */}
        <div className="card lg:col-span-2">
          <p className="section-title mb-4">Recent Transfers</p>

          {recentTransfers.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                    <th className="table-header">Filename</th>
                    <th className="table-header">Status</th>
                    <th className="table-header">Size</th>
                    <th className="table-header">Time</th>
                  </tr>
                </thead>
                <tbody>
                  {recentTransfers.slice(0, 10).map((t, i) => (
                    <tr key={t.trackId || i} className="table-row">
                      <td className="table-cell">
                        <span className="font-medium text-sm truncate block max-w-[200px]">{t.filename || t.fileName || '—'}</span>
                        <span className="text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
                          {t.trackId || ''}
                        </span>
                      </td>
                      <td className="table-cell">
                        <StatusBadge status={t.status} />
                      </td>
                      <td className="table-cell">
                        <span className="font-mono text-xs">{t.size || t.fileSize || '—'}</span>
                      </td>
                      <td className="table-cell">
                        <span className="text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                          {t.startedAt ? format(new Date(t.startedAt), 'MMM d, HH:mm') : '—'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="h-40 flex flex-col items-center justify-center gap-2">
              <ArrowUpTrayIcon className="w-7 h-7" style={{ color: 'rgb(var(--tx-muted))' }} />
              <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>No transfers yet</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
