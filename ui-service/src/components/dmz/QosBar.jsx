export default function QosBar({ name, stats }) {
  if (!stats) return null
  const pct = stats.utilizationPercent || 0
  const color = pct > 90 ? 'bg-red-500' : pct > 70 ? 'bg-amber-500' : 'bg-blue-500'

  return (
    <div className="rounded-lg border border-gray-200 p-3">
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-sm font-medium text-gray-900">{name}</span>
        <span className="text-xs font-mono text-gray-500">
          {(stats.currentBps / 1048576).toFixed(1)} / {(stats.limitBps / 1048576).toFixed(1)} MB/s
        </span>
      </div>
      <div className="w-full bg-gray-100 rounded-full h-2.5">
        <div className={`${color} h-2.5 rounded-full transition-all duration-500`} style={{ width: `${Math.min(pct, 100)}%` }} />
      </div>
      <div className="flex justify-between mt-1 text-[10px] text-gray-400">
        <span>{pct.toFixed(1)}% utilization</span>
        <span>{stats.activeConnections || 0} conns</span>
      </div>
    </div>
  )
}
