import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { formatDistanceToNow } from 'date-fns'
import {
  Square3Stack3DIcon,
  ServerStackIcon,
  ExclamationTriangleIcon,
  ClockIcon,
  CpuChipIcon,
  BoltIcon,
  ArrowTopRightOnSquareIcon,
  ChartBarIcon,
} from '@heroicons/react/24/outline'
import {
  getFabricQueues,
  getFabricInstances,
  getFabricStuck,
  getFabricLatency,
} from '../api/fabric'
import { onboardingApi } from '../api/client'
import CopyButton from '../components/CopyButton'

/**
 * FabricDashboard — headline view for the Flow Fabric feature.
 *
 * Design principles (locked):
 *   • Speed            — 5s refetch on hot panels, 30s on latency (slow-changing)
 *   • Accuracy         — every card reflects live API state, no mocks
 *   • Stability        — every fetch has its own error guard; one failure doesn't blank the page
 *   • Resilience       — graceful empty states for every panel
 *   • Transparency     — countEvery card exposes counts, last-heartbeat timestamps, error categories
 *   • Flexibility      — KPI cards click through to Activity Monitor with pre-applied filters
 *   • Attractiveness   — dark palette, monospaced trackIds, color-coded severity
 *   • Minimalism       — one accent per card, clean tables, no gratuitous chart chrome
 *   • Guidance         — every empty state explains what to do next ("Upload a file to see…")
 */
