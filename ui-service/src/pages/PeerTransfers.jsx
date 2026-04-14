import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { ArrowsRightLeftIcon, UserIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function PeerTransfers() {
  const [expandedTicket, setExpandedTicket] = useState(null)
  const { data: peers = [], isError: peersError, refetch: refetchPeers } = useQuery({ queryKey: ['p2p-peers'], queryFn: () => onboardingApi.get('/api/p2p/presence').then(r => r.data), meta: { silent: true }, refetchInterval: 15000, retry: 1 })
  const { data: tickets = [], isLoading, isError: ticketsError, refetch: refetchTickets } = useQuery({ queryKey: ['p2p-tickets'], queryFn: () => onboardingApi.get('/api/p2p/tickets').then(r => r.data), meta: { silent: true }, refetchInterval: 10000, retry: 1 })

  const isError = peersError || ticketsError
  const refetch = () => { refetchPeers(); refetchTickets() }

  if (isLoading) return <LoadingSpinner />
  const statusColor = { COMPLETED: 'badge-green', FAILED: 'badge-red', PENDING: 'badge-yellow', IN_PROGRESS: 'badge-blue', EXPIRED: 'badge-gray' }

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
      <div><h1 className="text-2xl font-bold text-primary">Peer-to-Peer Transfers</h1>
        <p className="text-secondary text-sm">Direct client-to-client file transfers</p></div>

      {/* Online Peers */}
      <div className="card">
        <h3 className="font-semibold text-primary mb-3">Online Clients ({peers.length})</h3>
        {peers.length === 0 ? <p className="text-sm text-secondary">No clients currently online</p> : (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {peers.map(p => (
              <div key={p.username} className="flex items-center gap-2 p-3 bg-green-50 rounded-lg">
                <div className="w-2 h-2 bg-green-400 rounded-full animate-pulse" />
                <UserIcon className="w-4 h-4 text-green-600" />
                <div><p className="font-medium text-sm text-primary">{p.username}</p>
                  <p className="text-xs text-secondary">{p.host}:{p.port}</p></div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Transfer Tickets */}
      <div className="card">
        <h3 className="font-semibold text-primary mb-3">Transfer History</h3>
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Track ID</th><th className="table-header">Sender</th><th className="table-header"></th>
          <th className="table-header">Receiver</th><th className="table-header">File</th><th className="table-header">Status</th><th className="table-header">Time</th>
        </tr></thead><tbody>
          {tickets.length === 0 ? (
            <tr><td colSpan={7} className="text-center py-8 text-secondary text-sm">No peer-to-peer transfers yet. Transfers will appear here as clients exchange files.</td></tr>
          ) : tickets.map(t => (
            <React.Fragment key={t.ticketId}>
              <tr
                className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
                onClick={() => setExpandedTicket(expandedTicket === t.ticketId ? null : t.ticketId)}
              >
                <td className="table-cell font-mono text-xs font-bold text-blue-600">{t.trackId}</td>
                <td className="table-cell text-sm">{t.senderAccount?.username || '?'}</td>
                <td className="table-cell text-center"><ArrowsRightLeftIcon className="w-4 h-4 text-muted inline" /></td>
                <td className="table-cell text-sm">{t.receiverAccount?.username || '?'}</td>
                <td className="table-cell text-xs text-secondary">{t.filename}</td>
                <td className="table-cell"><span className={`badge ${statusColor[t.status] || 'badge-gray'}`}>{t.status}</span></td>
                <td className="table-cell text-xs text-secondary">{t.createdAt ? format(new Date(t.createdAt), 'MMM d HH:mm') : ''}</td>
              </tr>
              {expandedTicket === t.ticketId && (
                <tr>
                  <td colSpan={7} className="px-4 py-3 bg-canvas/50 border-b border-border">
                    <div className="grid grid-cols-3 gap-4 text-sm">
                      <div>
                        <span className="text-xs text-muted">Ticket ID</span>
                        <p className="font-mono text-xs text-primary">{t.ticketId}</p>
                      </div>
                      <div>
                        <span className="text-xs text-muted">Track ID</span>
                        <p className="font-mono text-xs text-primary">{t.trackId}</p>
                      </div>
                      <div>
                        <span className="text-xs text-muted">Filename</span>
                        <p className="text-primary">{t.filename}</p>
                      </div>
                      <div>
                        <span className="text-xs text-muted">Sender</span>
                        <p className="text-primary">{t.senderAccount?.username || '?'}</p>
                      </div>
                      <div>
                        <span className="text-xs text-muted">Receiver</span>
                        <p className="text-primary">{t.receiverAccount?.username || '?'}</p>
                      </div>
                      <div>
                        <span className="text-xs text-muted">Status</span>
                        <span className={`badge ${statusColor[t.status] || 'badge-gray'}`}>{t.status}</span>
                      </div>
                      {t.fileSize != null && (
                        <div>
                          <span className="text-xs text-muted">File Size</span>
                          <p className="text-primary">{t.fileSize}</p>
                        </div>
                      )}
                      {t.errorMessage && (
                        <div className="col-span-3">
                          <span className="text-xs text-muted">Error</span>
                          <p className="text-sm text-red-600">{t.errorMessage}</p>
                        </div>
                      )}
                    </div>
                  </td>
                </tr>
              )}
            </React.Fragment>
          ))}
        </tbody></table>
      </div>
    </div>
  )
}
