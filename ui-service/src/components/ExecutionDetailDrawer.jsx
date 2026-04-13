import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate, Link } from 'react-router-dom'
import CopyButton from './CopyButton'
import { format, formatDistanceToNow } from 'date-fns'
import toast from 'react-hot-toast'
import {
  XMarkIcon, ArrowPathIcon, StopIcon,
  ChevronDownIcon, ChevronRightIcon, ArrowTopRightOnSquareIcon,
  CheckCircleIcon, XCircleIcon,
  ForwardIcon, BoltIcon,
} from '@heroicons/react/24/outline'
import FileDownloadButton from './FileDownloadButton'
import ConfigLink from './ConfigLink'
import ConfigInlineEditor from './ConfigInlineEditor'
import TimelineGantt from './TimelineGantt'
import ConfirmDialog from './ConfirmDialog'
import {
  getExecution, getFlowSteps, getFlowEvents,
  restartExecution, restartFromStep, skipStep, terminateExecution,
} from '../api/executions'
import { getFabricTimeline } from '../api/fabric'

// ── Constants ───────────────────────────────────────────────────────────

const STEP_ICONS = {
  COMPRESS_GZIP: '\uD83D\uDDDC\uFE0F', DECOMPRESS_GZIP: '\uD83D\uDCC2',
  COMPRESS_ZIP: '\uD83D\uDDDC\uFE0F', DECOMPRESS_ZIP: '\uD83D\uDCC2',
  ENCRYPT_AES: '\uD83D\uDD10', DECRYPT_AES: '\uD83D\uDD13',
  ENCRYPT_PGP: '\uD83D\uDD10', DECRYPT_PGP: '\uD83D\uDD13',
  SCREEN: '\uD83D\uDEE1\uFE0F', SCREEN_SANCTIONS: '\uD83D\uDEE1\uFE0F',
  RENAME: '\u270F\uFE0F', MAILBOX: '\uD83D\uDCEC',
  FILE_DELIVERY: '\uD83D\uDE80', CONVERT_EDI: '\uD83D\uDD04',
  ROUTE: '\u27A1\uFE0F', EXECUTE_SCRIPT: '\u26A1',
}

const EVENT_TYPE_BADGES = {
  STEP_STARTED: 'badge-blue', STEP_COMPLETED: 'badge-green', STEP_FAILED: 'badge-red',
  EXECUTION_STARTED: 'badge-blue', EXECUTION_COMPLETED: 'badge-green', EXECUTION_FAILED: 'badge-red',
  RETRY_SCHEDULED: 'badge-yellow', STEP_SKIPPED: 'badge-gray', EXECUTION_TERMINATED: 'badge-red',
  EXECUTION_RESTARTED: 'badge-purple', FILE_RECEIVED: 'badge-teal', FILE_DELIVERED: 'badge-green',
}

// ── Helpers ─────────────────────────────────────────────────────────────

