import { useState, useEffect } from 'react'
import { getDashboard, getTransfers, getSla } from '../api/client'
import { useNavigate } from 'react-router-dom'
import { ArrowUpTrayIcon, CheckCircleIcon, XCircleIcon, ClockIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Dashboard({ username }) {
  const [dash, setDash] = useState(null)
  const [recent, setRecent] = useState([])
  const [sla, setSla] = useState(null)
  const nav = useNavigate()

  useEffect(() => {
    getDashboard(username).then(setDash).catch(() => {})
    getTransfers(username, 0, 5).then(setRecent).catch(() => {})
    getSla(username).then(setSla).catch(() => {})
  }, [username])

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-900">Welcome, {username}</h1>

      {/* Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Today', value: dash?.todayTransfers || 0, icon: ArrowUpTrayIcon, color: 'blue' },
          { label: 'This Week', value: dash?.weekTransfers || 0, icon: ClockIcon, color: 'purple' },
          { label: 'Success Rate', value: (dash?.successRate || 100) + '%', icon: CheckCircleIcon, color: 'green' },
          { label: 'Failed', value: dash?.failedTransfers || 0, icon: XCircleIcon, color: 'red' },
        ].map(s => (
          <div key={s.label} className="bg-white rounded-xl border p-5">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center mb-3 bg-${s.color}-50`}>
              <s.icon className={`w-5 h-5 text-${s.color}-600`} />
            </div>
            <p className="text-2xl font-bold text-gray-900">{s.value}</p>
            <p className="text-sm text-gray-500">{s.label}</p>
          </div>
        ))}
      </div>

      {/* SLA */}
      {sla && (
        <div className={`rounded-xl border p-5 ${sla.slaCompliant ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
          <h3 className="font-semibold text-gray-900 mb-2">SLA Status (Last 7 Days)</h3>
          <div className="grid grid-cols-3 gap-4 text-sm">
            <div><span className="text-gray-500">Error Rate:</span> <strong>{sla.errorRate}</strong></div>
            <div><span className="text-gray-500">Avg Delivery:</span> <strong>{sla.avgDeliveryTimeMs}ms</strong></div>
            <div><span className="text-gray-500">Compliant:</span> <strong>{sla.slaCompliant ? '✅ Yes' : '❌ No'}</strong></div>
          </div>
        </div>
      )}

      {/* Recent */}
      <div className="bg-white rounded-xl border">
        <div className="p-5 border-b flex items-center justify-between">
          <h3 className="font-semibold text-gray-900">Recent Transfers</h3>
          <button onClick={() => nav('/transfers')} className="text-sm text-blue-600 hover:text-blue-700">View all →</button>
        </div>
        <div className="divide-y">
          {recent.map(t => (
            <div key={t.trackId} className="px-5 py-3 flex items-center gap-3 hover:bg-gray-50 cursor-pointer" onClick={() => nav(`/track/${t.trackId}`)}>
              <div className={`w-2 h-2 rounded-full ${t.status === 'FAILED' ? 'bg-red-400' : t.status === 'IN_OUTBOX' || t.status === 'MOVED_TO_SENT' ? 'bg-green-400' : 'bg-yellow-400'}`} />
              <span className="font-mono text-xs text-blue-600 font-bold w-28">{t.trackId}</span>
              <span className="text-sm flex-1">{t.filename}</span>
              <span className={`text-xs px-2 py-0.5 rounded-full ${t.integrityVerified ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                {t.integrityVerified ? '✓ Verified' : 'Pending'}
              </span>
              <span className="text-xs text-gray-400">{t.uploadedAt ? format(new Date(t.uploadedAt), 'MMM d HH:mm') : ''}</span>
            </div>
          ))}
          {recent.length === 0 && <div className="px-5 py-8 text-center text-gray-500 text-sm">No transfers yet</div>}
        </div>
      </div>
    </div>
  )
}
