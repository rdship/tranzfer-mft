import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { ArrowsRightLeftIcon, UserIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function PeerTransfers() {
  const { data: peers = [] } = useQuery({ queryKey: ['p2p-peers'], queryFn: () => onboardingApi.get('/api/p2p/presence').then(r => r.data).catch(() => []), refetchInterval: 15000 })
  const { data: tickets = [], isLoading } = useQuery({ queryKey: ['p2p-tickets'], queryFn: () => onboardingApi.get('/api/p2p/tickets').then(r => r.data).catch(() => []), refetchInterval: 10000 })

  if (isLoading) return <LoadingSpinner />
  const statusColor = { COMPLETED: 'badge-green', FAILED: 'badge-red', PENDING: 'badge-yellow', IN_PROGRESS: 'badge-blue', EXPIRED: 'badge-gray' }

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Peer-to-Peer Transfers</h1>
        <p className="text-gray-500 text-sm">Direct client-to-client file transfers</p></div>

      {/* Online Peers */}
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Online Clients ({peers.length})</h3>
        {peers.length === 0 ? <p className="text-sm text-gray-500">No clients currently online</p> : (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {peers.map(p => (
              <div key={p.username} className="flex items-center gap-2 p-3 bg-green-50 rounded-lg">
                <div className="w-2 h-2 bg-green-400 rounded-full animate-pulse" />
                <UserIcon className="w-4 h-4 text-green-600" />
                <div><p className="font-medium text-sm text-gray-900">{p.username}</p>
                  <p className="text-xs text-gray-500">{p.host}:{p.port}</p></div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Transfer Tickets */}
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Transfer History</h3>
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Track ID</th><th className="table-header">Sender</th><th className="table-header"></th>
          <th className="table-header">Receiver</th><th className="table-header">File</th><th className="table-header">Status</th><th className="table-header">Time</th>
        </tr></thead><tbody>
          {tickets.length === 0 ? (
            <tr><td colSpan={7} className="text-center py-8 text-gray-500 text-sm">No peer-to-peer transfers yet. Transfers will appear here as clients exchange files.</td></tr>
          ) : tickets.map(t => (
            <tr key={t.ticketId} className="table-row">
              <td className="table-cell font-mono text-xs font-bold text-blue-600">{t.trackId}</td>
              <td className="table-cell text-sm">{t.senderAccount?.username || '?'}</td>
              <td className="table-cell text-center"><ArrowsRightLeftIcon className="w-4 h-4 text-gray-400 inline" /></td>
              <td className="table-cell text-sm">{t.receiverAccount?.username || '?'}</td>
              <td className="table-cell text-xs text-gray-500">{t.filename}</td>
              <td className="table-cell"><span className={`badge ${statusColor[t.status] || 'badge-gray'}`}>{t.status}</span></td>
              <td className="table-cell text-xs text-gray-500">{t.createdAt ? format(new Date(t.createdAt), 'MMM d HH:mm') : ''}</td>
            </tr>
          ))}
        </tbody></table>
      </div>
    </div>
  )
}
