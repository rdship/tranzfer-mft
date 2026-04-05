import { useState, useEffect } from 'react'
import { getTransfers } from '../api/client'
import { useNavigate } from 'react-router-dom'
import { format } from 'date-fns'

export default function Transfers({ username }) {
  const [transfers, setTransfers] = useState([])
  const [page, setPage] = useState(0)
  const nav = useNavigate()

  useEffect(() => { getTransfers(username, page, 25).then(setTransfers).catch(() => {}) }, [username, page])

  const statusColor = { FAILED: 'bg-red-100 text-red-700', IN_OUTBOX: 'bg-green-100 text-green-700',
    MOVED_TO_SENT: 'bg-green-100 text-green-700', PENDING: 'bg-yellow-100 text-yellow-700' }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold text-gray-900">Transfer History</h1>
      <div className="bg-white rounded-xl border">
        <table className="w-full">
          <thead><tr className="border-b">
            <th className="text-left text-xs font-semibold text-gray-500 uppercase px-4 py-3">Track ID</th>
            <th className="text-left text-xs font-semibold text-gray-500 uppercase px-4 py-3">File</th>
            <th className="text-left text-xs font-semibold text-gray-500 uppercase px-4 py-3">Status</th>
            <th className="text-left text-xs font-semibold text-gray-500 uppercase px-4 py-3">Integrity</th>
            <th className="text-left text-xs font-semibold text-gray-500 uppercase px-4 py-3">Size</th>
            <th className="text-left text-xs font-semibold text-gray-500 uppercase px-4 py-3">Date</th>
          </tr></thead>
          <tbody>
            {transfers.map(t => (
              <tr key={t.trackId} className="border-b hover:bg-blue-50 cursor-pointer transition-colors" onClick={() => nav(`/track/${t.trackId}`)}>
                <td className="px-4 py-3 font-mono text-xs font-bold text-blue-600">{t.trackId}</td>
                <td className="px-4 py-3 text-sm">{t.filename}</td>
                <td className="px-4 py-3"><span className={`text-xs px-2 py-0.5 rounded-full ${statusColor[t.status] || 'bg-gray-100 text-gray-600'}`}>{t.status}</span></td>
                <td className="px-4 py-3"><span className={`text-xs ${t.integrityVerified ? 'text-green-600' : 'text-gray-400'}`}>{t.integrityVerified ? '✅ Verified' : '⏳ Pending'}</span></td>
                <td className="px-4 py-3 text-xs text-gray-500">{t.sizeBytes ? (t.sizeBytes / 1024).toFixed(1) + ' KB' : '—'}</td>
                <td className="px-4 py-3 text-xs text-gray-500">{t.uploadedAt ? format(new Date(t.uploadedAt), 'MMM d HH:mm') : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="px-4 py-3 flex gap-2 border-t">
          <button onClick={() => setPage(Math.max(0, page - 1))} disabled={page === 0} className="text-xs px-3 py-1 bg-gray-100 rounded disabled:opacity-50">← Previous</button>
          <span className="text-xs text-gray-500 py-1">Page {page + 1}</span>
          <button onClick={() => setPage(page + 1)} disabled={transfers.length < 25} className="text-xs px-3 py-1 bg-gray-100 rounded disabled:opacity-50">Next →</button>
        </div>
      </div>
    </div>
  )
}
