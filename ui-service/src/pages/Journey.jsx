import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import EvidenceReport from '../components/EvidenceReport'
import DataLineageGraph from '../components/DataLineageGraph'
import {
  MagnifyingGlassIcon, CheckCircleIcon, XCircleIcon, ClockIcon,
  ArrowRightIcon, ShieldCheckIcon, ArrowDownTrayIcon, EyeIcon,
  ChevronDownIcon, ChevronRightIcon, ArrowPathIcon, StopIcon,
  ExclamationTriangleIcon, DocumentArrowDownIcon,
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

// ── Confirm dialog ───────────────────────────────────────────────────────────
function ConfirmDialog({ title, message, confirmLabel, confirmClass = 'btn-danger', onConfirm, onCancel }) {
  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div className="bg-white rounded-xl shadow-xl p-6 max-w-md w-full mx-4">
        <div className="flex items-start gap-3 mb-4">
          <ExclamationTriangleIcon className="w-6 h-6 text-yellow-500 flex-shrink-0 mt-0.5" />
          <div>
            <h3 className="font-semibold text-gray-900">{title}</h3>
            <p className="text-sm text-gray-600 mt-1">{message}</p>
          </div>
        </div>
        <div className="flex gap-2 justify-end">
          <button className="btn-secondary" onClick={onCancel}>Cancel</button>
          <button className={confirmClass} onClick={onConfirm}>{confirmLabel}</button>
        </div>
      </div>
    </div>
  )
}

// ── Flow execution action bar ─────────────────────────────────────────────────
function FlowActionBar({ trackId, status, stepSnapshots, onActionComplete }) {
  const queryClient = useQueryClient()
  const [confirm, setConfirm] = useState(null) // { type, step? }
  const [feedback, setFeedback] = useState(null)

  const showFeedback = (msg, isError = false) => {
    setFeedback({ msg, isError })
    setTimeout(() => setFeedback(null), 4000)
  }

  const restartMutation = useMutation({
    mutationFn: (fromStep) => fromStep === 0
      ? onboardingApi.post(`/api/flow-executions/${trackId}/restart`)
      : onboardingApi.post(`/api/flow-executions/${trackId}/restart/${fromStep}`),
    onSuccess: (_, fromStep) => {
      showFeedback(fromStep === 0
        ? 'Restart queued. Refreshing in 3s…'
        : `Restart from step ${fromStep} queued. Refreshing in 3s…`)
      setTimeout(() => {
        queryClient.invalidateQueries(['journey', trackId])
        queryClient.invalidateQueries(['flow-steps', trackId])
        if (onActionComplete) onActionComplete()
      }, 3000)
    },
    onError: (e) => showFeedback(e.response?.data?.message || 'Restart failed', true)
  })

  const terminateMutation = useMutation({
    mutationFn: () => onboardingApi.post(`/api/flow-executions/${trackId}/terminate`),
    onSuccess: () => {
      showFeedback('Termination requested. Agent will exit after current step.')
      queryClient.invalidateQueries(['journey', trackId])
      if (onActionComplete) onActionComplete()
    },
    onError: (e) => showFeedback(e.response?.data?.message || 'Terminate failed', true)
  })

  const canRestart = status === 'FAILED' || status === 'CANCELLED' || status === 'UNMATCHED'
  const canTerminate = status === 'PROCESSING' || status === 'FAILED' || status === 'PAUSED'

  // Build restart-from-step options from snapshots
  const failedStep = stepSnapshots?.find(s => s.stepStatus === 'FAILED')

  return (
    <div className="flex items-center gap-2 flex-wrap">
      {feedback && (
        <span className={`text-xs px-3 py-1 rounded-full ${feedback.isError ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700'}`}>
          {feedback.msg}
        </span>
      )}

      {canRestart && (
        <button
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
          disabled={restartMutation.isPending}
          onClick={() => setConfirm({ type: 'restart', step: 0 })}
        >
          <ArrowPathIcon className="w-4 h-4" />
          Restart
        </button>
      )}

      {canRestart && failedStep && failedStep.stepIndex > 0 && (
        <button
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
          disabled={restartMutation.isPending}
          onClick={() => setConfirm({ type: 'restart', step: failedStep.stepIndex })}
          title={`Skip steps 0–${failedStep.stepIndex - 1} (already succeeded)`}
        >
          <ArrowPathIcon className="w-4 h-4" />
          Restart from Step {failedStep.stepIndex + 1}
        </button>
      )}

      {canTerminate && (
        <button
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
          disabled={terminateMutation.isPending}
          onClick={() => setConfirm({ type: 'terminate' })}
        >
          <StopIcon className="w-4 h-4" />
          Terminate
        </button>
      )}

      {confirm?.type === 'restart' && (
        <ConfirmDialog
          title={confirm.step === 0 ? 'Restart from beginning?' : `Restart from Step ${confirm.step + 1}?`}
          message={confirm.step === 0
            ? 'All steps will re-run using the original input file. Previous attempt is archived.'
            : `Steps 1–${confirm.step} are skipped (already succeeded). Processing resumes at step ${confirm.step + 1}.`}
          confirmLabel="Restart"
          confirmClass="inline-flex items-center gap-1 px-4 py-2 text-sm font-medium rounded-lg bg-blue-600 text-white hover:bg-blue-700"
          onConfirm={() => { restartMutation.mutate(confirm.step); setConfirm(null) }}
          onCancel={() => setConfirm(null)}
        />
      )}

      {confirm?.type === 'terminate' && (
        <ConfirmDialog
          title="Terminate this execution?"
          message="The running agent will exit after its current step. This cannot be undone — use Restart to re-process."
          confirmLabel="Terminate"
          confirmClass="inline-flex items-center gap-1 px-4 py-2 text-sm font-medium rounded-lg bg-red-600 text-white hover:bg-red-700"
          onConfirm={() => { terminateMutation.mutate(); setConfirm(null) }}
          onCancel={() => setConfirm(null)}
        />
      )}
    </div>
  )
}

