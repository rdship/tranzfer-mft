import { useState, useEffect } from 'react'
import { getDashboard, getTransfers, getSla } from '../api/client'
import { useNavigate } from 'react-router-dom'
import { ArrowUpTrayIcon, CheckCircleIcon, XCircleIcon, ClockIcon, ArrowTrendingUpIcon, ShieldCheckIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

function getGreeting() {
  const hour = new Date().getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

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

  const stats = [
    { label: 'Today', value: dash?.todayTransfers || 0, icon: ArrowUpTrayIcon, color: 'blue', bg: 'bg-blue-50', text: 'text-blue-600' },
    { label: 'This Week', value: dash?.weekTransfers || 0, icon: ClockIcon, color: 'purple', bg: 'bg-purple-50', text: 'text-purple-600' },
    { label: 'Success Rate', value: (dash?.successRate || 100) + '%', icon: CheckCircleIcon, color: 'green', bg: 'bg-green-50', text: 'text-green-600' },
    { label: 'Failed', value: dash?.failedTransfers || 0, icon: XCircleIcon, color: 'red', bg: 'bg-red-50', text: 'text-red-600' },
  ]

  return (
    <div className="space-y-6">
      {/* Welcome Banner */}
      <div className="bg-gradient-to-r from-blue-600 to-blue-700 rounded-2xl p-6 text-white">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold">{getGreeting()}, {username}</h1>
            <p className="text-blue-100 text-sm mt-1">Here's your transfer activity overview</p>
          </div>
          <div className="hidden lg:flex items-center gap-4">
            <button onClick={() => nav('/transfers')} className="px-4 py-2 bg-white/15 hover:bg-white/25 rounded-lg text-sm font-medium transition-colors">
              View All Transfers
            </button>
            <button onClick={() => nav('/track')} className="px-4 py-2 bg-white/15 hover:bg-white/25 rounded-lg text-sm font-medium transition-colors">
              Track a File
            </button>
          </div>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map(s => (
          <div key={s.label} className="bg-white rounded-xl border border-gray-100 p-5 hover:shadow-md transition-shadow">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center mb-3 ${s.bg}`}>
              <s.icon className={`w-5 h-5 ${s.text}`} />
            </div>
            <p className="text-2xl font-bold text-gray-900">{s.value}</p>
            <p className="text-sm text-gray-500 mt-0.5">{s.label}</p>
          </div>
        ))}
      </div>

      {/* SLA */}
      {sla && (
        <div className={`rounded-xl border p-5 ${sla.slaCompliant ? 'bg-green-50/50 border-green-200' : 'bg-red-50/50 border-red-200'}`}>
          <div className="flex items-center gap-2 mb-3">
            <ShieldCheckIcon className={`w-5 h-5 ${sla.slaCompliant ? 'text-green-600' : 'text-red-600'}`} />
            <h3 className="font-semibold text-gray-900">SLA Compliance (Last 7 Days)</h3>
            <span className={`ml-auto text-xs px-2.5 py-0.5 rounded-full font-semibold ${sla.slaCompliant ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
              {sla.slaCompliant ? 'Compliant' : 'Non-Compliant'}
            </span>
          </div>
          <div className="grid grid-cols-3 gap-4 text-sm">
            <div className="bg-white/60 rounded-lg p-3">
              <p className="text-gray-500 text-xs mb-1">Error Rate</p>
              <p className="text-lg font-bold text-gray-900">{sla.errorRate}</p>
            </div>
            <div className="bg-white/60 rounded-lg p-3">
              <p className="text-gray-500 text-xs mb-1">Avg Delivery Time</p>
              <p className="text-lg font-bold text-gray-900">{sla.avgDeliveryTimeMs}ms</p>
            </div>
            <div className="bg-white/60 rounded-lg p-3">
              <p className="text-gray-500 text-xs mb-1">Status</p>
              <p className={`text-lg font-bold ${sla.slaCompliant ? 'text-green-700' : 'text-red-700'}`}>
                {sla.slaCompliant ? 'Passing' : 'Failing'}
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Recent Transfers */}
      <div className="bg-white rounded-xl border border-gray-100 overflow-hidden">
        <div className="p-5 border-b border-gray-100 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ArrowTrendingUpIcon className="w-5 h-5 text-gray-400" />
            <h3 className="font-semibold text-gray-900">Recent Transfers</h3>
          </div>
          <button onClick={() => nav('/transfers')} className="text-sm text-blue-600 hover:text-blue-700 font-medium">View all</button>
        </div>
        <div className="divide-y divide-gray-50">
          {recent.map(t => (
            <div key={t.trackId} className="px-5 py-3.5 flex items-center gap-3 hover:bg-blue-50/30 cursor-pointer transition-colors" onClick={() => nav(`/track/${t.trackId}`)}>
              <div className={`w-2.5 h-2.5 rounded-full flex-shrink-0 ${t.status === 'FAILED' ? 'bg-red-400' : t.status === 'IN_OUTBOX' || t.status === 'MOVED_TO_SENT' ? 'bg-green-400' : 'bg-amber-400'}`} />
              <span className="font-mono text-xs text-blue-600 font-bold w-28 flex-shrink-0">{t.trackId}</span>
              <span className="text-sm flex-1 truncate text-gray-700">{t.filename}</span>
              <span className={`text-xs px-2.5 py-0.5 rounded-full font-medium flex-shrink-0 ${t.integrityVerified ? 'bg-green-50 text-green-700 ring-1 ring-inset ring-green-600/10' : 'bg-gray-50 text-gray-600 ring-1 ring-inset ring-gray-500/10'}`}>
                {t.integrityVerified ? 'Verified' : 'Pending'}
              </span>
              <span className="text-xs text-gray-400 flex-shrink-0 w-24 text-right">{t.uploadedAt ? format(new Date(t.uploadedAt), 'MMM d, HH:mm') : ''}</span>
            </div>
          ))}
          {recent.length === 0 && (
            <div className="px-5 py-12 text-center">
              <ArrowUpTrayIcon className="w-8 h-8 text-gray-300 mx-auto mb-2" />
              <p className="text-gray-500 text-sm">No transfers yet</p>
              <p className="text-gray-400 text-xs mt-1">Upload files via SFTP/FTP to see them here</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
