import React, { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import ExecutionDetailDrawer from '../components/ExecutionDetailDrawer'
import { format } from 'date-fns'
import { MagnifyingGlassIcon, ArrowDownTrayIcon, ChevronLeftIcon, ChevronRightIcon, ArrowTopRightOnSquareIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

const LEVELS = ['ALL', 'ERROR', 'WARN', 'INFO']
const PAGE_SIZES = [25, 50, 100, 200]

export default function Logs() {
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const [level, setLevel] = useState('ALL')
  const [service, setService] = useState('ALL')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(50)
  const [expandedRow, setExpandedRow] = useState(null)
  const [drawerTrackId, setDrawerTrackId] = useState(null)

  const { data: logs = [], isLoading, isError, refetch } = useQuery({
    queryKey: ['audit-logs', search, level, service],
    queryFn: () => onboardingApi.get('/api/audit-logs', {
      params: { search: search || undefined, level: level !== 'ALL' ? level : undefined, service: service !== 'ALL' ? service : undefined }
    }).then(r => r.data),
    refetchInterval: 15000,
    retry: 1
  })

  // Reset to first page when filters change
  const totalPages = Math.max(1, Math.ceil(logs.length / pageSize))
  const safePage = Math.min(page, totalPages - 1)
  const paginatedLogs = logs.slice(safePage * pageSize, (safePage + 1) * pageSize)

  const exportCSV = () => {
    const csv = ['timestamp,service,account,action,status,message', ...logs.map(l =>
      `${l.timestamp},${l.serviceType},${l.accountUsername},${l.action},${l.status},"${l.message || ''}"`
    )].join('\n')
    const blob = new Blob([csv], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = 'audit-logs.csv'; a.click()
  }

  const handlePageSizeChange = (newSize) => {
    setPageSize(newSize)
    setPage(0)
  }

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
        <div><h1 className="text-2xl font-bold text-primary">Audit Logs</h1>
          <p className="text-secondary text-sm">Centralized log search across all services</p></div>
        <button onClick={exportCSV} className="btn-secondary"><ArrowDownTrayIcon className="w-4 h-4" /> Export CSV</button>
      </div>
      <div className="card">
        <div className="flex gap-3 mb-4">
          <div className="relative flex-1">
            <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted pointer-events-none" />
            <input className="pl-10 pr-3 py-2 text-sm border rounded-lg w-full focus:ring-2 focus:ring-blue-500" placeholder="Search by username, filename, message..." value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
          </div>
          <select value={level} onChange={e => { setLevel(e.target.value); setPage(0) }} className="w-auto text-sm">
            {LEVELS.map(l => <option key={l}>{l}</option>)}
          </select>
        </div>
        {isLoading ? <LoadingSpinner /> : (
          <>
            <p className="text-xs text-muted mb-2">Tip: Double-click any row to open detailed view</p>
            <table className="w-full">
              <thead><tr className="border-b border-border">
                <th className="table-header">Time</th>
                <th className="table-header">Account</th>
                <th className="table-header">Action</th>
                <th className="table-header">File</th>
                <th className="table-header">Track ID</th>
                <th className="table-header">Status</th>
              </tr></thead>
              <tbody>
                {paginatedLogs.length === 0 ? (
                  <tr><td colSpan={6} className="text-center py-8 text-secondary text-sm">No logs found</td></tr>
                ) : paginatedLogs.map((log, i) => (
                  <React.Fragment key={i}>
                    <tr
                      className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
                      onClick={() => setExpandedRow(expandedRow === i ? null : i)}
                      onDoubleClick={() => { if (log.trackId) setDrawerTrackId(log.trackId) }}
                    >
                      <td className="table-cell text-xs text-secondary font-mono">{log.createdAt ? format(new Date(log.createdAt), 'MM/dd HH:mm:ss') : '--'}</td>
                      <td className="table-cell text-sm font-medium">{log.account?.username || '--'}</td>
                      <td className="table-cell text-xs font-mono">{log.action}</td>
                      <td className="table-cell text-xs text-secondary truncate max-w-48">{log.filename || '--'}</td>
                      <td className="table-cell text-xs font-mono">
                        {log.trackId ? (
                          <button
                            onClick={(e) => { e.stopPropagation(); navigate(`/journey?trackId=${encodeURIComponent(log.trackId)}`) }}
                            className="text-blue-600 hover:text-blue-800 hover:underline truncate max-w-[120px] block"
                            title={log.trackId}
                          >
                            {log.trackId.length > 12 ? log.trackId.substring(0, 12) + '...' : log.trackId}
                          </button>
                        ) : <span className="text-muted">--</span>}
                      </td>
                      <td className="table-cell"><span className={`badge ${log.success ? 'badge-green' : 'badge-red'}`}>{log.success ? 'OK' : 'FAIL'}</span></td>
                    </tr>
                    {expandedRow === i && (
                      <tr>
                        <td colSpan={6} className="px-4 py-3 bg-canvas/50 border-b border-border">
                          <div className="grid grid-cols-2 gap-4 text-sm">
                            <div>
                              <span className="text-xs text-muted">Service</span>
                              <p className="font-mono text-xs text-primary">{log.serviceType || '--'}</p>
                            </div>
                            <div>
                              <span className="text-xs text-muted">Full Timestamp</span>
                              <p className="font-mono text-xs text-primary">{log.createdAt ? format(new Date(log.createdAt), 'yyyy-MM-dd HH:mm:ss.SSS') : '--'}</p>
                            </div>
                            {log.message && (
                              <div className="col-span-2">
                                <span className="text-xs text-muted">Message</span>
                                <p className="text-sm text-primary mt-0.5">{log.message}</p>
                              </div>
                            )}
                            {log.filename && (
                              <div className="col-span-2">
                                <span className="text-xs text-muted">Filename</span>
                                <p className="font-mono text-xs text-primary">{log.filename}</p>
                              </div>
                            )}
                            {log.trackId && (
                              <div className="col-span-2 flex items-center gap-3 pt-2 border-t border-border">
                                <button
                                  onClick={(e) => { e.stopPropagation(); navigate(`/journey?trackId=${encodeURIComponent(log.trackId)}`) }}
                                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-blue-50 text-blue-700 hover:bg-blue-100 border border-blue-200 transition-colors"
                                >
                                  <ArrowTopRightOnSquareIcon className="w-3.5 h-3.5" />
                                  View Journey
                                </button>
                                <button
                                  onClick={(e) => { e.stopPropagation(); setDrawerTrackId(log.trackId) }}
                                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-purple-50 text-purple-700 hover:bg-purple-100 border border-purple-200 transition-colors"
                                >
                                  Quick View
                                </button>
                              </div>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>

            {/* Pagination controls */}
            <div className="flex items-center justify-between pt-4 border-t border-border mt-4">
              <div className="flex items-center gap-2 text-sm text-secondary">
                <span>Showing {logs.length === 0 ? 0 : safePage * pageSize + 1}--{Math.min((safePage + 1) * pageSize, logs.length)} of {logs.length}</span>
                <span className="text-muted">|</span>
                <label className="flex items-center gap-1">
                  <span>Per page:</span>
                  <select value={pageSize} onChange={e => handlePageSizeChange(+e.target.value)} className="w-auto text-xs px-2 py-1 border rounded-lg">
                    {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
                  </select>
                </label>
              </div>
              <div className="flex items-center gap-1">
                <button onClick={() => setPage(0)} disabled={safePage === 0}
                  className="px-2 py-1 text-xs rounded-lg border border-border hover:bg-canvas disabled:opacity-40 disabled:cursor-not-allowed">First</button>
                <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={safePage === 0}
                  className="p-1.5 rounded-lg border border-border hover:bg-canvas disabled:opacity-40 disabled:cursor-not-allowed">
                  <ChevronLeftIcon className="w-4 h-4" />
                </button>
                <span className="px-3 py-1 text-sm font-medium text-primary">
                  Page {safePage + 1} of {totalPages}
                </span>
                <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={safePage >= totalPages - 1}
                  className="p-1.5 rounded-lg border border-border hover:bg-canvas disabled:opacity-40 disabled:cursor-not-allowed">
                  <ChevronRightIcon className="w-4 h-4" />
                </button>
                <button onClick={() => setPage(totalPages - 1)} disabled={safePage >= totalPages - 1}
                  className="px-2 py-1 text-xs rounded-lg border border-border hover:bg-canvas disabled:opacity-40 disabled:cursor-not-allowed">Last</button>
              </div>
            </div>
          </>
        )}
      </div>
      <ExecutionDetailDrawer trackId={drawerTrackId} open={!!drawerTrackId} onClose={() => setDrawerTrackId(null)} />
    </div>
  )
}
