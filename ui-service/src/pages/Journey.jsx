import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { MagnifyingGlassIcon, CheckCircleIcon, XCircleIcon, ClockIcon, ArrowRightIcon, ShieldCheckIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const stageIcons = {
  FILE_RECEIVED: '📥', AI_CLASSIFICATION: '🤖', FLOW_PROCESSING: '⚙️',
  SANCTIONS_SCREENING: '🛡️', FILE_ROUTED: '📤', FILE_DELIVERED: '✅', TRANSFER_COMPLETE: '🏁', TRANSFER_FAILED: '❌'
}
const statusColor = { COMPLETED: 'bg-green-500', PASSED: 'bg-green-500', CLEAR: 'bg-green-500', FAILED: 'bg-red-500', BLOCKED: 'bg-red-500', HIT: 'bg-red-500', PROCESSING: 'bg-yellow-500', PENDING: 'bg-gray-400' }

export default function Journey() {
  const [searchParams] = useSearchParams()
  const incomingTrackId = searchParams.get('trackId') || ''
  const [trackId, setTrackId] = useState(incomingTrackId)
  const [searchId, setSearchId] = useState(incomingTrackId || null)

  // Auto-search when navigated with trackId param (e.g. from Activity Monitor)
  useEffect(() => {
    if (incomingTrackId) {
      setTrackId(incomingTrackId)
      setSearchId(incomingTrackId)
    }
  }, [incomingTrackId])

  const { data: journey, isLoading, isError } = useQuery({
    queryKey: ['journey', searchId], enabled: !!searchId,
    queryFn: () => onboardingApi.get(`/api/journey/${searchId}`).then(r => r.data)
  })

  const { data: recent = [] } = useQuery({
    queryKey: ['journey-list'],
    queryFn: () => onboardingApi.get('/api/journey?limit=20').then(r => r.data)
  })

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Transfer Journey Tracker</h1>
        <p className="text-gray-500 text-sm">Complete lifecycle view of any file transfer across all microservices</p></div>

      {/* Search */}
      <div className="card flex gap-3">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-2.5 w-4 h-4 text-gray-400" />
          <input className="pl-9" placeholder="Enter Track ID (e.g. TRZRPF8TEA5Q)..." value={trackId}
            onChange={e => setTrackId(e.target.value.toUpperCase())}
            onKeyDown={e => e.key === 'Enter' && setSearchId(trackId)} />
        </div>
        <button className="btn-primary" onClick={() => setSearchId(trackId)}>Track</button>
      </div>

      {/* Journey Detail */}
      {isLoading && <LoadingSpinner text="Loading journey..." />}
      {isError && <div className="card text-center text-red-500 py-8">Track ID not found: {searchId}</div>}

      {journey && (
        <div className="space-y-4">
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h2 className="text-lg font-bold text-gray-900 font-mono">{journey.trackId}</h2>
                <p className="text-sm text-gray-500">{journey.filename}</p>
              </div>
              <div className="text-right">
                <span className={`badge ${journey.overallStatus === 'MOVED_TO_SENT' || journey.overallStatus === 'COMPLETED' ? 'badge-green' : journey.overallStatus === 'FAILED' ? 'badge-red' : 'badge-yellow'}`}>
                  {journey.overallStatus}
                </span>
                {journey.totalDurationMs && <p className="text-xs text-gray-500 mt-1">{journey.totalDurationMs}ms total</p>}
              </div>
            </div>

            {/* Integrity */}
            <div className={`flex items-center gap-2 p-3 rounded-lg text-sm mb-4 ${
              journey.integrityStatus === 'VERIFIED' ? 'bg-green-50 text-green-800' :
              journey.integrityStatus === 'MISMATCH' ? 'bg-red-50 text-red-800' : 'bg-gray-50 text-gray-600'}`}>
              <ShieldCheckIcon className="w-4 h-4" />
              <span className="font-medium">Integrity: {journey.integrityStatus}</span>
              {journey.sourceChecksum && <span className="font-mono text-xs ml-2">SHA-256: {journey.sourceChecksum?.substring(0,16)}...</span>}
            </div>

            {/* Pipeline visualization */}
            <div className="space-y-0">
              {journey.stages?.map((stage, i) => (
                <div key={i} className="flex items-start gap-3 relative">
                  {i < journey.stages.length - 1 && <div className="absolute left-4 top-8 w-0.5 h-full bg-gray-200" />}
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center text-white text-xs flex-shrink-0 z-10 ${statusColor[stage.status] || 'bg-gray-400'}`}>
                    {stageIcons[stage.stage] || (i + 1)}
                  </div>
                  <div className="flex-1 pb-4">
                    <div className="flex items-center justify-between">
                      <div>
                        <span className="font-medium text-gray-900 text-sm">{stage.stage.replace(/_/g, ' ')}</span>
                        <span className="text-xs text-gray-400 ml-2">{stage.service}</span>
                      </div>
                      <span className={`badge ${statusColor[stage.status] ? (stage.status === 'FAILED' ? 'badge-red' : 'badge-green') : 'badge-gray'}`}>{stage.status}</span>
                    </div>
                    {stage.detail && <p className="text-xs text-gray-500 mt-0.5">{stage.detail}</p>}
                    {stage.timestamp && <p className="text-xs text-gray-400">{format(new Date(stage.timestamp), 'HH:mm:ss.SSS')}</p>}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Audit Trail */}
          {journey.auditTrail?.length > 0 && (
            <div className="card">
              <h3 className="font-semibold text-gray-900 mb-3">Audit Trail</h3>
              <div className="space-y-1 text-xs font-mono">
                {journey.auditTrail.map((a, i) => (
                  <div key={i} className={`flex gap-2 py-1 ${a.success ? 'text-gray-600' : 'text-red-600'}`}>
                    <span className="text-gray-400 w-16">{a.timestamp ? format(new Date(a.timestamp), 'HH:mm:ss') : ''}</span>
                    <span className={`w-3 text-center ${a.success ? 'text-green-500' : 'text-red-500'}`}>{a.success ? '✓' : '✗'}</span>
                    <span className="font-semibold w-28">{a.action}</span>
                    <span className="text-gray-400">{a.principal}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Recent Transfers */}
      {!journey && recent.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-3">Recent Transfers</h3>
          <table className="w-full"><thead><tr className="border-b"><th className="table-header">Track ID</th><th className="table-header">File</th><th className="table-header">Status</th><th className="table-header">Time</th></tr></thead>
            <tbody>{recent.map(r => (
              <tr key={r.trackId} className="table-row cursor-pointer hover:bg-blue-50" onClick={() => { setTrackId(r.trackId); setSearchId(r.trackId) }}>
                <td className="table-cell font-mono text-xs font-bold text-blue-600">{r.trackId}</td>
                <td className="table-cell text-sm">{r.filename}</td>
                <td className="table-cell"><span className={`badge ${r.status === 'FAILED' ? 'badge-red' : r.status === 'IN_OUTBOX' || r.status === 'MOVED_TO_SENT' ? 'badge-green' : 'badge-yellow'}`}>{r.status}</span></td>
                <td className="table-cell text-xs text-gray-500">{r.uploadedAt ? format(new Date(r.uploadedAt), 'MMM d HH:mm') : ''}</td>
              </tr>
            ))}</tbody></table>
        </div>
      )}
    </div>
  )
}