// ── Attempt history ───────────────────────────────────────────────────────────
function AttemptHistory({ trackId, attemptNumber }) {
  const [open, setOpen] = useState(false)

  const { data: history = [] } = useQuery({
    queryKey: ['flow-history', trackId],
    queryFn: () => onboardingApi.get(`/api/flow-executions/${trackId}/history`).then(r => r.data),
    enabled: open && !!trackId
  })

  if (attemptNumber <= 1) return null

  return (
    <div className="card">
      <button className="flex items-center justify-between w-full" onClick={() => setOpen(v => !v)}>
        <h3 className="font-semibold text-gray-900 flex items-center gap-2">
          <span>📋</span>
          Previous Attempts
          <span className="text-xs font-normal text-gray-500 bg-gray-100 px-2 py-0.5 rounded-full">
            {attemptNumber - 1} prior
          </span>
        </h3>
        {open ? <ChevronDownIcon className="w-4 h-4 text-gray-400" /> : <ChevronRightIcon className="w-4 h-4 text-gray-400" />}
      </button>

      {open && (
        <div className="mt-3 space-y-2">
          {history.map((h, i) => (
            <div key={i} className="flex items-start gap-3 p-3 rounded-lg bg-gray-50 border border-gray-100 text-sm">
              <span className={`w-6 h-6 rounded-full flex items-center justify-center text-xs text-white flex-shrink-0 ${h.status === 'FAILED' ? 'bg-red-400' : h.status === 'CANCELLED' ? 'bg-yellow-400' : 'bg-gray-400'}`}>
                {h.attempt}
              </span>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className={`badge text-xs ${h.status === 'FAILED' ? 'badge-red' : 'badge-yellow'}`}>{h.status}</span>
                  <span className="text-xs text-gray-500">{h.stepCount} step(s) completed</span>
                </div>
                {h.errorMessage && (
                  <p className="text-xs text-red-600 mt-1 truncate" title={h.errorMessage}>{h.errorMessage}</p>
                )}
                <p className="text-xs text-gray-400 mt-0.5">
                  {h.startedAt ? format(new Date(h.startedAt), 'MMM d HH:mm:ss') : ''}
                  {h.failedAt ? ` → ${format(new Date(h.failedAt), 'HH:mm:ss')}` : ''}
                </p>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
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
    queryFn: () => onboardingApi.get(`/api/journey/${searchId}`).then(r => r.data),
    refetchInterval: (data) => {
      // Auto-refresh while processing
      const s = data?.overallStatus
      return (s === 'PROCESSING' || s === 'PENDING') ? 4000 : false
    }
  })

  const { data: execDetail } = useQuery({
    queryKey: ['flow-exec-detail', searchId], enabled: !!searchId,
    queryFn: () => onboardingApi.get(`/api/flow-executions/${searchId}`).then(r => r.data).catch(() => null)
  })

  const { data: stepSnapshots = [] } = useQuery({
    queryKey: ['flow-steps-for-action', searchId], enabled: !!searchId,
    queryFn: () => onboardingApi.get(`/api/flow-steps/${searchId}`).then(r => r.data).catch(() => [])
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
            <div className="flex items-start justify-between mb-4 gap-4">
              <div>
                <h2 className="text-lg font-bold text-gray-900 font-mono">{journey.trackId}</h2>
                <p className="text-sm text-gray-500">{journey.filename}</p>
                {execDetail?.attemptNumber > 1 && (
                  <p className="text-xs text-indigo-600 mt-0.5">Attempt {execDetail.attemptNumber} · restarted by {execDetail.restartedBy}</p>
                )}
              </div>
              <div className="flex items-start gap-3 flex-shrink-0">
                <button
                  onClick={() => {
                    const prev = document.title
                    document.title = `Evidence-${journey.trackId}`
                    window.print()
                    document.title = prev
                  }}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors"
                  style={{ borderColor: '#d1d5db', color: '#6b7280' }}
                  onMouseEnter={e => { e.currentTarget.style.background = '#f3f4f6'; e.currentTarget.style.color = '#111827' }}
                  onMouseLeave={e => { e.currentTarget.style.background = ''; e.currentTarget.style.color = '#6b7280' }}
                  title="Export as PDF — opens browser print dialog"
                >
                  <DocumentArrowDownIcon className="w-4 h-4" />
                  Export PDF
                </button>
                <div className="text-right">
                  <span className={`badge ${journey.overallStatus === 'MOVED_TO_SENT' || journey.overallStatus === 'COMPLETED' ? 'badge-green' : journey.overallStatus === 'FAILED' ? 'badge-red' : journey.overallStatus === 'CANCELLED' ? 'badge-yellow' : 'badge-yellow'}`}>
                    {journey.overallStatus}
                  </span>
                  {journey.totalDurationMs && <p className="text-xs text-gray-500 mt-1">{journey.totalDurationMs}ms total</p>}
                </div>
              </div>
            </div>

            {/* Action bar — only shown when a flow was matched */}
            {execDetail && (
              <div className="mb-4 pb-4 border-b border-gray-100">
                <FlowActionBar
                  trackId={journey.trackId}
                  status={execDetail.status}
                  stepSnapshots={stepSnapshots}
                />
              </div>
            )}

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

          {/* ── Attempt history ───────────────────────────────────────────── */}
          {execDetail && <AttemptHistory trackId={journey.trackId} attemptNumber={execDetail.attemptNumber} />}

          {/* ── Flow Pipeline Step Preview ─────────────────────────────────── */}
          <FlowStepsPanel trackId={journey.trackId} />

          {/* ── Data Lineage Graph ─────────────────────────────────────────── */}
          <DataLineageGraph trackId={journey.trackId} />

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

      {/* Hidden print-only evidence report — revealed by @media print CSS */}
      {journey && (
        <EvidenceReport journey={journey} execDetail={execDetail} />
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
