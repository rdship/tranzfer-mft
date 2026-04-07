import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { format } from 'date-fns'
import { MagnifyingGlassIcon, ArrowDownTrayIcon, ChevronLeftIcon, ChevronRightIcon } from '@heroicons/react/24/outline'

const LEVELS = ['ALL', 'ERROR', 'WARN', 'INFO']
const PAGE_SIZES = [25, 50, 100, 200]

export default function Logs() {
  const [search, setSearch] = useState('')
  const [level, setLevel] = useState('ALL')
  const [service, setService] = useState('ALL')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(50)

  const { data: logs = [], isLoading } = useQuery({
    queryKey: ['audit-logs', search, level, service],
    queryFn: () => onboardingApi.get('/api/audit-logs', {
      params: { search: search || undefined, level: level !== 'ALL' ? level : undefined, service: service !== 'ALL' ? service : undefined }
    }).then(r => r.data).catch(() => []),
    refetchInterval: 15000
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
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Audit Logs</h1>
          <p className="text-gray-500 text-sm">Centralized log search across all services</p></div>
        <button onClick={exportCSV} className="btn-secondary"><ArrowDownTrayIcon className="w-4 h-4" /> Export CSV</button>
      </div>
      <div className="card">
        <div className="flex gap-3 mb-4">
          <div className="relative flex-1">
            <MagnifyingGlassIcon className="absolute left-3 top-2.5 w-4 h-4 text-gray-400" />
            <input className="pl-9" placeholder="Search by username, filename, message..." value={search} onChange={e => { setSearch(e.target.value); setPage(0) }} />
          </div>
          <select value={level} onChange={e => { setLevel(e.target.value); setPage(0) }} className="w-auto text-sm">
            {LEVELS.map(l => <option key={l}>{l}</option>)}
          </select>
        </div>
        {isLoading ? <LoadingSpinner /> : (
          <>
            <table className="w-full">
              <thead><tr className="border-b border-gray-100">
                <th className="table-header">Time</th>
                <th className="table-header">Account</th>
                <th className="table-header">Action</th>
                <th className="table-header">File</th>
                <th className="table-header">Status</th>
              </tr></thead>
              <tbody>
                {paginatedLogs.length === 0 ? (
                  <tr><td colSpan={5} className="text-center py-8 text-gray-500 text-sm">No logs found</td></tr>
                ) : paginatedLogs.map((log, i) => (
                  <tr key={i} className="table-row">
                    <td className="table-cell text-xs text-gray-500 font-mono">{log.createdAt ? format(new Date(log.createdAt), 'MM/dd HH:mm:ss') : '--'}</td>
                    <td className="table-cell text-sm font-medium">{log.account?.username || '--'}</td>
                    <td className="table-cell text-xs font-mono">{log.action}</td>
                    <td className="table-cell text-xs text-gray-500 truncate max-w-48">{log.filename || '--'}</td>
                    <td className="table-cell"><span className={`badge ${log.success ? 'badge-green' : 'badge-red'}`}>{log.success ? 'OK' : 'FAIL'}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* Pagination controls */}
            <div className="flex items-center justify-between pt-4 border-t border-gray-100 mt-4">
              <div className="flex items-center gap-2 text-sm text-gray-500">
                <span>Showing {logs.length === 0 ? 0 : safePage * pageSize + 1}--{Math.min((safePage + 1) * pageSize, logs.length)} of {logs.length}</span>
                <span className="text-gray-300">|</span>
                <label className="flex items-center gap-1">
                  <span>Per page:</span>
                  <select value={pageSize} onChange={e => handlePageSizeChange(+e.target.value)} className="w-auto text-xs px-2 py-1 border rounded-lg">
                    {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
                  </select>
                </label>
              </div>
              <div className="flex items-center gap-1">
                <button onClick={() => setPage(0)} disabled={safePage === 0}
                  className="px-2 py-1 text-xs rounded-lg border border-gray-200 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed">First</button>
                <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={safePage === 0}
                  className="p-1.5 rounded-lg border border-gray-200 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed">
                  <ChevronLeftIcon className="w-4 h-4" />
                </button>
                <span className="px-3 py-1 text-sm font-medium text-gray-700">
                  Page {safePage + 1} of {totalPages}
                </span>
                <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={safePage >= totalPages - 1}
                  className="p-1.5 rounded-lg border border-gray-200 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed">
                  <ChevronRightIcon className="w-4 h-4" />
                </button>
                <button onClick={() => setPage(totalPages - 1)} disabled={safePage >= totalPages - 1}
                  className="px-2 py-1 text-xs rounded-lg border border-gray-200 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed">Last</button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
