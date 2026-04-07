import { useQuery } from '@tanstack/react-query'
import { getVfsDashboard, getVfsRecentIntents } from '../api/config'
import LoadingSpinner from '../components/LoadingSpinner'
import StatCard from '../components/StatCard'
import { CircleStackIcon, ShieldCheckIcon, ExclamationTriangleIcon, ClockIcon,
         ServerStackIcon, CubeIcon, ArrowsRightLeftIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const bucketMeta = {
  inline:   { icon: '⚡', color: 'bg-emerald-100 text-emerald-800', desc: '< 64KB \u2014 stored in DB row' },
  standard: { icon: '📦', color: 'bg-blue-100 text-blue-800',    desc: '64KB\u201364MB \u2014 CAS via Storage Manager' },
  chunked:  { icon: '🧩', color: 'bg-purple-100 text-purple-800', desc: '> 64MB \u2014 4MB chunk streaming' }
}

const statusColors = {
  PENDING:    'bg-yellow-100 text-yellow-800',
  COMMITTED:  'bg-green-100 text-green-800',
  ABORTED:    'bg-red-100 text-red-800',
  RECOVERING: 'bg-orange-100 text-orange-800'
}

const opIcons = { WRITE: '✏️', DELETE: '🗑️', MOVE: '📁' }

export default function VfsStorage() {
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['vfs-dashboard'],
    queryFn: getVfsDashboard,
    refetchInterval: 15000
  })

  const { data: recentIntents = [] } = useQuery({
    queryKey: ['vfs-recent-intents'],
    queryFn: () => getVfsRecentIntents(50),
    refetchInterval: 15000
  })

  if (isLoading) return <LoadingSpinner />

  const buckets = dashboard?.buckets || {}
  const intents = dashboard?.intents || {}
  const totals = dashboard?.totals || {}

  const formatSize = (bytes) => {
    if (!bytes || bytes === 0) return '0 B'
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    const i = Math.floor(Math.log(bytes) / Math.log(1024))
    return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i]
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Virtual Filesystem Storage</h1>
        <p className="text-gray-500 text-sm">WAIP-protected storage with smart bucket routing across SFTP, FTP, and FTP-Web</p>
      </div>

      {/* Health banner */}
      {intents.healthy === false && (
        <div className="rounded-lg bg-red-50 border border-red-200 p-4 flex items-center gap-3">
          <ExclamationTriangleIcon className="w-5 h-5 text-red-600 shrink-0" />
          <div>
            <p className="font-semibold text-red-800">Intent Protocol Alert</p>
            <p className="text-sm text-red-700">
              {intents.staleCount > 0 && `${intents.staleCount} stale intent(s) detected on pod(s): ${(intents.stalePods || []).join(', ')}. `}
              {intents.recovering > 0 && `${intents.recovering} intent(s) currently recovering. `}
              Recovery job runs every 2 minutes.
            </p>
          </div>
        </div>
      )}
      {intents.healthy === true && (
        <div className="rounded-lg bg-green-50 border border-green-200 p-4 flex items-center gap-3">
          <ShieldCheckIcon className="w-5 h-5 text-green-600 shrink-0" />
          <div>
            <p className="font-semibold text-green-800">All Systems Healthy</p>
            <p className="text-sm text-green-700">Zero stale intents. WAIP protocol operating normally across all pods.</p>
          </div>
        </div>
      )}

      {/* Bucket distribution */}
      <div>
        <h2 className="text-lg font-semibold text-gray-900 mb-3">Storage Bucket Distribution</h2>
        <div className="grid grid-cols-3 gap-4">
          {['inline', 'standard', 'chunked'].map(key => {
            const b = buckets[key] || {}
            const meta = bucketMeta[key]
            const total = buckets.totalFiles || 1
            const pct = total > 0 ? ((b.count || 0) / total * 100).toFixed(1) : 0
            return (
              <div key={key} className="card text-center">
                <div className="text-3xl mb-2">{meta.icon}</div>
                <h3 className="font-bold text-gray-900">{b.label || key.toUpperCase()}</h3>
                <p className="text-xs text-gray-500 mb-3">{meta.desc}</p>
                <p className="text-2xl font-bold text-gray-900">{(b.count || 0).toLocaleString()}</p>
                <p className="text-sm text-gray-500">files ({pct}%)</p>
                <p className="text-lg font-semibold text-gray-700 mt-1">{formatSize(b.sizeBytes || 0)}</p>
                {/* Progress bar */}
                <div className="mt-3 w-full bg-gray-200 rounded-full h-2">
                  <div className={`h-2 rounded-full ${key === 'inline' ? 'bg-emerald-500' : key === 'standard' ? 'bg-blue-500' : 'bg-purple-500'}`}
                       style={{ width: `${Math.max(pct, 1)}%` }} />
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Stats row */}
      <div className="grid grid-cols-4 gap-4">
        <StatCard title="Total Files" value={(buckets.totalFiles || 0).toLocaleString()} icon={CircleStackIcon} color="blue" />
        <StatCard title="Total Chunks" value={(buckets.totalChunks || 0).toLocaleString()} icon={CubeIcon} color="purple" />
        <StatCard title="Total Intents" value={(totals.totalIntents || 0).toLocaleString()} icon={ClockIcon} color="amber" />
        <StatCard title="Committed" value={(intents.committed || 0).toLocaleString()} icon={ShieldCheckIcon} color="green" />
      </div>

      {/* Intent health breakdown */}
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Write-Ahead Intent Protocol</h3>
        <div className="grid grid-cols-4 gap-3">
          {[
            { label: 'PENDING', count: intents.pending || 0, color: 'border-yellow-400 bg-yellow-50', desc: 'In-flight operations' },
            { label: 'COMMITTED', count: intents.committed || 0, color: 'border-green-400 bg-green-50', desc: 'Successfully completed' },
            { label: 'ABORTED', count: intents.aborted || 0, color: 'border-red-400 bg-red-50', desc: 'Rolled back / cancelled' },
            { label: 'RECOVERING', count: intents.recovering || 0, color: 'border-orange-400 bg-orange-50', desc: 'Crash recovery in progress' }
          ].map(s => (
            <div key={s.label} className={`rounded-lg border-l-4 p-3 ${s.color}`}>
              <p className="text-2xl font-bold text-gray-900">{s.count.toLocaleString()}</p>
              <p className="text-sm font-medium text-gray-700">{s.label}</p>
              <p className="text-xs text-gray-500">{s.desc}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Recent intents table */}
      {recentIntents.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-3">Recent Intents ({recentIntents.length})</h3>
          <div className="overflow-x-auto">
            <table className="w-full"><thead><tr className="border-b">
              <th className="table-header">Op</th>
              <th className="table-header">Path</th>
              <th className="table-header">Status</th>
              <th className="table-header">Bucket</th>
              <th className="table-header">Size</th>
              <th className="table-header">Pod</th>
              <th className="table-header">Created</th>
              <th className="table-header">Resolved</th>
            </tr></thead><tbody>
              {recentIntents.map(i => (
                <tr key={i.id} className="table-row">
                  <td className="table-cell text-center">{opIcons[i.op] || i.op} <span className="text-xs">{i.op}</span></td>
                  <td className="table-cell font-mono text-xs max-w-xs truncate" title={i.destPath ? `${i.path} → ${i.destPath}` : i.path}>
                    {i.path}
                    {i.destPath && <span className="text-gray-400"> → {i.destPath}</span>}
                  </td>
                  <td className="table-cell">
                    <span className={`badge ${statusColors[i.status] || 'badge-gray'}`}>{i.status}</span>
                  </td>
                  <td className="table-cell text-xs">{i.storageBucket}</td>
                  <td className="table-cell text-xs">{i.sizeBytes > 0 ? formatSize(i.sizeBytes) : '\u2014'}</td>
                  <td className="table-cell font-mono text-xs text-gray-500" title={i.podId}>
                    {i.podId?.length > 15 ? i.podId.substring(0, 15) + '...' : i.podId}
                  </td>
                  <td className="table-cell text-xs text-gray-500">
                    {i.createdAt ? format(new Date(i.createdAt), 'MMM d HH:mm:ss') : ''}
                  </td>
                  <td className="table-cell text-xs text-gray-500">
                    {i.resolvedAt ? format(new Date(i.resolvedAt), 'MMM d HH:mm:ss') : '\u2014'}
                  </td>
                </tr>
              ))}
            </tbody></table>
          </div>
        </div>
      )}
    </div>
  )
}
