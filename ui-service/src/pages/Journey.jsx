import { useState, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import {
  MagnifyingGlassIcon, CheckCircleIcon, XCircleIcon, ClockIcon,
  ArrowRightIcon, ShieldCheckIcon, ArrowDownTrayIcon, EyeIcon,
  ChevronDownIcon, ChevronRightIcon
} from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const stageIcons = {
  FILE_RECEIVED: '📥', AI_CLASSIFICATION: '🤖', FLOW_PROCESSING: '⚙️',
  SANCTIONS_SCREENING: '🛡️', FILE_ROUTED: '📤', FILE_DELIVERED: '✅',
  TRANSFER_COMPLETE: '🏁', TRANSFER_FAILED: '❌'
}
const statusColor = {
  COMPLETED: 'bg-green-500', PASSED: 'bg-green-500', CLEAR: 'bg-green-500',
  FAILED: 'bg-red-500', BLOCKED: 'bg-red-500', HIT: 'bg-red-500',
  PROCESSING: 'bg-yellow-500', PENDING: 'bg-gray-400'
}

const STEP_ICONS = {
  COMPRESS_GZIP: '🗜️', DECOMPRESS_GZIP: '📂', COMPRESS_ZIP: '🗜️', DECOMPRESS_ZIP: '📂',
  ENCRYPT_AES: '🔐', DECRYPT_AES: '🔓', ENCRYPT_PGP: '🔐', DECRYPT_PGP: '🔓',
  SCREEN: '🛡️', RENAME: '✏️', MAILBOX: '📬', FILE_DELIVERY: '🚀',
  CONVERT_EDI: '🔄', ROUTE: '➡️', EXECUTE_SCRIPT: '⚡'
}

function stepStatusBadge(status) {
  if (!status) return 'badge-gray'
  if (status === 'FAILED') return 'badge-red'
  if (status.startsWith('OK')) return 'badge-green'
  return 'badge-yellow'
}

function FilePreviewButton({ trackId, stepIndex, direction, label, storageKey, virtualPath }) {
  if (!storageKey) return (
    <span className="text-xs text-gray-300 italic">
      {direction === 'output' ? 'no output' : 'unavailable'}
    </span>
  )

  const filename = virtualPath
    ? virtualPath.substring(virtualPath.lastIndexOf('/') + 1)
    : `step${stepIndex}-${direction}`

  const href = `/api/flow-steps/${trackId}/${stepIndex}/${direction}/content`

  return (
    <a
      href={onboardingApi.defaults.baseURL + href}
      target="_blank"
      rel="noopener noreferrer"
      title={`${label}: ${virtualPath || storageKey.substring(0, 12) + '…'}`}
      className="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded border border-blue-200 text-blue-600 hover:bg-blue-50 hover:border-blue-400 transition-colors"
    >
      <EyeIcon className="w-3 h-3" />
      {filename.length > 22 ? filename.substring(0, 19) + '…' : filename}
    </a>
  )
}

function FlowStepsPanel({ trackId }) {
  const [expanded, setExpanded] = useState(true)

  const { data: steps = [], isLoading } = useQuery({
    queryKey: ['flow-steps', trackId],
    queryFn: () => onboardingApi.get(`/api/flow-steps/${trackId}`).then(r => r.data),
    enabled: !!trackId,
    staleTime: 30_000
  })

  if (isLoading) return (
    <div className="card">
      <div className="animate-pulse h-4 w-32 bg-gray-200 rounded" />
    </div>
  )
  if (!steps.length) return null

  return (
    <div className="card">
      <button
        className="flex items-center justify-between w-full"
        onClick={() => setExpanded(v => !v)}
      >
        <h3 className="font-semibold text-gray-900 flex items-center gap-2">
          <span>⚙️</span>
          Flow Pipeline Steps
          <span className="text-xs font-normal text-gray-500 bg-gray-100 px-2 py-0.5 rounded-full">
            {steps.length} step{steps.length !== 1 ? 's' : ''}
          </span>
        </h3>
        {expanded
          ? <ChevronDownIcon className="w-4 h-4 text-gray-400" />
          : <ChevronRightIcon className="w-4 h-4 text-gray-400" />}
      </button>

      {expanded && (
        <div className="mt-3 space-y-2">
          {/* Header row */}
          <div className="grid grid-cols-[2rem_1fr_4rem_5rem_1fr_2rem_1fr] gap-2 px-2 text-xs font-medium text-gray-400 uppercase tracking-wide">
            <span>#</span>
            <span>Step</span>
            <span>Status</span>
            <span>Duration</span>
            <span>Input file</span>
            <span className="text-center">→</span>
            <span>Output file</span>
          </div>

          {steps.map(snap => (
            <div
              key={snap.stepIndex}
              className={`grid grid-cols-[2rem_1fr_4rem_5rem_1fr_2rem_1fr] gap-2 items-center px-2 py-2 rounded-lg text-sm
                ${snap.stepStatus === 'FAILED' ? 'bg-red-50 border border-red-100' : 'bg-gray-50 border border-gray-100'}`}
            >
              {/* Step number */}
              <span className="text-xs font-mono text-gray-400 font-bold">
                {snap.stepIndex + 1}
              </span>

              {/* Step type */}
              <span className="flex items-center gap-1.5 font-medium text-gray-700 text-xs">
                <span>{STEP_ICONS[snap.stepType] || '⚙️'}</span>
                {snap.stepType.replace(/_/g, ' ')}
              </span>

              {/* Status badge */}
              <span>
                <span className={`badge text-xs ${stepStatusBadge(snap.stepStatus)}`}>
                  {snap.stepStatus === 'FAILED' ? 'FAIL'
                    : snap.stepStatus?.startsWith('OK_AFTER') ? 'RETRY'
                    : 'OK'}
                </span>
              </span>

              {/* Duration */}
              <span className="text-xs text-gray-500 font-mono">
                {snap.durationMs != null ? `${snap.durationMs}ms` : '—'}
              </span>

              {/* Input file preview */}
              <span>
                <FilePreviewButton
                  trackId={trackId}
                  stepIndex={snap.stepIndex}
                  direction="input"
                  label="Input"
                  storageKey={snap.inputStorageKey}
                  virtualPath={snap.inputVirtualPath}
                />
                {snap.inputSizeBytes != null && (
                  <span className="ml-1 text-xs text-gray-400">
                    ({(snap.inputSizeBytes / 1024).toFixed(1)} KB)
                  </span>
                )}
              </span>

              {/* Arrow */}
              <ArrowRightIcon className="w-3 h-3 text-gray-300 mx-auto" />

              {/* Output file preview */}
              <span>
                {snap.stepStatus === 'FAILED' && snap.errorMessage ? (
                  <span
                    className="text-xs text-red-500 truncate block"
                    title={snap.errorMessage}
                  >
                    {snap.errorMessage.length > 40
                      ? snap.errorMessage.substring(0, 37) + '…'
                      : snap.errorMessage}
                  </span>
                ) : (
                  <>
                    <FilePreviewButton
                      trackId={trackId}
                      stepIndex={snap.stepIndex}
                      direction="output"
                      label="Output"
                      storageKey={snap.outputStorageKey}
                      virtualPath={snap.outputVirtualPath}
                    />
                    {snap.outputSizeBytes != null && snap.outputSizeBytes > 0 && (
                      <span className="ml-1 text-xs text-gray-400">
                        ({(snap.outputSizeBytes / 1024).toFixed(1)} KB)
                      </span>
                    )}
                  </>
                )}
              </span>
            </div>
          ))}

          <p className="text-xs text-gray-400 px-2 pt-1">
            Click any file name to open/preview directly from storage — no download required.
          </p>
        </div>
      )}
    </div>
  )
}

export default function Journey() {
  const [searchParams] = useSearchParams()
  const incomingTrackId = searchParams.get('trackId') || ''
  const [trackId, setTrackId] = useState(incomingTrackId)
  const [searchId, setSearchId] = useState(incomingTrackId || null)

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
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Transfer Journey Tracker</h1>
        <p className="text-gray-500 text-sm">Complete lifecycle view — click any pipeline step to open the file before or after transformation</p>
      </div>

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

      {isLoading && <LoadingSpinner text="Loading journey..." />}
      {isError && <div className="card text-center text-red-500 py-8">Track ID not found: {searchId}</div>}

      {journey && (
        <div className="space-y-4">
          {/* Summary card */}
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

            {/* Pipeline stages */}
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

          {/* ── Flow Pipeline Step Preview ─────────────────────────────────── */}
          <FlowStepsPanel trackId={journey.trackId} />

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
          <table className="w-full">
            <thead>
              <tr className="border-b">
                <th className="table-header">Track ID</th>
                <th className="table-header">File</th>
                <th className="table-header">Status</th>
                <th className="table-header">Time</th>
              </tr>
            </thead>
            <tbody>
              {recent.map(r => (
                <tr key={r.trackId} className="table-row cursor-pointer hover:bg-blue-50"
                  onClick={() => { setTrackId(r.trackId); setSearchId(r.trackId) }}>
                  <td className="table-cell font-mono text-xs font-bold text-blue-600">{r.trackId}</td>
                  <td className="table-cell text-sm">{r.filename}</td>
                  <td className="table-cell">
                    <span className={`badge ${r.status === 'FAILED' ? 'badge-red' : r.status === 'IN_OUTBOX' || r.status === 'MOVED_TO_SENT' ? 'badge-green' : 'badge-yellow'}`}>
                      {r.status}
                    </span>
                  </td>
                  <td className="table-cell text-xs text-gray-500">
                    {r.uploadedAt ? format(new Date(r.uploadedAt), 'MMM d HH:mm') : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
