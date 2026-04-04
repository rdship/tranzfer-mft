import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { format } from 'date-fns'
import { MagnifyingGlassIcon, ArrowDownTrayIcon } from '@heroicons/react/24/outline'

const LEVELS = ['ALL', 'ERROR', 'WARN', 'INFO']

export default function Logs() {
  const [search, setSearch] = useState('')
  const [level, setLevel] = useState('ALL')
  const [service, setService] = useState('ALL')

  const { data: logs = [], isLoading } = useQuery({
    queryKey: ['audit-logs', search, level, service],
    queryFn: () => onboardingApi.get('/api/audit-logs', {
      params: { search: search || undefined, level: level !== 'ALL' ? level : undefined, service: service !== 'ALL' ? service : undefined }
    }).then(r => r.data).catch(() => []),
    refetchInterval: 15000
  })

  const exportCSV = () => {
    const csv = ['timestamp,service,account,action,status,message', ...logs.map(l =>
      `${l.timestamp},${l.serviceType},${l.accountUsername},${l.action},${l.status},"${l.message || ''}"`
    )].join('\n')
    const blob = new Blob([csv], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = 'audit-logs.csv'; a.click()
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
            <input className="pl-9" placeholder="Search by username, filename, message..." value={search} onChange={e => setSearch(e.target.value)} />
          </div>
          <select value={level} onChange={e => setLevel(e.target.value)} className="w-auto text-sm">
            {LEVELS.map(l => <option key={l}>{l}</option>)}
          </select>
        </div>
        {isLoading ? <LoadingSpinner /> : (
          <table className="w-full">
            <thead><tr className="border-b border-gray-100">
              <th className="table-header">Time</th>
              <th className="table-header">Account</th>
              <th className="table-header">Action</th>
              <th className="table-header">File</th>
              <th className="table-header">Status</th>
            </tr></thead>
            <tbody>
              {logs.length === 0 ? (
                <tr><td colSpan={5} className="text-center py-8 text-gray-500 text-sm">No logs found</td></tr>
              ) : logs.slice(0, 200).map((log, i) => (
                <tr key={i} className="table-row">
                  <td className="table-cell text-xs text-gray-500 font-mono">{log.createdAt ? format(new Date(log.createdAt), 'MM/dd HH:mm:ss') : '—'}</td>
                  <td className="table-cell text-sm font-medium">{log.account?.username || '—'}</td>
                  <td className="table-cell text-xs font-mono">{log.action}</td>
                  <td className="table-cell text-xs text-gray-500 truncate max-w-48">{log.filename || '—'}</td>
                  <td className="table-cell"><span className={`badge ${log.success ? 'badge-green' : 'badge-red'}`}>{log.success ? 'OK' : 'FAIL'}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
