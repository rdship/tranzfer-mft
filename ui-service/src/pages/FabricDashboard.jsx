import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { formatDistanceToNow } from 'date-fns'
import {
  Square3Stack3DIcon,
  ServerStackIcon,
  ExclamationTriangleIcon,
  ClockIcon,
  CpuChipIcon,
  BoltIcon,
} from '@heroicons/react/24/outline'
import {
  getFabricQueues,
  getFabricInstances,
  getFabricStuck,
  getFabricLatency,
} from '../api/fabric'

export default function FabricDashboard() {
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

  const anyError = qErr || iErr || sErr || lErr

  const totalInProgress = queues?.inProgressByStepType
    ? Object.values(queues.inProgressByStepType).reduce((a, b) => a + b, 0)
    : 0

  const activeInstances = instances?.active?.length || 0
  const deadInstances = instances?.dead?.length || 0
  const stuckCount = Array.isArray(stuck) ? stuck.length : 0

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-primary flex items-center gap-2">
          <BoltIcon className="w-6 h-6 text-yellow-500" />
          Flow Fabric
        </h1>
        <p className="text-secondary text-sm mt-1">
          Distributed work orchestration &mdash; real-time visibility across all instances
        </p>
      </div>

      {anyError && (
        <div className="bg-yellow-500/10 border border-yellow-500/30 rounded-xl px-4 py-3">
          <p className="text-sm text-yellow-500">
            Fabric data unavailable. This may mean Fabric is disabled or no files have been processed through it yet.
          </p>
        </div>
      )}

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <KpiCard
          icon={<Square3Stack3DIcon className="w-5 h-5" />}
          label="In Progress"
          value={totalInProgress}
          color="blue"
        />
        <KpiCard
          icon={<ServerStackIcon className="w-5 h-5" />}
          label="Active Instances"
          value={activeInstances}
          sub={deadInstances > 0 ? `${deadInstances} dead` : null}
          color={deadInstances > 0 ? 'yellow' : 'green'}
        />
        <KpiCard
          icon={<ExclamationTriangleIcon className="w-5 h-5" />}
          label="Stuck Files"
          value={stuckCount}
          color={stuckCount > 0 ? 'red' : 'green'}
        />
        <KpiCard
          icon={<ClockIcon className="w-5 h-5" />}
          label="Sample Size (1h)"
          value={latency?.sampleCount || 0}
          color="gray"
        />
      </div>

      {/* Queue Depths */}
      <div className="card">
        <h2 className="text-lg font-semibold text-primary mb-3">Queue Depths by Step Type</h2>
        {queues?.inProgressByStepType && Object.keys(queues.inProgressByStepType).length > 0 ? (
          <div className="space-y-2">
            {Object.entries(queues.inProgressByStepType).map(([type, count]) => (
              <div
                key={type}
                className="flex items-center justify-between py-2 border-b border-border last:border-b-0"
              >
                <span className="text-sm font-mono text-primary">{type}</span>
                <div className="flex items-center gap-3">
                  <div
                    className="h-2 bg-blue-500 rounded"
                    style={{ width: `${Math.min(count * 10, 200)}px` }}
                  />
                  <span className="text-sm text-secondary w-8 text-right">{count}</span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-secondary text-sm italic">No work in progress.</p>
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
          <p className="text-secondary text-sm italic">No active instances registered.</p>
        )}
      </div>

      {/* Stuck Files */}
      {stuckCount > 0 && (
        <div className="card border-red-500/30">
          <h2 className="text-lg font-semibold text-red-500 mb-3 flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5" />
            Stuck Files ({stuckCount})
          </h2>
          <div className="space-y-2">
            {stuck.map(s => (
              <div
                key={`${s.trackId}-${s.stepIndex}`}
                className="flex items-center justify-between py-2 border-b border-border last:border-b-0"
              >
                <div>
                  <Link
                    to={`/journey?trackId=${s.trackId}`}
                    className="text-sm font-mono text-blue-500 hover:underline"
                    style={{ color: 'rgb(100,140,255)' }}
                  >
                    {s.trackId}
                  </Link>
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
                  <tr key={type} className="border-b border-border last:border-b-0">
                    <td className="py-2 font-mono text-primary">{type}</td>
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
          <p className="text-secondary text-sm italic">No completed steps in the last hour.</p>
        )}
      </div>
    </div>
  )
}

function KpiCard({ icon, label, value, sub, color }) {
  const colorMap = {
    blue: 'text-blue-500',
    green: 'text-green-500',
    yellow: 'text-yellow-500',
    red: 'text-red-500',
    gray: 'text-muted',
  }
  const textClass = colorMap[color] || 'text-primary'
  return (
    <div className="card">
      <div className="flex items-start justify-between">
        <div>
          <div className="text-xs text-secondary uppercase tracking-wide">{label}</div>
          <div className={`text-3xl font-bold mt-1 ${textClass}`}>{value}</div>
          {sub && <div className="text-xs text-secondary mt-1">{sub}</div>}
        </div>
        <div className={textClass}>{icon}</div>
      </div>
    </div>
  )
}
