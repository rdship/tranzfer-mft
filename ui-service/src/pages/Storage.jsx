import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { storageApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import StatCard from '../components/StatCard'
import toast from 'react-hot-toast'
import { CircleStackIcon, ArrowPathIcon, ArrowUpTrayIcon, CloudIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const tierColors = { HOT: 'bg-red-100 text-red-800', WARM: 'bg-amber-100 text-amber-800', COLD: 'bg-blue-100 text-blue-800' }

export default function Storage() {
  const qc = useQueryClient()
  const { data: metrics = {}, isLoading, isError: metricsError, refetch: refetchMetrics } = useQuery({ queryKey: ['storage-metrics'],
    queryFn: () => storageApi.get('/api/v1/storage/metrics').then(r => r.data), refetchInterval: 30000, retry: 1 })
  const { data: objects = [], isError: objectsError, refetch: refetchObjects } = useQuery({ queryKey: ['storage-objects'],
    queryFn: () => storageApi.get('/api/v1/storage/objects').then(r => r.data), retry: 1 })
  const { data: actions = [], isError: actionsError, refetch: refetchActions } = useQuery({ queryKey: ['storage-actions'],
    queryFn: () => storageApi.get('/api/v1/storage/lifecycle/actions').then(r => r.data), retry: 1 })

  const { data: drpStats = null } = useQuery({ queryKey: ['drp-stats'],
    queryFn: () => storageApi.get('/api/v1/storage/drp-stats').then(r => r.data),
    refetchInterval: 30000 })

  const isError = metricsError || objectsError || actionsError
  const refetch = () => { refetchMetrics(); refetchObjects(); refetchActions() }

  const tierMut = useMutation({ mutationFn: () => storageApi.post('/api/v1/storage/lifecycle/tier'),
    onSuccess: () => { toast.success('Tiering cycle completed'); qc.invalidateQueries({ queryKey: ['storage'] }); qc.invalidateQueries({ queryKey: ['storage-metrics'] }); qc.invalidateQueries({ queryKey: ['storage-objects'] }); qc.invalidateQueries({ queryKey: ['storage-actions'] }) },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to run tiering cycle — please try again') })
  const backupMut = useMutation({ mutationFn: () => storageApi.post('/api/v1/storage/lifecycle/backup'),
    onSuccess: () => { toast.success('Backup completed'); qc.invalidateQueries({ queryKey: ['storage'] }); qc.invalidateQueries({ queryKey: ['storage-metrics'] }); qc.invalidateQueries({ queryKey: ['storage-objects'] }); qc.invalidateQueries({ queryKey: ['storage-actions'] }) },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to run backup — please try again') })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      {isError && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">Failed to load data — service may be unavailable</span>
          </div>
          <button onClick={() => refetch()} className="text-xs text-red-400 hover:text-red-300 underline">Retry</button>
        </div>
      )}
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-primary">Storage Manager</h1>
          <p className="text-secondary text-sm">GPFS-style tiered storage with parallel I/O and AI lifecycle</p></div>
        <div className="flex gap-2">
          <button className="btn-secondary text-xs" onClick={() => tierMut.mutate()} disabled={tierMut.isPending}>
            <ArrowPathIcon className="w-3.5 h-3.5" /> {tierMut.isPending ? 'Running...' : 'Run Tiering'}
          </button>
          <button className="btn-secondary text-xs" onClick={() => backupMut.mutate()} disabled={backupMut.isPending}>
            <CloudIcon className="w-3.5 h-3.5" /> {backupMut.isPending ? 'Running...' : 'Run Backup'}
          </button>
        </div>
      </div>

      {/* Tier cards */}
      <div className="grid grid-cols-3 gap-4">
        {[{ tier: 'HOT', icon: '🔴', desc: 'NVMe/SSD — active files', count: metrics.hotCount || 0, size: metrics.hotSizeGb || 0 },
          { tier: 'WARM', icon: '🟡', desc: 'HDD/S3 — 7+ days old', count: metrics.warmCount || 0, size: metrics.warmSizeGb || 0 },
          { tier: 'COLD', icon: '🔵', desc: 'Archive — 30+ days old', count: metrics.coldCount || 0, size: metrics.coldSizeGb || 0 }
        ].map(t => (
          <div key={t.tier} className="card text-center">
            <div className="text-3xl mb-2">{t.icon}</div>
            <h3 className="font-bold text-primary">{t.tier} Tier</h3>
            <p className="text-xs text-secondary mb-3">{t.desc}</p>
            <p className="text-2xl font-bold text-primary">{t.count.toLocaleString()}</p>
            <p className="text-sm text-secondary">files</p>
            <p className="text-lg font-semibold text-secondary mt-1">{t.size} GB</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-4 gap-4">
        <StatCard title="Total Objects" value={metrics.totalObjects || 0} icon={CircleStackIcon} color="blue" />
        <StatCard title="Lifecycle Actions" value={metrics.recentActions || 0} icon={ArrowPathIcon} color="amber" />
        <StatCard title="Features" value="6" subtitle="parallel-io, tiered, dedup, backup, ai-lifecycle, pre-stage" icon={CloudIcon} color="green" />
        <StatCard title="I/O Threads" value="8" subtitle="4MB stripe, 64MB buffer" icon={ArrowUpTrayIcon} color="purple" />
      </div>

      {/* Objects table */}
      {objects.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-primary mb-3">Stored Objects ({objects.length})</h3>
          <table className="w-full"><thead><tr className="border-b">
            <th className="table-header">File</th><th className="table-header">Track ID</th><th className="table-header">Tier</th>
            <th className="table-header">Size</th><th className="table-header">Accesses</th><th className="table-header">SHA-256</th><th className="table-header">Stored</th>
          </tr></thead><tbody>
            {objects.slice(0, 50).map(o => (
              <tr key={o.id} className="table-row">
                <td className="table-cell text-sm font-medium">{o.filename}</td>
                <td className="table-cell font-mono text-xs text-blue-600">{o.trackId || '—'}</td>
                <td className="table-cell"><span className={`badge ${tierColors[o.tier] || 'badge-gray'}`}>{o.tier}</span></td>
                <td className="table-cell text-xs">{(o.sizeBytes / 1024).toFixed(1)} KB</td>
                <td className="table-cell text-xs">{o.accessCount}</td>
                <td className="table-cell font-mono text-xs text-muted">{o.sha256?.substring(0,12)}...</td>
                <td className="table-cell text-xs text-secondary">{o.createdAt ? format(new Date(o.createdAt), 'MMM d HH:mm') : ''}</td>
              </tr>
            ))}
          </tbody></table>
        </div>
      )}

      {/* Lifecycle actions */}
      {actions.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-primary mb-3">Recent Lifecycle Actions</h3>
          <div className="space-y-1">{actions.slice(0, 20).map((a, i) => (
            <div key={i} className="flex items-center gap-3 text-sm py-1">
              <span className="badge badge-blue text-xs">{a.action}</span>
              <span className="font-medium">{a.filename}</span>
              <span className="text-muted text-xs">{(a.sizeBytes / 1024).toFixed(0)} KB</span>
              <span className="text-muted text-xs ml-auto">{a.timestamp ? format(new Date(a.timestamp), 'HH:mm:ss') : ''}</span>
            </div>
          ))}</div>
        </div>
      )}

      {/* DRP Engine Stats */}
      {drpStats && (
        <div className="card">
          <h3 className="font-semibold text-primary mb-1">DRP Engine Stats</h3>
          <p className="text-secondary text-xs mb-4">Data Replication & Protection engine status and I/O lane metrics</p>
          {drpStats.ioLanes && Object.keys(drpStats.ioLanes).length > 0 ? (
            <div className="grid grid-cols-3 gap-4">
              {Object.entries(drpStats.ioLanes).map(([key, val]) => (
                <div key={key} className="border rounded-lg p-3 bg-canvas text-center">
                  <span className="text-sm text-secondary block">{key}</span>
                  <span className="text-lg font-bold text-primary">{typeof val === 'number' ? val.toLocaleString() : String(val)}</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="grid grid-cols-3 gap-4">
              {Object.entries(drpStats).filter(([k]) => k !== 'ioLanes').map(([key, val]) => (
                <div key={key} className="border rounded-lg p-3 bg-canvas text-center">
                  <span className="text-sm text-secondary block">{key.replace(/([A-Z])/g, ' $1').trim()}</span>
                  <span className="text-lg font-bold text-primary">{typeof val === 'number' ? val.toLocaleString() : typeof val === 'boolean' ? (val ? 'Yes' : 'No') : String(val)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