export default function FabricDashboard() {
  const navigate = useNavigate()

  const { data: queues, isError: qErr } = useQuery({
    queryKey: ['fabric-queues'],
    queryFn: getFabricQueues,
    refetchInterval: 5000,
    retry: 1,
  })

  const { data: instances, isError: iErr } = useQuery({
    queryKey: ['fabric-instances'],
    queryFn: getFabricInstances,
    refetchInterval: 10000,
    retry: 1,
  })

  const { data: stuck, isError: sErr } = useQuery({
    queryKey: ['fabric-stuck'],
    queryFn: getFabricStuck,
    refetchInterval: 10000,
    retry: 1,
  })

  const { data: latency, isError: lErr } = useQuery({
    queryKey: ['fabric-latency'],
    queryFn: getFabricLatency,
    refetchInterval: 30000,
    retry: 1,
  })

  // Recent activity — inline last-10 executions, drives "see live throughput" without
  // leaving the page. Cross-links each row to Activity Monitor with pre-applied filter.
  const { data: recentActivity } = useQuery({
    queryKey: ['fabric-recent-activity'],
    queryFn: () =>
      onboardingApi
        .get('/api/activity-monitor', { params: { page: 0, size: 10, sortBy: 'uploadedAt', sortDir: 'DESC' } })
        .then(r => r.data),
    refetchInterval: 5000,
  })

  const anyError = qErr || iErr || sErr || lErr

  const totalInProgress = queues?.inProgressByStepType
    ? Object.values(queues.inProgressByStepType).reduce((a, b) => a + b, 0)
    : 0

  const activeInstances = instances?.active?.length || 0
  const deadInstances = instances?.dead?.length || 0
  const stuckItems = Array.isArray(stuck) ? stuck : (stuck?.items || [])
  const stuckCount = stuck?.totalElements ?? stuckItems.length
  const recentRows = recentActivity?.content || []

  // Max queue value for bar scaling — keeps the UI stable as counts rise.
  const maxQueueVal = queues?.inProgressByStepType
    ? Math.max(1, ...Object.values(queues.inProgressByStepType))
    : 1

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary flex items-center gap-2">
            <BoltIcon className="w-6 h-6 text-yellow-500" />
            Flow Fabric
          </h1>
          <p className="text-secondary text-sm mt-1">
            Distributed work orchestration &mdash; real-time visibility across all instances
          </p>
        </div>
        <div className="flex gap-2">
          <Link
            to="/operations/activity"
            className="flex items-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-lg border transition-colors"
            style={{ borderColor: 'rgb(30, 30, 36)', color: 'rgb(160, 165, 175)' }}
            title="Open full Activity Monitor with search, filters, and bulk actions"
          >
            <ChartBarIcon className="w-4 h-4" />
            Activity Monitor
            <ArrowTopRightOnSquareIcon className="w-3 h-3" />
          </Link>
        </div>
      </div>

      {anyError && (
        <div className="bg-yellow-500/10 border border-yellow-500/30 rounded-xl px-4 py-3">
          <p className="text-sm text-yellow-500">
            Fabric data unavailable. This may mean Fabric is disabled or no files have been processed through it yet.
          </p>
        </div>
      )}

      {/* KPI Cards — each card is clickable and deep-links to Activity Monitor with a filter */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <KpiCard
          icon={<Square3Stack3DIcon className="w-5 h-5" />}
          label="In Progress"
          value={totalInProgress}
          color="blue"
          onClick={() => navigate('/operations/activity?status=IN_PROGRESS')}
          hint="View all in-progress transfers"
        />
        <KpiCard
          icon={<ServerStackIcon className="w-5 h-5" />}
          label="Active Instances"
          value={activeInstances}
          sub={deadInstances > 0 ? `${deadInstances} dead` : null}
          color={deadInstances > 0 ? 'yellow' : 'green'}
          hint={`${activeInstances} pods healthy, ${deadInstances} unresponsive`}
        />
        <KpiCard
          icon={<ExclamationTriangleIcon className="w-5 h-5" />}
          label="Stuck Files"
          value={stuckCount}
          color={stuckCount > 0 ? 'red' : 'green'}
          onClick={stuckCount > 0 ? () => navigate('/operations/activity?stuckOnly=true') : null}
          hint={stuckCount > 0 ? 'Click to filter Activity Monitor' : 'No stuck work'}
        />
        <KpiCard
          icon={<ClockIcon className="w-5 h-5" />}
          label="Sample Size (1h)"
          value={latency?.sampleCount || 0}
          color="gray"
          hint="Completed steps in the last hour"
        />
      </div>

      {/* Queue Depths — each row is clickable, filters Activity Monitor by stepType */}
      <div className="card">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-primary">Queue Depths by Step Type</h2>
          <span className="text-xs text-secondary">Click a row to filter Activity Monitor</span>
        </div>
        {queues?.inProgressByStepType && Object.keys(queues.inProgressByStepType).length > 0 ? (
          <div className="space-y-1">
            {Object.entries(queues.inProgressByStepType)
              .sort((a, b) => b[1] - a[1])
              .map(([type, count]) => (
                <button
                  key={type}
                  onClick={() =>
                    navigate(`/operations/activity?stepType=${encodeURIComponent(type)}&status=IN_PROGRESS`)
                  }
                  className="w-full flex items-center justify-between py-2 px-2 rounded transition-colors hover:bg-surface-hover text-left"
                  style={{ borderBottom: '1px solid rgb(30, 30, 36)' }}
                  title={`Filter Activity Monitor by ${type}`}
                >
                  <span className="text-sm font-mono text-primary">{type}</span>
                  <div className="flex items-center gap-3">
                    <div className="h-2 bg-blue-500 rounded" style={{ width: `${(count / maxQueueVal) * 200}px` }} />
                    <span className="text-sm text-secondary w-8 text-right">{count}</span>
                  </div>
                </button>
              ))}
          </div>
        ) : (
          <EmptyState
            title="No work in progress"
            hint="Upload a file via File Manager or SFTP to see queue depths light up here."
            action={{ label: 'Open File Manager', to: '/file-manager' }}
          />
        )}
      </div>

      {/* Recent Activity — inline last-10 executions, no page swap required */}
      <div className="card">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-primary flex items-center gap-2">
            <ChartBarIcon className="w-5 h-5 text-blue-500" />
            Recent Activity
          </h2>
          <Link
            to="/operations/activity"
            className="text-xs text-blue-500 hover:underline flex items-center gap-1"
            style={{ color: 'rgb(100, 140, 255)' }}
          >
            View all
            <ArrowTopRightOnSquareIcon className="w-3 h-3" />
          </Link>
        </div>
        {recentRows.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-secondary text-xs uppercase">
                  <th className="text-left py-2 pr-3">TrackId</th>
                  <th className="text-left py-2 pr-3">File</th>
                  <th className="text-left py-2 pr-3">Status</th>
                  <th className="text-left py-2 pr-3">Current Step</th>
                  <th className="text-left py-2 pr-3">When</th>
                </tr>
              </thead>
              <tbody>
                {recentRows.map(r => (
                  <tr key={r.trackId} className="border-b border-border last:border-b-0 hover:bg-surface-hover">
                    <td className="py-2 pr-3">
                      <div className="inline-flex items-center gap-1">
                        <Link
                          to={`/operations/journey?trackId=${r.trackId}`}
                          className="font-mono text-xs hover:underline"
                          style={{ color: 'rgb(100, 140, 255)' }}
                        >
                          {r.trackId}
                        </Link>
                        <CopyButton value={r.trackId} label="trackId" size="xs" />
                      </div>
                    </td>
                    <td className="py-2 pr-3 text-xs text-primary truncate max-w-xs">{r.filename}</td>
                    <td className="py-2 pr-3">
                      <StatusPill status={r.status} />
                    </td>
                    <td className="py-2 pr-3 text-xs font-mono text-secondary">{r.currentStepType || '—'}</td>
                    <td className="py-2 pr-3 text-xs text-secondary">
                      {r.uploadedAt ? formatDistanceToNow(new Date(r.uploadedAt), { addSuffix: true }) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="No recent transfers"
            hint="Transfers processed through the Fabric will appear here as they happen."
          />
        )}
      </div>

      {/* Instances Grid */}
      <div className="card">
        <h2 className="text-lg font-semibold text-primary mb-3">Active Instances</h2>
        {activeInstances > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {instances.active.map(inst => (
              <div key={inst.instanceId} className="border border-border rounded-lg p-3 bg-surface">
                <div className="flex items-center gap-2 mb-1">
                  <CpuChipIcon className="w-4 h-4 text-green-500" />
                  <span className="text-sm font-mono text-primary truncate">{inst.instanceId}</span>
                </div>
                <div className="text-xs text-secondary">{inst.serviceName}</div>
                <div className="text-xs text-muted mt-1">
                  Heartbeat:{' '}
                  {inst.lastHeartbeat
                    ? formatDistanceToNow(new Date(inst.lastHeartbeat), { addSuffix: true })
                    : 'unknown'}
                </div>
                <div className="text-xs text-muted">In-flight: {inst.inFlightCount ?? 0}</div>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="No active instances"
            hint="Fabric consumers register themselves on boot. Check that onboarding-api and sftp-service are healthy."
          />
        )}
      </div>

      {/* Stuck Files */}
      {stuckCount > 0 && (
        <div className="card border-red-500/30">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-lg font-semibold text-red-500 flex items-center gap-2">
              <ExclamationTriangleIcon className="w-5 h-5" />
              Stuck Files ({stuckCount})
            </h2>
            <Link
              to="/operations/activity?stuckOnly=true"
              className="text-xs text-blue-500 hover:underline flex items-center gap-1"
              style={{ color: 'rgb(100, 140, 255)' }}
            >
              Open in Activity Monitor
              <ArrowTopRightOnSquareIcon className="w-3 h-3" />
            </Link>
          </div>
          <div className="space-y-2">
            {stuckItems.map(s => (
              <div
                key={`${s.trackId}-${s.stepIndex}`}
                className="flex items-center justify-between py-2 border-b border-border last:border-b-0"
              >
                <div>
                  <span className="inline-flex items-center gap-1">
                    <Link
                      to={`/operations/journey?trackId=${s.trackId}`}
                      className="text-sm font-mono hover:underline"
                      style={{ color: 'rgb(100, 140, 255)' }}
                    >
                      {s.trackId}
                    </Link>
                    <CopyButton value={s.trackId} label="trackId" size="xs" />
                  </span>
                  <span className="text-xs text-secondary ml-2">
                    step {s.stepIndex} ({s.stepType})
                  </span>
                </div>
                <div className="text-xs text-red-500">
                  Stuck for {Math.floor((s.stuckForMs || 0) / 1000)}s on {s.instance}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Latency */}
      <div className="card">
        <h2 className="text-lg font-semibold text-primary mb-3">
          Step Latency (Last Hour, P50 / P95 / P99)
        </h2>
        {latency?.byStepType && Object.keys(latency.byStepType).length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-secondary text-xs uppercase">
                  <th className="text-left py-2">Step Type</th>
                  <th className="text-right py-2">Count</th>
                  <th className="text-right py-2">Min</th>
                  <th className="text-right py-2">P50</th>
                  <th className="text-right py-2">P95</th>
                  <th className="text-right py-2">P99</th>
                  <th className="text-right py-2">Max</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(latency.byStepType).map(([type, stats]) => (
                  <tr key={type} className="border-b border-border last:border-b-0 hover:bg-surface-hover">
                    <td className="py-2 font-mono text-primary">
                      <Link
                        to={`/operations/activity?stepType=${encodeURIComponent(type)}`}
                        className="hover:underline"
                      >
                        {type}
                      </Link>
                    </td>
                    <td className="text-right text-secondary">{stats.count}</td>
                    <td className="text-right text-secondary">{stats.min}ms</td>
                    <td className="text-right text-primary">{stats.p50}ms</td>
                    <td className="text-right text-yellow-500">{stats.p95}ms</td>
                    <td className="text-right text-red-500">{stats.p99}ms</td>
                    <td className="text-right text-secondary">{stats.max}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="No completed steps in the last hour"
            hint="Percentiles will populate as the Fabric processes transfers."
          />
        )}
      </div>
    </div>
  )
}

function KpiCard({ icon, label, value, sub, color, onClick, hint }) {
  const colorMap = {
    blue: 'text-blue-500',
    green: 'text-green-500',
    yellow: 'text-yellow-500',
    red: 'text-red-500',
    gray: 'text-muted',
  }
  const textClass = colorMap[color] || 'text-primary'
  const clickable = typeof onClick === 'function'
  return (
    <button
      onClick={clickable ? onClick : undefined}
      disabled={!clickable}
      title={hint}
      className={`card text-left ${clickable ? 'transition-colors hover:bg-surface-hover cursor-pointer' : 'cursor-default'}`}
    >
      <div className="flex items-start justify-between">
        <div>
          <div className="text-xs text-secondary uppercase tracking-wide">{label}</div>
          <div className={`text-3xl font-bold mt-1 ${textClass}`}>{value}</div>
          {sub && <div className="text-xs text-secondary mt-1">{sub}</div>}
        </div>
        <div className={textClass}>{icon}</div>
      </div>
    </button>
  )
}

function StatusPill({ status }) {
  const map = {
    COMPLETED:     { bg: 'rgba(34, 197, 94, 0.15)',  text: 'rgb(74, 222, 128)' },
    MOVED_TO_SENT: { bg: 'rgba(34, 197, 94, 0.15)',  text: 'rgb(74, 222, 128)' },
    FAILED:        { bg: 'rgba(239, 68, 68, 0.15)',  text: 'rgb(248, 113, 113)' },
    IN_OUTBOX:     { bg: 'rgba(59, 130, 246, 0.15)', text: 'rgb(96, 165, 250)' },
    DOWNLOADED:    { bg: 'rgba(59, 130, 246, 0.15)', text: 'rgb(96, 165, 250)' },
    PROCESSING:    { bg: 'rgba(59, 130, 246, 0.15)', text: 'rgb(96, 165, 250)' },
    PENDING:       { bg: 'rgba(234, 179, 8, 0.15)',  text: 'rgb(250, 204, 21)' },
  }
  const style = map[status] || { bg: 'rgba(100, 100, 100, 0.15)', text: 'rgb(148, 163, 184)' }
  return (
    <span
      className="inline-block px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wide"
      style={{ background: style.bg, color: style.text }}
    >
      {status}
    </span>
  )
}

function EmptyState({ title, hint, action }) {
  return (
    <div className="text-center py-6">
      <p className="text-secondary text-sm font-medium">{title}</p>
      {hint && <p className="text-muted text-xs mt-1">{hint}</p>}
      {action && (
        <Link
          to={action.to}
          className="inline-block mt-3 text-xs font-semibold px-3 py-1.5 rounded-lg border transition-colors hover:bg-surface-hover"
          style={{ borderColor: 'rgb(30, 30, 36)', color: 'rgb(100, 140, 255)' }}
        >
          {action.label} →
        </Link>
      )}
    </div>
  )
}