function formatBytes(bytes) {
  if (bytes == null || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

function formatTs(ts) {
  if (!ts) return '--'
  try { return format(new Date(ts), 'MMM dd, HH:mm:ss.SSS') } catch { return '--' }
}

function formatDuration(ms) {
  if (ms == null) return '--'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`
}

function executionDuration(exec) {
  if (!exec?.startedAt) return '--'
  const start = new Date(exec.startedAt)
  if (exec.completedAt) {
    const end = new Date(exec.completedAt)
    return formatDuration(end - start)
  }
  return formatDistanceToNow(start, { addSuffix: false }) + ' (in progress)'
}

function statusBadgeClass(status) {
  if (!status) return 'badge-gray'
  const s = status.toUpperCase()
  if (s === 'COMPLETED' || s === 'OK' || s.startsWith('OK')) return 'badge-green'
  if (s === 'FAILED' || s === 'TERMINATED' || s === 'ERROR') return 'badge-red'
  if (s === 'PROCESSING' || s === 'IN_PROGRESS' || s === 'RUNNING') return 'badge-yellow'
  if (s === 'PAUSED') return 'badge-orange'
  if (s === 'PENDING' || s === 'QUEUED') return 'badge-gray'
  return 'badge-blue'
}

function stepStatusBadgeClass(status) {
  if (!status) return 'badge-gray'
  if (status === 'FAILED') return 'badge-red'
  if (status.startsWith('OK')) return 'badge-green'
  if (status === 'SKIPPED') return 'badge-gray'
  return 'badge-yellow'
}

function stepStatusColor(status) {
  if (!status) return 'rgb(var(--border))'
  if (status === 'FAILED') return 'rgb(240, 120, 120)'
  if (status.startsWith('OK')) return 'rgb(120, 220, 160)'
  if (status === 'SKIPPED') return 'rgb(var(--tx-muted))'
  return 'rgb(240, 200, 100)'
}

function compressionRatio(inputSize, outputSize) {
  if (!inputSize || !outputSize) return null
  const ratio = outputSize / inputSize
  if (ratio < 1) return `${((1 - ratio) * 100).toFixed(0)}% smaller`
  if (ratio > 1) return `${((ratio - 1) * 100).toFixed(0)}% larger`
  return 'same size'
}

// ── Skeleton Loader ─────────────────────────────────────────────────────

function Skeleton({ className = '' }) {
  return <div className={`animate-pulse rounded bg-[rgb(var(--hover))] ${className}`} />
}

function DrawerSkeleton() {
  return (
    <div className="space-y-6 p-6">
      <div className="space-y-3">
        <Skeleton className="h-5 w-48" />
        <Skeleton className="h-4 w-32" />
        <div className="flex gap-2">
          <Skeleton className="h-6 w-20 rounded-full" />
          <Skeleton className="h-6 w-28 rounded-full" />
        </div>
      </div>
      <div className="space-y-3">
        <Skeleton className="h-4 w-24" />
        {[1, 2, 3].map(i => (
          <div key={i} className="flex items-center gap-3">
            <Skeleton className="h-8 w-8 rounded-full" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-3 w-24" />
            </div>
          </div>
        ))}
      </div>
      <div className="space-y-2">
        <Skeleton className="h-4 w-24" />
        {[1, 2, 3, 4].map(i => <Skeleton key={i} className="h-3 w-full" />)}
      </div>
    </div>
  )
}

// ── Step Pipeline Item ──────────────────────────────────────────────────

function StepItem({ step, index, totalSteps, trackId, showActions, onRestartFrom, onSkip }) {
  const [expanded, setExpanded] = useState(false)
  const color = stepStatusColor(step.status)
  const icon = STEP_ICONS[step.functionType] || '\u2699\uFE0F'
  const isLast = index === totalSteps - 1
  const ratio = compressionRatio(step.inputSize, step.outputSize)

  return (
    <div className="relative flex gap-3">
      {/* Vertical connector line */}
      <div className="flex flex-col items-center flex-shrink-0" style={{ width: 32 }}>
        <div
          className="w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold border-2 flex-shrink-0"
          style={{ borderColor: color, background: 'rgb(var(--surface))' }}
          title={step.status || 'PENDING'}
        >
          {step.status === 'FAILED' ? (
            <XCircleIcon className="w-4 h-4" style={{ color }} />
          ) : step.status?.startsWith('OK') ? (
            <CheckCircleIcon className="w-4 h-4" style={{ color }} />
          ) : (
            <span className="text-xs" style={{ color }}>{step.stepIndex ?? index}</span>
          )}
        </div>
        {!isLast && (
          <div className="w-0.5 flex-1 min-h-[16px]" style={{ background: color }} />
        )}
      </div>

      {/* Step content */}
      <div className="flex-1 pb-4 cursor-pointer group" onClick={() => setExpanded(!expanded)}>
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm">{icon}</span>
          <span className="text-sm font-semibold text-[rgb(var(--tx-primary))]">
            {(step.functionType || 'STEP').replace(/_/g, ' ')}
          </span>
          <span className={`badge ${stepStatusBadgeClass(step.status)}`}>{step.status || 'PENDING'}</span>
          {step.durationMs != null && (
            <span className="text-xs font-mono text-[rgb(var(--tx-muted))]">{formatDuration(step.durationMs)}</span>
          )}
          {expanded ? (
            <ChevronDownIcon className="w-3.5 h-3.5 text-[rgb(var(--tx-muted))] ml-auto" />
          ) : (
            <ChevronRightIcon className="w-3.5 h-3.5 text-[rgb(var(--tx-muted))] ml-auto opacity-0 group-hover:opacity-100 transition-opacity" />
          )}
        </div>

        {/* Data flow summary (always visible) */}
        <div className="flex items-center gap-2 mt-1 text-xs text-[rgb(var(--tx-secondary))]">
          {step.inputSize != null && <span>{formatBytes(step.inputSize)}</span>}
          {step.inputSize != null && step.outputSize != null && (
            <>
              <span style={{ color: 'rgb(var(--tx-muted))' }}>{'\u2192'}</span>
              <span>{formatBytes(step.outputSize)}</span>
              {ratio && <span className="text-[rgb(var(--tx-muted))]">({ratio})</span>}
            </>
          )}
        </div>

        {/* Error message */}
        {step.status === 'FAILED' && step.errorMessage && (
          <p className="mt-1 text-xs text-[rgb(240,120,120)] leading-relaxed line-clamp-2">
            {step.errorMessage}
          </p>
        )}

        {/* Expanded details */}
        {expanded && (
          <div className="mt-3 space-y-3 card-sm text-xs">
            {/* Input / output file downloads */}
            <div className="flex flex-wrap gap-3">
              {step.inputStorageKey && (
                <div className="flex items-center gap-1.5">
                  <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Input</span>
                  <span className="text-[rgb(var(--tx-secondary))]">{formatBytes(step.inputSize)}</span>
                  <FileDownloadButton
                    stepInfo={{ trackId, stepIndex: step.stepIndex ?? index, direction: 'input' }}
                    filename={step.inputVirtualPath ? step.inputVirtualPath.split('/').pop() : `step${step.stepIndex ?? index}-input`}
                    size="sm"
                  />
                </div>
              )}
              {step.outputStorageKey && (
                <div className="flex items-center gap-1.5">
                  <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Output</span>
                  <span className="text-[rgb(var(--tx-secondary))]">{formatBytes(step.outputSize)}</span>
                  <FileDownloadButton
                    stepInfo={{ trackId, stepIndex: step.stepIndex ?? index, direction: 'output' }}
                    filename={step.outputVirtualPath ? step.outputVirtualPath.split('/').pop() : `step${step.stepIndex ?? index}-output`}
                    size="sm"
                  />
                </div>
              )}
            </div>

            {/* Metadata */}
            {step.metadata && Object.keys(step.metadata).length > 0 && (
              <div>
                <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Metadata</span>
                <div className="mt-1 grid grid-cols-2 gap-x-4 gap-y-1">
                  {Object.entries(step.metadata).map(([k, v]) => (
                    <div key={k} className="flex gap-1">
                      <span className="text-[rgb(var(--tx-muted))]">{k}:</span>
                      <span className="text-[rgb(var(--tx-primary))] font-mono truncate">{String(v)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Actions for failed steps */}
            {showActions && step.status === 'FAILED' && (
              <div className="flex gap-2 pt-1">
                <button
                  onClick={(e) => { e.stopPropagation(); onRestartFrom(step.stepIndex ?? index) }}
                  className="btn-secondary text-xs py-1 px-2"
                >
                  <ArrowPathIcon className="w-3 h-3" /> Restart from here
                </button>
                <button
                  onClick={(e) => { e.stopPropagation(); onSkip(step.stepIndex ?? index) }}
                  className="btn-secondary text-xs py-1 px-2"
                >
                  <ForwardIcon className="w-3 h-3" /> Skip
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

// ── Event Timeline ──────────────────────────────────────────────────────

function EventTimeline({ events }) {
  const [showAll, setShowAll] = useState(false)
  if (!events || events.length === 0) {
    return <p className="text-sm text-[rgb(var(--tx-muted))] italic">No events recorded</p>
  }

  const sorted = [...events].sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp))
  const visible = showAll ? sorted : sorted.slice(-10)

  return (
    <div className="space-y-1.5">
      {!showAll && sorted.length > 10 && (
        <button
          onClick={() => setShowAll(true)}
          className="text-xs text-[rgb(100,140,255)] hover:underline mb-1"
        >
          Show all {sorted.length} events
        </button>
      )}
      {visible.map((evt, i) => (
        <div key={evt.id || i} className="flex items-start gap-2 py-1.5 border-b border-[rgb(var(--border))] last:border-0">
          <span className="text-xs font-mono text-[rgb(var(--tx-muted))] flex-shrink-0 w-24 pt-0.5">
            {formatTs(evt.timestamp)}
          </span>
          <span className={`badge flex-shrink-0 ${EVENT_TYPE_BADGES[evt.eventType] || 'badge-gray'}`}>
            {(evt.eventType || 'EVENT').replace(/_/g, ' ')}
          </span>
          <span className="text-xs text-[rgb(var(--tx-secondary))] flex-1 truncate">
            {evt.stepName && <span className="font-medium text-[rgb(var(--tx-primary))]">{evt.stepName} </span>}
            {evt.message || evt.detail || ''}
            {evt.durationMs != null && (
              <span className="font-mono text-[rgb(var(--tx-muted))]"> ({formatDuration(evt.durationMs)})</span>
            )}
          </span>
          {evt.actor && (
            <span className="text-xs text-[rgb(var(--tx-muted))] flex-shrink-0">{evt.actor}</span>
          )}
        </div>
      ))}
      {showAll && sorted.length > 10 && (
        <button
          onClick={() => setShowAll(false)}
          className="text-xs text-[rgb(100,140,255)] hover:underline mt-1"
        >
          Show last 10 only
        </button>
      )}
    </div>
  )
}

// ── Main Drawer ─────────────────────────────────────────────────────────

/**
 * ExecutionDetailDrawer — full-featured execution deep-dive panel.
 *
 * Props:
 *   trackId      string   — the execution to display
 *   open         boolean  — drawer visibility
 *   onClose      function — close callback
 *   showActions  boolean  — show restart/terminate (default true)
 */
export default function ExecutionDetailDrawer({ trackId, open, onClose, showActions = true }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [confirmAction, setConfirmAction] = useState(null)
  const [editConfig, setEditConfig] = useState(null)  // { type, id, name }

  // ── Data fetching ───────────────────────────────────────────────────
  const { data: execution, isLoading: execLoading, error: execError, refetch: refetchExec } = useQuery({
    queryKey: ['execution', trackId],
    queryFn: () => getExecution(trackId),
    enabled: !!trackId && open,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return (status === 'PROCESSING' || status === 'RUNNING') ? 3000 : false
    },
  })

  const { data: steps, isLoading: stepsLoading, error: stepsError } = useQuery({
    queryKey: ['flow-steps', trackId],
    queryFn: () => getFlowSteps(trackId),
    enabled: !!trackId && open,
    refetchInterval: () => {
      const execStatus = execution?.status
      return (execStatus === 'PROCESSING' || execStatus === 'RUNNING') ? 3000 : false
    },
  })

  const { data: events, isLoading: eventsLoading } = useQuery({
    queryKey: ['flow-events', trackId],
    queryFn: () => getFlowEvents(trackId),
    enabled: !!trackId && open,
  })

  // Fabric timeline (Phase 4B — optional, backend may return 404 if fabric disabled)
  const { data: fabricTimeline } = useQuery({
    queryKey: ['fabric-timeline', trackId],
    queryFn: () => getFabricTimeline(trackId),
    enabled: !!trackId && open,
    refetchInterval: 5000,
    retry: 1,
  })

  // ── Mutations ─────────────────────────────────────────────────────────
  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['execution', trackId] })
    queryClient.invalidateQueries({ queryKey: ['flow-steps', trackId] })
    queryClient.invalidateQueries({ queryKey: ['flow-events', trackId] })
  }

  const restartMutation = useMutation({
    mutationFn: () => restartExecution(trackId),
    onSuccess: () => { toast.success('Execution restarted'); invalidate() },
    onError: (err) => toast.error(err.response?.data?.message || 'Restart failed'),
  })

  const restartFromMutation = useMutation({
    mutationFn: (step) => restartFromStep(trackId, step),
    onSuccess: () => { toast.success('Restarted from step'); invalidate() },
    onError: (err) => toast.error(err.response?.data?.message || 'Restart failed'),
  })

  const skipMutation = useMutation({
    mutationFn: (step) => skipStep(trackId, step),
    onSuccess: () => { toast.success('Step skipped'); invalidate() },
    onError: (err) => toast.error(err.response?.data?.message || 'Skip failed'),
  })

  const terminateMutation = useMutation({
    mutationFn: () => terminateExecution(trackId),
    onSuccess: () => { toast.success('Execution terminated'); invalidate() },
    onError: (err) => toast.error(err.response?.data?.message || 'Terminate failed'),
  })

  // ── Action handlers ───────────────────────────────────────────────────
  const handleRestartFrom = (stepIndex) => {
    setConfirmAction({
      variant: 'warning',
      title: 'Restart from step',
      message: `Re-execute from step ${stepIndex}? Previous outputs for this and subsequent steps will be overwritten.`,
      confirmLabel: 'Restart',
      onConfirm: () => { restartFromMutation.mutate(stepIndex); setConfirmAction(null) },
    })
  }

  const handleSkip = (stepIndex) => {
    setConfirmAction({
      variant: 'info',
      title: 'Skip step',
      message: `Skip step ${stepIndex} and continue with the next step? The input file will be passed through unchanged.`,
      confirmLabel: 'Skip',
      onConfirm: () => { skipMutation.mutate(stepIndex); setConfirmAction(null) },
    })
  }

  const handleRestart = () => {
    setConfirmAction({
      variant: 'warning',
      title: 'Restart execution',
      message: 'Restart the entire execution from the beginning? This will create a new attempt.',
      confirmLabel: 'Restart',
      onConfirm: () => { restartMutation.mutate(); setConfirmAction(null) },
    })
  }

  const handleTerminate = () => {
    setConfirmAction({
      variant: 'danger',
      title: 'Terminate execution',
      message: 'Terminate this execution? This action cannot be undone.',
      confirmLabel: 'Terminate',
      onConfirm: () => { terminateMutation.mutate(); setConfirmAction(null) },
    })
  }

  // ── Render ────────────────────────────────────────────────────────────
  if (!open) return null

  const isActive = execution?.status === 'PROCESSING' || execution?.status === 'RUNNING' || execution?.status === 'PAUSED'
  const isLoading = execLoading || stepsLoading
  const hasError = execError || stepsError
  const stepList = Array.isArray(steps) ? steps : []

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/50 z-50 transition-opacity" onClick={onClose} />

      {/* Drawer */}
      <div
        className="fixed top-0 right-0 h-full z-50 flex flex-col bg-[rgb(var(--canvas))] border-l border-[rgb(var(--border))] shadow-2xl"
        style={{ width: 'min(600px, 100vw)', animation: 'slideInRight 0.2s ease-out' }}
      >
        {/* ── Drawer Header ──────────────────────────────────────────── */}
        <div className="flex items-center justify-between p-4 border-b border-[rgb(var(--border))] flex-shrink-0">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold text-[rgb(var(--tx-primary))] truncate">
                {execution?.flowName ? (
                  <ConfigLink type="flow" name={execution.flowName} id={execution.flowId}
                    onEdit={setEditConfig} navigateTo="/flows" />
                ) : 'Execution Detail'}
              </h2>
              {execution?.status && (
                <span className={`badge ${statusBadgeClass(execution.status)}`}>{execution.status}</span>
              )}
            </div>
            <div className="flex items-center gap-2 mt-1">
              <span className="inline-flex items-center gap-1 text-xs font-mono text-[rgb(var(--tx-muted))]">
                {trackId?.length > 20 ? trackId.substring(0, 20) + '\u2026' : trackId}
                <CopyButton value={trackId} label="trackId" size="xs" />
              </span>
              {execution?.attempt != null && (
                <span className="badge badge-gray">Attempt {execution.attempt}</span>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-[rgb(var(--hover))] transition-colors flex-shrink-0"
          >
            <XMarkIcon className="w-5 h-5 text-[rgb(var(--tx-secondary))]" />
          </button>
        </div>

        {/* ── Scrollable Content ─────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto">
          {isLoading ? (
            <DrawerSkeleton />
          ) : hasError ? (
            <div className="p-6 text-center">
              <XCircleIcon className="w-10 h-10 mx-auto text-[rgb(240,120,120)] mb-3" />
              <p className="text-sm text-[rgb(var(--tx-secondary))] mb-3">
                {execError?.response?.data?.message || stepsError?.response?.data?.message || 'Failed to load execution details'}
              </p>
              <button onClick={() => refetchExec()} className="btn-secondary text-sm">
                <ArrowPathIcon className="w-4 h-4" /> Retry
              </button>
            </div>
          ) : (
            <div className="p-5 space-y-6">

              {/* ── Summary Card ──────────────────────────────────────── */}
              <div className="card-sm space-y-3">
                <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs">
                  <div>
                    <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Duration</span>
                    <p className="text-[rgb(var(--tx-primary))] font-mono mt-0.5">{executionDuration(execution)}</p>
                  </div>
                  <div>
                    <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Started</span>
                    <p className="text-[rgb(var(--tx-primary))] font-mono mt-0.5">{formatTs(execution?.startedAt)}</p>
                  </div>
                  {execution?.completedAt && (
                    <div>
                      <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Completed</span>
                      <p className="text-[rgb(var(--tx-primary))] font-mono mt-0.5">{formatTs(execution.completedAt)}</p>
                    </div>
                  )}
                  {execution?.originalFilename && (
                    <div>
                      <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Original File</span>
                      <div className="flex items-center gap-1 mt-0.5">
                        <span className="text-[rgb(var(--tx-primary))] truncate" title={execution.originalFilename}>
                          {execution.originalFilename}
                        </span>
                        {execution.initialStorageKey && (
                          <FileDownloadButton
                            sha256={execution.initialStorageKey}
                            filename={execution.originalFilename}
                            size="sm"
                          />
                        )}
                      </div>
                    </div>
                  )}
                  {execution?.fileSizeBytes != null && (
                    <div>
                      <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>File Size</span>
                      <p className="text-[rgb(var(--tx-primary))] font-mono mt-0.5">{formatBytes(execution.fileSizeBytes)}</p>
                    </div>
                  )}
                  {execution?.sourcePartnerName && (
                    <div>
                      <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Source Partner</span>
                      <div className="mt-0.5">
                        <ConfigLink type="partner" name={execution.sourcePartnerName} id={execution.sourcePartnerId}
                          onEdit={setEditConfig} navigateTo={execution.sourcePartnerId ? `/partners/${execution.sourcePartnerId}` : '/partners'} />
                      </div>
                    </div>
                  )}
                  {(execution?.sourceUsername || execution?.destUsername) && (
                    <div>
                      <span className="text-[rgb(var(--tx-muted))] uppercase tracking-wider font-semibold" style={{ fontSize: '0.625rem' }}>Account</span>
                      <div className="mt-0.5">
                        <ConfigLink type="account" name={execution.sourceUsername || execution.destUsername}
                          onEdit={setEditConfig} navigateTo="/accounts" />
                      </div>
                    </div>
                  )}
                </div>

                {/* View full journey link */}
                <button
                  onClick={() => { onClose(); navigate(`/journey?trackId=${trackId}`) }}
                  className="inline-flex items-center gap-1 text-xs text-[rgb(100,140,255)] hover:underline"
                >
                  <ArrowTopRightOnSquareIcon className="w-3 h-3" />
                  View Full Journey
                </button>
              </div>

              {/* ── Step Pipeline ─────────────────────────────────────── */}
              <div>
                <h3 className="section-label mb-3">
                  Step Pipeline ({stepList.length} step{stepList.length !== 1 ? 's' : ''})
                </h3>
                {stepList.length === 0 ? (
                  <p className="text-sm text-[rgb(var(--tx-muted))] italic">No steps recorded yet</p>
                ) : (
                  <div>
                    {stepList.map((step, i) => (
                      <StepItem
                        key={step.id || i}
                        step={step}
                        index={i}
                        totalSteps={stepList.length}
                        trackId={trackId}
                        showActions={showActions}
                        onRestartFrom={handleRestartFrom}
                        onSkip={handleSkip}
                      />
                    ))}
                  </div>
                )}
              </div>

              {/* ── Fabric Checkpoints (Phase 4B) ─────────────────────── */}
              {fabricTimeline && fabricTimeline.stepCount > 0 && (
                <div>
                  <h3 className="section-label mb-3 flex items-center gap-2">
                    <BoltIcon className="w-4 h-4 text-yellow-500" />
                    Fabric Checkpoints
                    <span className="ml-1 text-[rgb(var(--tx-muted))] font-normal normal-case tracking-normal">
                      ({fabricTimeline.stepCount} step{fabricTimeline.stepCount !== 1 ? 's' : ''})
                    </span>
                  </h3>
                  <TimelineGantt
                    steps={fabricTimeline.steps}
                    totalDurationMs={fabricTimeline.totalDurationMs}
                  />
                </div>
              )}

              {/* ── Event Timeline ────────────────────────────────────── */}
              <div>
                <h3 className="section-label mb-3">
                  Event Timeline
                  {events?.length > 0 && (
                    <span className="ml-1.5 text-[rgb(var(--tx-muted))] font-normal normal-case tracking-normal">
                      ({events.length})
                    </span>
                  )}
                </h3>
                {eventsLoading ? (
                  <div className="space-y-2">
                    {[1, 2, 3].map(i => <Skeleton key={i} className="h-6 w-full" />)}
                  </div>
                ) : (
                  <EventTimeline events={events} />
                )}
              </div>
            </div>
          )}
        </div>

        {/* ── Cross-link strip ───────────────────────────────────────────
            Quick navigation from this execution into its Fabric context,
            Sentinel findings, or Journey trace. Transparency + guidance.    */}
        {showActions && execution && !isLoading && !hasError && (
          <div className="flex flex-wrap items-center gap-2 px-4 pt-3 pb-1">
            <span className="text-[10px] uppercase tracking-wide font-semibold" style={{ color: 'rgb(148, 163, 184)' }}>
              Jump to:
            </span>
            <Link
              to={`/operations/journey?trackId=${trackId}`}
              className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-semibold rounded border transition-colors hover:bg-[rgb(var(--surface-hover))]"
              style={{ borderColor: 'rgb(var(--border))', color: 'rgb(100, 140, 255)' }}
              title="Full step-by-step timeline for this trackId"
            >
              Journey →
            </Link>
            <Link
              to="/operations/fabric"
              className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-semibold rounded border transition-colors hover:bg-[rgb(var(--surface-hover))]"
              style={{ borderColor: 'rgb(var(--border))', color: 'rgb(234, 179, 8)' }}
              title="Flow Fabric dashboard — queue depths, instances, latency"
            >
              Flow Fabric →
            </Link>
            <Link
              to={`/sentinel?trackId=${trackId}`}
              className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-semibold rounded border transition-colors hover:bg-[rgb(var(--surface-hover))]"
              style={{ borderColor: 'rgb(var(--border))', color: 'rgb(192, 132, 252)' }}
              title="Platform Sentinel findings for this trackId"
            >
              Sentinel Findings →
            </Link>
            {execution.flowId && (
              <Link
                to={`/flows?id=${execution.flowId}`}
                className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-semibold rounded border transition-colors hover:bg-[rgb(var(--surface-hover))]"
                style={{ borderColor: 'rgb(var(--border))', color: 'rgb(74, 222, 128)' }}
                title="View the Flow definition that ran this execution"
              >
                Flow Definition →
              </Link>
            )}
          </div>
        )}

        {/* ── Action Bar (footer) ────────────────────────────────────── */}
        {showActions && execution && !isLoading && !hasError && (
          <div className="flex items-center gap-2 p-4 border-t border-[rgb(var(--border))] flex-shrink-0 bg-[rgb(var(--surface))]">
            <button
              onClick={handleRestart}
              disabled={restartMutation.isPending}
              className="btn-secondary text-xs"
            >
              <ArrowPathIcon className="w-3.5 h-3.5" />
              {restartMutation.isPending ? 'Restarting...' : 'Restart'}
            </button>

            {isActive && (
              <button
                onClick={handleTerminate}
                disabled={terminateMutation.isPending}
                className="btn-danger text-xs"
              >
                <StopIcon className="w-3.5 h-3.5" />
                {terminateMutation.isPending ? 'Terminating...' : 'Terminate'}
              </button>
            )}

            <div className="flex-1" />

            {/* Download original */}
            {execution.initialStorageKey && (
              <FileDownloadButton
                sha256={execution.initialStorageKey}
                filename={execution.originalFilename || `original-${trackId}`}
                label="Original"
                size="sm"
              />
            )}

            {/* Download current */}
            {execution.currentStorageKey && execution.currentStorageKey !== execution.initialStorageKey && (
              <FileDownloadButton
                sha256={execution.currentStorageKey}
                filename={`current-${execution.originalFilename || trackId}`}
                label="Current"
                size="sm"
              />
            )}
          </div>
        )}
      </div>

      {/* ── Confirm Dialog ────────────────────────────────────────────── */}
      <ConfirmDialog
        open={!!confirmAction}
        variant={confirmAction?.variant || 'warning'}
        title={confirmAction?.title}
        message={confirmAction?.message}
        confirmLabel={confirmAction?.confirmLabel}
        cancelLabel="Cancel"
        loading={restartMutation.isPending || restartFromMutation.isPending || skipMutation.isPending || terminateMutation.isPending}
        onConfirm={() => confirmAction?.onConfirm?.()}
        onCancel={() => setConfirmAction(null)}
      />

      {/* ── Config Inline Editor (slides on top of this drawer) ────── */}
      {editConfig && (
        <div className="relative z-[55]">
          <ConfigInlineEditor
            open={!!editConfig}
            onClose={() => setEditConfig(null)}
            configType={editConfig.type}
            configId={editConfig.id}
            configName={editConfig.name}
          />
        </div>
      )}

      {/* ── Slide-in animation ────────────────────────────────────────── */}
      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to { transform: translateX(0); }
        }
      `}</style>
    </>
  )
}
