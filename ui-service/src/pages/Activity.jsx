import { useQuery } from '@tanstack/react-query'
import { configApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import StatCard from '../components/StatCard'
import { SignalIcon, ArrowUpTrayIcon, ArrowDownTrayIcon, WifiIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Activity() {
  const { data: snapshot = {}, isLoading } = useQuery({ queryKey: ['activity-snap'],
    queryFn: () => configApi.get('/api/activity/snapshot').then(r => r.data).catch(() => ({})), refetchInterval: 5000 })
  const { data: transfers = [] } = useQuery({ queryKey: ['activity-transfers'],
    queryFn: () => configApi.get('/api/activity/transfers').then(r => r.data).catch(() => []), refetchInterval: 5000 })
  const { data: events = [] } = useQuery({ queryKey: ['activity-events'],
    queryFn: () => configApi.get('/api/activity/events?limit=50').then(r => r.data).catch(() => []), refetchInterval: 5000 })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Real-Time Activity</h1>
        <p className="text-secondary text-sm">Live view — auto-refreshes every 5 seconds</p></div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard title="SFTP Connections" value={snapshot.activeSftpConnections || 0} icon={SignalIcon} color="blue" />
        <StatCard title="FTP Connections" value={snapshot.activeFtpConnections || 0} icon={SignalIcon} color="amber" />
        <StatCard title="Active Transfers" value={snapshot.activeTransfers || 0} icon={ArrowUpTrayIcon} color="green" />
        <StatCard title="Last 5 Min" value={snapshot.transfersLast5Min || 0} icon={WifiIcon} color="purple" />
      </div>

      {transfers.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-3 flex items-center gap-2">
            <div className="w-2 h-2 bg-green-400 rounded-full animate-pulse" /> Active Transfers
          </h3>
          <div className="space-y-2">{transfers.map((t, i) => (
            <div key={i} className="flex items-center gap-3 p-2 bg-green-50 rounded-lg text-sm">
              <ArrowUpTrayIcon className="w-4 h-4 text-green-600" />
              <span className="font-medium">{t.filename || 'unknown'}</span>
              <span className="badge badge-blue">{t.protocol}</span>
              <span className="text-secondary">{t.account}</span>
              {t.fileSizeBytes && <span className="text-xs text-muted">{(t.fileSizeBytes / 1024).toFixed(0)} KB</span>}
              <span className="badge badge-green ml-auto">{t.status}</span>
            </div>
          ))}</div>
        </div>
      )}

      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Recent Events ({events.length})</h3>
        {events.length === 0 ? <p className="text-sm text-secondary">No events yet. Activity will appear here as files are transferred.</p> : (
          <div className="space-y-1 max-h-96 overflow-y-auto">{events.map((e, i) => (
            <div key={i} className="flex items-center gap-2 text-xs py-1 border-b border-gray-50">
              <span className="text-muted w-16 font-mono">{e.timestamp ? format(new Date(e.timestamp), 'HH:mm:ss') : ''}</span>
              <span className={`badge text-xs ${e.status === 'COMPLETED' ? 'badge-green' : e.status === 'FAILED' ? 'badge-red' : 'badge-blue'}`}>{e.eventType}</span>
              <span className="badge badge-gray text-xs">{e.protocol}</span>
              <span className="font-medium text-gray-700">{e.filename || ''}</span>
              <span className="text-muted">{e.account}</span>
              {e.trackId && <span className="font-mono text-blue-500 ml-auto">{e.trackId}</span>}
            </div>
          ))}</div>
        )}
      </div>
    </div>
  )
}
