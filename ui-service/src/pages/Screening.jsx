import { useQuery } from '@tanstack/react-query'
import { screeningApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { ShieldExclamationIcon, CheckBadgeIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Screening() {
  const { data: lists } = useQuery({ queryKey: ['screen-lists'], queryFn: () => screeningApi.get('/api/v1/screening/lists').then(r => r.data) })
  const { data: results = [] } = useQuery({ queryKey: ['screen-results'], queryFn: () => screeningApi.get('/api/v1/screening/results').then(r => r.data).catch(() => []), refetchInterval: 15000 })
  const { data: hits = [] } = useQuery({ queryKey: ['screen-hits'], queryFn: () => screeningApi.get('/api/v1/screening/hits').then(r => r.data).catch(() => []) })

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">OFAC/AML Screening</h1>
        <p className="text-gray-500 text-sm">Sanctions list screening for file transfers</p></div>

      <div className="grid grid-cols-3 gap-4">
        {lists && Object.entries(lists.lists || {}).map(([name, count]) => (
          <div key={name} className="card text-center">
            <p className="text-2xl font-bold text-gray-900">{Number(count).toLocaleString()}</p>
            <p className="text-sm text-gray-500">{name}</p>
          </div>
        ))}
      </div>
      {lists?.lastRefresh && <p className="text-xs text-gray-400">Last refreshed: {lists.lastRefresh} (auto-refresh every 6 hours)</p>}

      {hits.length > 0 && (
        <div className="card border-red-200 bg-red-50">
          <h3 className="font-semibold text-red-800 flex items-center gap-2"><ShieldExclamationIcon className="w-5 h-5" /> Sanctions Hits ({hits.length})</h3>
          <table className="w-full mt-3"><thead><tr className="border-b border-red-200">
            <th className="table-header">Track ID</th><th className="table-header">File</th><th className="table-header">Outcome</th><th className="table-header">Hits</th><th className="table-header">Action</th>
          </tr></thead><tbody>
            {hits.map(h => (
              <tr key={h.id} className="border-b border-red-100">
                <td className="table-cell font-mono text-xs font-bold text-red-700">{h.trackId}</td>
                <td className="table-cell text-sm">{h.filename}</td>
                <td className="table-cell"><span className="badge badge-red">{h.outcome}</span></td>
                <td className="table-cell">{h.hitsFound}</td>
                <td className="table-cell"><span className="badge badge-red">{h.actionTaken}</span></td>
              </tr>
            ))}
          </tbody></table>
        </div>
      )}

      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Recent Screenings</h3>
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Track ID</th><th className="table-header">File</th><th className="table-header">Records</th>
          <th className="table-header">Outcome</th><th className="table-header">Duration</th><th className="table-header">Time</th>
        </tr></thead><tbody>
          {results.length === 0 ? (
            <tr><td colSpan={6} className="text-center py-8 text-gray-500 text-sm">No screening results yet. Results appear as files are screened during transfer.</td></tr>
          ) : results.map(r => (
            <tr key={r.id} className="table-row">
              <td className="table-cell font-mono text-xs">{r.trackId}</td>
              <td className="table-cell text-sm">{r.filename}</td>
              <td className="table-cell text-xs">{r.recordsScanned}</td>
              <td className="table-cell"><span className={`badge ${r.outcome === 'CLEAR' ? 'badge-green' : r.outcome === 'HIT' ? 'badge-red' : 'badge-yellow'}`}>{r.outcome}</span></td>
              <td className="table-cell text-xs">{r.durationMs}ms</td>
              <td className="table-cell text-xs text-gray-500">{r.screenedAt ? format(new Date(r.screenedAt), 'HH:mm:ss') : ''}</td>
            </tr>
          ))}
        </tbody></table>
      </div>
    </div>
  )
}
