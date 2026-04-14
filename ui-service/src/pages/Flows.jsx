import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi, onboardingApi, aiApi } from '../api/client'
import { getPendingApprovals, approveStep, rejectStep } from '../api/approvals'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import FormField from '../components/FormField'
import useGentleValidation from '../hooks/useGentleValidation'
import useEnterAdvances from '../hooks/useEnterAdvances'
import Skeleton, { useDelayedFlag } from '../components/Skeleton'
import EmptyState from '../components/EmptyState'
import ExecutionDetailDrawer from '../components/ExecutionDetailDrawer'
import FileDownloadButton from '../components/FileDownloadButton'
import ConfigLink from '../components/ConfigLink'
import QuickFlowWizard from '../components/QuickFlowWizard'
import MatchCriteriaBuilder, { MatchSummaryBadges, buildCriteriaFromLegacy } from '../components/MatchCriteriaBuilder'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilSquareIcon, ChevronUpIcon, ChevronDownIcon,
  FunnelIcon, ArrowPathIcon, ClockIcon, CheckCircleIcon, XCircleIcon,
  ArrowsUpDownIcon, SparklesIcon, StopIcon, BoltIcon,
  ChevronRightIcon, InboxIcon, PaperAirplaneIcon, HandRaisedIcon,
  BeakerIcon, ExclamationTriangleIcon, QuestionMarkCircleIcon, MagnifyingGlassIcon,
} from '@heroicons/react/24/outline'

// ─── Fallback step type definitions (used when backend catalog is unavailable) ───
const FALLBACK_STEP_TYPES = {
  // Encryption
  ENCRYPT_PGP:     { label: 'Encrypt (PGP)',     icon: '🔒', category: 'Encryption',   color: 'text-amber-600 bg-amber-50',     configFields: [{ key: 'keyAlias', label: 'PGP Key Alias', placeholder: 'recipient-public-key' }] },
  DECRYPT_PGP:     { label: 'Decrypt (PGP)',     icon: '🔓', category: 'Encryption',   color: 'text-amber-600 bg-amber-50',     configFields: [{ key: 'keyAlias', label: 'PGP Key Alias', placeholder: 'our-private-key' }] },
  ENCRYPT_AES:     { label: 'Encrypt (AES)',     icon: '🔒', category: 'Encryption',   color: 'text-amber-600 bg-amber-50',     configFields: [{ key: 'keyAlias', label: 'AES Key Alias', placeholder: 'shared-aes-key' }] },
  DECRYPT_AES:     { label: 'Decrypt (AES)',     icon: '🔓', category: 'Encryption',   color: 'text-amber-600 bg-amber-50',     configFields: [{ key: 'keyAlias', label: 'AES Key Alias', placeholder: 'shared-aes-key' }] },
  // Compression
  COMPRESS_GZIP:   { label: 'Compress (GZIP)',   icon: '📦', category: 'Compression',  color: 'text-teal-600 bg-teal-50',       configFields: [] },
  DECOMPRESS_GZIP: { label: 'Decompress (GZIP)', icon: '📂', category: 'Compression',  color: 'text-teal-600 bg-teal-50',       configFields: [] },
  COMPRESS_ZIP:    { label: 'Compress (ZIP)',    icon: '📦', category: 'Compression',  color: 'text-teal-600 bg-teal-50',       configFields: [] },
  DECOMPRESS_ZIP:  { label: 'Decompress (ZIP)',  icon: '📂', category: 'Compression',  color: 'text-teal-600 bg-teal-50',       configFields: [] },
  // Transform
  RENAME:          { label: 'Rename File',       icon: '✏️', category: 'Transform',    color: 'text-indigo-600 bg-indigo-50',   configFields: [{ key: 'pattern', label: 'Rename Pattern', placeholder: '${basename}_${timestamp}${ext}', helper: 'Variables: ${basename}, ${timestamp}, ${trackid}, ${ext}, ${date}' }] },
  // Validation
  SCREEN:          { label: 'Sanctions Screen',  icon: '🛡️', category: 'Validation',   color: 'text-red-600 bg-red-50',         configFields: [{ key: 'mode', label: 'Screen Mode', placeholder: 'OFAC', type: 'select', options: ['OFAC', 'AML', 'BOTH'] }] },
  CHECKSUM_VERIFY: { label: 'Checksum Verify',   icon: '🔒', category: 'Validation',   color: 'text-red-600 bg-red-50',         configFields: [{ key: 'expectedSha256', label: 'Expected SHA-256 (optional)', placeholder: 'Leave empty for auto-detect' }] },
  // Delivery
  MAILBOX:         { label: 'Mailbox Delivery',  icon: '📬', category: 'Delivery',     color: 'text-blue-600 bg-blue-50',       configFields: [{ key: 'path', label: 'Mailbox Path', placeholder: '/outbox' }] },
  FILE_DELIVERY:   { label: 'File Delivery',     icon: '📤', category: 'Delivery',     color: 'text-blue-600 bg-blue-50',       configFields: [{ key: 'destinationId', label: 'Destination ID', placeholder: 'UUID of external destination' }] },
  // Routing
  ROUTE:           { label: 'Route',             icon: '🔀', category: 'Delivery',     color: 'text-purple-600 bg-purple-50',   configFields: [{ key: 'target', label: 'Route Target', placeholder: 'destination-name' }] },
  // Data
  CONVERT_EDI:     { label: 'Convert EDI',       icon: '🔄', category: 'Data',         color: 'text-orange-600 bg-orange-50',   configFields: [{ key: 'format', label: 'Target Format', placeholder: 'X12', type: 'select', options: ['X12', 'EDIFACT', 'JSON', 'XML'] }] },
  // Scripting
  EXECUTE_SCRIPT:  { label: 'Execute Script',    icon: '⚡', category: 'Scripting',    color: 'text-violet-600 bg-violet-50',   configFields: [{ key: 'command', label: 'Command', placeholder: '/opt/scripts/process.sh ${filepath}' }, { key: 'timeout', label: 'Timeout (seconds)', placeholder: '30' }] },

  APPROVE:         { label: 'Admin Approval',    icon: '✋', category: 'Governance',   color: 'text-purple-600 bg-purple-50',   configFields: [{ key: 'requiredApprovers', label: 'Required Approvers', placeholder: 'admin, finance-team (usernames, informational)' }] },
}

// Mutable — will be replaced by dynamic fetch in the main component
let STEP_TYPE_CATALOG = { ...FALLBACK_STEP_TYPES }

const FALLBACK_STEP_CATEGORIES = [
  { name: 'Encryption',   types: ['ENCRYPT_PGP', 'DECRYPT_PGP', 'ENCRYPT_AES', 'DECRYPT_AES'] },
  { name: 'Compression',  types: ['COMPRESS_GZIP', 'DECOMPRESS_GZIP', 'COMPRESS_ZIP', 'DECOMPRESS_ZIP'] },
  { name: 'Transform',    types: ['RENAME'] },
  { name: 'Validation',   types: ['SCREEN', 'CHECKSUM_VERIFY'] },
  { name: 'Delivery',     types: ['MAILBOX', 'FILE_DELIVERY', 'ROUTE'] },
  { name: 'Data',         types: ['CONVERT_EDI'] },
  { name: 'Scripting',    types: ['EXECUTE_SCRIPT'] },
]

let STEP_CATEGORIES = [...FALLBACK_STEP_CATEGORIES]

const PROTOCOLS = ['ANY', 'SFTP', 'FTP', 'AS2', 'API']

const FLOW_TEMPLATES = [
  { name: 'Standard Inbound',  desc: 'Decrypt, decompress, screen, deliver to mailbox', steps: [
    { type: 'DECRYPT_PGP', config: {} }, { type: 'DECOMPRESS_GZIP', config: {} }, { type: 'SCREEN', config: { mode: 'OFAC' } }, { type: 'MAILBOX', config: { path: '/outbox' } }
  ]},
  { name: 'Secure Outbound',   desc: 'Compress, encrypt, deliver externally', steps: [
    { type: 'COMPRESS_GZIP', config: {} }, { type: 'ENCRYPT_PGP', config: {} }, { type: 'FILE_DELIVERY', config: {} }
  ]},
  { name: 'EDI Processing',    desc: 'Decrypt, convert EDI, screen, deliver to mailbox', steps: [
    { type: 'DECRYPT_PGP', config: {} }, { type: 'CONVERT_EDI', config: { format: 'X12' } }, { type: 'SCREEN', config: { mode: 'OFAC' } }, { type: 'MAILBOX', config: { path: '/outbox' } }
  ]},
  { name: 'Pass-Through',      desc: 'Screen only, deliver to mailbox', steps: [
    { type: 'SCREEN', config: { mode: 'OFAC' } }, { type: 'MAILBOX', config: { path: '/outbox' } }
  ]},
]

const STATUS_STYLES = {
  COMPLETED:  { bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200', dot: 'bg-emerald-500' },
  FAILED:     { bg: 'bg-red-50',     text: 'text-red-700',     border: 'border-red-200',     dot: 'bg-red-500' },
  PROCESSING: { bg: 'bg-amber-50',   text: 'text-amber-700',   border: 'border-amber-200',   dot: 'bg-amber-500' },
  PENDING:    { bg: 'bg-canvas',    text: 'text-secondary',    border: 'border-border',    dot: 'bg-gray-400' },
  PAUSED:     { bg: 'bg-blue-50',    text: 'text-blue-700',    border: 'border-blue-200',    dot: 'bg-blue-500' },
  UNMATCHED:  { bg: 'bg-slate-50',  text: 'text-slate-600',   border: 'border-slate-200',   dot: 'bg-slate-400' },
}

const defaultForm = {
  name: '', description: '', filenamePattern: '', sourcePath: '/inbox',
  destinationPath: '/outbox', priority: 100, active: true, steps: [],
  sourceAccountId: '', protocol: 'ANY', serverId: '',
  deliveryMode: 'none', externalDestinationId: '', destinationAccountId: '',
  matchCriteria: null, direction: null,
}

// ─── Mini pipeline visualization ───
function MiniPipeline({ steps }) {
  if (!steps || steps.length === 0) return <span className="text-xs text-muted italic">No steps</span>
  return (
    <div className="flex items-center gap-1 flex-wrap">
      {steps.map((step, i) => {
        const meta = STEP_TYPE_CATALOG[step.type]
        return (
          <div key={i} className="flex items-center gap-1">
            {i > 0 && <ChevronRightIcon className="w-3 h-3 text-gray-300 flex-shrink-0" />}
            <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${meta?.color || 'text-secondary bg-hover'}`}>
              <span>{meta?.icon || '?'}</span>
              <span>{meta?.label || step.type}</span>
            </span>
          </div>
        )
      })}
    </div>
  )
}

// ─── Step card in the builder ───
function StepCard({ step, index, total, onRemove, onMoveUp, onMoveDown, onConfigChange, availableQueues }) {
  const [expanded, setExpanded] = useState(false)
  const meta = STEP_TYPE_CATALOG[step.type] || { label: step.type, icon: '?', color: 'text-secondary bg-hover', configFields: [] }
  const queuesForType = (availableQueues || []).filter(q => q.functionType === step.type && q.enabled)

  return (
    <div className={`border rounded-lg transition-all ${expanded ? 'border-blue-300 shadow-sm' : 'border-border'}`}>
      <div className="flex items-center gap-2 p-3">
        <span className="text-xs font-bold text-muted w-6 text-center flex-shrink-0">{index + 1}</span>
        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold flex-shrink-0 ${meta.color}`}>
          <span>{meta.icon}</span> {meta.label}
        </span>
        <div className="flex-1" />
        {meta.configFields.length > 0 && (
          <button type="button" onClick={() => setExpanded(!expanded)}
            className="text-xs text-muted hover:text-blue-600 transition-colors px-1">
            {expanded ? 'Collapse' : 'Configure'}
          </button>
        )}
        <div className="flex items-center gap-0.5 flex-shrink-0">
          <button type="button" onClick={onMoveUp} disabled={index === 0}
            className="p-1 text-muted hover:text-secondary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
            <ChevronUpIcon className="w-4 h-4" />
          </button>
          <button type="button" onClick={onMoveDown} disabled={index === total - 1}
            className="p-1 text-muted hover:text-secondary disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
            <ChevronDownIcon className="w-4 h-4" />
          </button>
          <button type="button" onClick={onRemove}
            className="p-1 text-muted hover:text-red-600 transition-colors">
            <TrashIcon className="w-4 h-4" />
          </button>
        </div>
      </div>
      {expanded && (
        <div className="px-3 pb-3 pt-1 border-t border-border space-y-2">
          {/* Queue profile picker — admin picks which queue profile to use for this step */}
          {queuesForType.length > 1 && (
            <div>
              <label className="text-xs font-medium text-secondary">Queue Profile</label>
              <select
                value={step.config?.queueId || ''}
                onChange={e => onConfigChange('queueId', e.target.value)}
                className="mt-0.5 text-sm"
              >
                <option value="">Default ({queuesForType.find(q => q.defaultQueue)?.displayName || step.type})</option>
                {queuesForType.filter(q => !q.defaultQueue).map(q => (
                  <option key={q.id} value={q.id}>
                    {q.displayName} (retry={q.retryCount}, timeout={q.timeoutSeconds}s, workers={q.minConcurrency}-{q.maxConcurrency})
                  </option>
                ))}
              </select>
            </div>
          )}
          {queuesForType.length === 1 && (
            <p className="text-[10px] text-muted">Queue: {queuesForType[0].displayName} (retry={queuesForType[0].retryCount}, timeout={queuesForType[0].timeoutSeconds}s)</p>
          )}
          {meta.configFields.map(field => (
            <div key={field.key}>
              <label className="text-xs font-medium text-secondary">{field.label}</label>
              {field.type === 'select' ? (
                <select
                  value={step.config?.[field.key] || ''}
                  onChange={e => onConfigChange(field.key, e.target.value)}
                  className="mt-0.5 text-sm"
                >
                  <option value="">-- Select --</option>
                  {field.options.map(o => <option key={o} value={o}>{o}</option>)}
                </select>
              ) : (
                <input
                  value={step.config?.[field.key] || ''}
                  onChange={e => onConfigChange(field.key, e.target.value)}
                  placeholder={field.placeholder}
                  className="mt-0.5 text-sm font-mono"
                />
              )}
              {field.helper && <p className="text-xs text-muted mt-0.5">{field.helper}</p>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── Add Step Dropdown ───
function AddStepDropdown({ onAdd, onClose }) {
  return (
    <div className="absolute z-30 mt-1 w-80 bg-surface rounded-xl shadow-xl border border-border p-3 space-y-3 max-h-96 overflow-y-auto">
      {STEP_CATEGORIES.map(cat => (
        <div key={cat.name}>
          <h4 className="text-xs font-semibold text-secondary uppercase tracking-wider mb-1">{cat.name}</h4>
          <div className="grid grid-cols-1 gap-1">
            {cat.types.map(type => {
              const meta = STEP_TYPE_CATALOG[type]
              return (
                <button key={type} type="button" onClick={() => { onAdd(type); onClose() }}
                  className="flex items-center gap-2 px-3 py-2 rounded-lg text-left hover:bg-canvas transition-colors">
                  <span className={`inline-flex items-center justify-center w-7 h-7 rounded-lg text-sm ${meta.color}`}>{meta.icon}</span>
                  <span className="text-sm font-medium text-primary">{meta.label}</span>
                </button>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}

// ─── Execution detail row ───
// ─── Dry Run Modal ───────────────────────────────────────────────────────────

const DR_STATUS_CFG = {
  WOULD_SUCCEED:  { icon: CheckCircleIcon,          color: '#22c55e', bg: 'rgb(20 83 45 / 0.2)',   label: 'WOULD SUCCEED' },
  WOULD_FAIL:     { icon: XCircleIcon,              color: '#ef4444', bg: 'rgb(127 29 29 / 0.2)', label: 'WOULD FAIL' },
  CANNOT_VERIFY:  { icon: QuestionMarkCircleIcon,   color: '#f59e0b', bg: 'rgb(120 53 15 / 0.2)', label: 'CANNOT VERIFY' },
}

function fmtEstimate(ms) {
  if (!ms) return null
  if (ms < 1000) return `~${ms}ms`
  return `~${(ms / 1000).toFixed(1)}s`
}

function DryRunModal({ result, onClose }) {
  const allSucceed  = result.wouldSucceed
  const hasFailures = result.issues?.length > 0

  return (
    <Modal title={`Dry Run: ${result.flowName}`} size="lg" onClose={onClose}>
      {/* Summary header */}
      <div
        className="flex items-center gap-3 p-4 rounded-xl mb-5"
        style={{
          background: allSucceed ? 'rgb(20 83 45 / 0.2)' : 'rgb(127 29 29 / 0.2)',
          border: `1px solid ${allSucceed ? '#16a34a' : '#dc2626'}44`,
        }}
      >
        {allSucceed
          ? <CheckCircleIcon className="w-6 h-6 flex-shrink-0" style={{ color: '#22c55e' }} />
          : <ExclamationTriangleIcon className="w-6 h-6 flex-shrink-0" style={{ color: '#ef4444' }} />
        }
        <div className="flex-1">
          <p className="font-semibold text-sm" style={{ color: allSucceed ? '#22c55e' : '#ef4444' }}>
            {allSucceed
              ? `All ${result.steps?.length} steps would succeed`
              : `${result.issues?.length} issue${result.issues?.length !== 1 ? 's' : ''} detected`}
          </p>
          <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
            Test file: <span className="font-mono">{result.testFilename}</span>
            {result.totalEstimatedMs > 0 && (
              <> · Estimated total: <span className="font-mono">{fmtEstimate(result.totalEstimatedMs)}</span></>
            )}
          </p>
        </div>
      </div>

      {/* Per-step results */}
      <div className="space-y-2 mb-5">
        {(result.steps || []).map((step, i) => {
          const cfg = DR_STATUS_CFG[step.status] || DR_STATUS_CFG.CANNOT_VERIFY
          const Icon = cfg.icon
          return (
            <div
              key={i}
              className="flex items-start gap-3 p-3 rounded-lg"
              style={{ background: cfg.bg, border: `1px solid ${cfg.color}40` }}
            >
              <Icon className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: cfg.color }} />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                    Step {step.stepIndex + 1} · {step.label}
                  </span>
                  <span
                    className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-full"
                    style={{ background: `${cfg.color}22`, color: cfg.color }}
                  >
                    {cfg.label}
                  </span>
                  {step.estimatedMs > 0 && (
                    <span className="text-[10px] font-mono ml-auto" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {fmtEstimate(step.estimatedMs)}
                    </span>
                  )}
                </div>
                <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
                  {step.message}
                </p>
              </div>
            </div>
          )
        })}
      </div>

      {/* Issues summary */}
      {hasFailures && (
        <div
          className="p-3 rounded-lg mb-4"
          style={{ background: 'rgb(127 29 29 / 0.15)', border: '1px solid rgb(239 68 68 / 0.3)' }}
        >
          <p className="text-xs font-semibold mb-1.5" style={{ color: '#ef4444' }}>
            Issues to fix before running live:
          </p>
          {result.issues.map((issue, i) => (
            <p key={i} className="text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
              · {issue}
            </p>
          ))}
        </div>
      )}

      <div className="flex justify-end">
        <button onClick={onClose} className="btn-secondary">Close</button>
      </div>
    </Modal>
  )
}

const RESTARTABLE = new Set(['FAILED', 'CANCELLED', 'UNMATCHED'])

function ExecutionRow({ ex, selected, onToggle, onSkipStep, skipPending, onScheduleRetry, onCancelSchedule, schedulePending, onOpenDrawer }) {
  const [expanded, setExpanded] = useState(false)
  const [showScheduler, setShowScheduler] = useState(false)
  const [scheduleInput, setScheduleInput] = useState('')
  const style = STATUS_STYLES[ex.status] || STATUS_STYLES.PENDING
  const totalSteps = ex.flow?.steps?.length ?? ex.stepResults?.length ?? '?'
  const duration = ex.startedAt && ex.completedAt
    ? `${((new Date(ex.completedAt) - new Date(ex.startedAt)) / 1000).toFixed(1)}s`
    : ex.startedAt ? 'Running...' : '—'
  const selectable  = RESTARTABLE.has(ex.status)
  const canSkip     = RESTARTABLE.has(ex.status)
  const isScheduled = !!ex.scheduledRetryAt

  return (
    <>
      <tr
        className={`table-row cursor-pointer hover:bg-canvas transition-colors ${selected ? 'bg-red-50' : ''}`}
        onClick={() => setExpanded(!expanded)}
        onDoubleClick={() => ex.trackId && onOpenDrawer && onOpenDrawer(ex.trackId)}
      >
        {/* Checkbox — stop propagation so row expand doesn't fire */}
        <td className="table-cell w-8" onClick={e => e.stopPropagation()}>
          {selectable ? (
            <input
              type="checkbox"
              checked={!!selected}
              onChange={() => onToggle(ex.trackId)}
              className="rounded border-border text-red-600 focus:ring-red-500 cursor-pointer"
            />
          ) : (
            <span className="block w-4" />
          )}
        </td>
        <td className="table-cell">
          <div className="flex items-center gap-1">
            <ChevronRightIcon className={`w-3.5 h-3.5 text-muted transition-transform ${expanded ? 'rotate-90' : ''}`} />
            <span className="font-mono text-xs font-bold text-blue-600">{ex.trackId}</span>
          </div>
        </td>
        <td className="table-cell text-sm text-primary">{ex.flow?.name || '—'}</td>
        <td className="table-cell text-xs text-secondary truncate max-w-40 font-mono">
          <div className="flex items-center gap-1.5">
            <span className="truncate">{ex.originalFilename}</span>
            <span onClick={e => e.stopPropagation()}>
              <FileDownloadButton
                trackId={ex.trackId}
                filename={ex.originalFilename}
              />
            </span>
          </div>
        </td>
        <td className="table-cell">
          <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium border ${style.bg} ${style.text} ${style.border}`}>
            <span className={`w-1.5 h-1.5 rounded-full ${style.dot}`} />
            {ex.status}
          </span>
        </td>
        <td className="table-cell">
          <div className="flex items-center gap-1.5">
            <div className="flex-1 h-1.5 bg-hover rounded-full overflow-hidden max-w-16">
              <div className="h-full bg-blue-500 rounded-full transition-all"
                style={{ width: `${totalSteps !== '?' ? (ex.currentStep / totalSteps) * 100 : 0}%` }} />
            </div>
            <span className="text-xs text-secondary">{ex.currentStep}/{totalSteps}</span>
          </div>
        </td>
        <td className="table-cell text-xs text-secondary">{duration}</td>
        <td className="table-cell text-xs text-secondary">
          {ex.startedAt ? new Date(ex.startedAt).toLocaleString() : '—'}
        </td>
        {/* Scheduled retry / actions */}
        <td className="table-cell" onClick={e => e.stopPropagation()}>
          {isScheduled ? (
            <div className="flex items-center gap-1.5">
              <span className="flex items-center gap-1 px-2 py-0.5 bg-blue-50 border border-blue-200 text-blue-700 rounded-full text-[10px] font-semibold whitespace-nowrap">
                <ClockIcon className="w-3 h-3" />
                {new Date(ex.scheduledRetryAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </span>
              <button
                onClick={() => onCancelSchedule()}
                disabled={schedulePending}
                className="p-0.5 text-muted hover:text-red-500 transition-colors"
                title="Cancel scheduled retry"
                aria-label="Cancel scheduled retry"
              >
                <XCircleIcon className="w-3.5 h-3.5" />
              </button>
            </div>
          ) : selectable && !showScheduler ? (
            <button
              onClick={() => { setShowScheduler(true); setScheduleInput('') }}
              className="text-[10px] text-muted hover:text-blue-600 px-1.5 py-0.5 rounded hover:bg-blue-50 transition-colors whitespace-nowrap"
              title="Schedule retry at a specific time"
            >
              + Schedule
            </button>
          ) : showScheduler ? (
            <div className="flex items-center gap-1" onClick={e => e.stopPropagation()}>
              <input
                type="datetime-local"
                value={scheduleInput}
                onChange={e => setScheduleInput(e.target.value)}
                className="text-[10px] border border-border rounded px-1.5 py-0.5 focus:outline-none focus:ring-1 focus:ring-blue-400"
                min={new Date(Date.now() + 60000).toISOString().slice(0, 16)}
              />
              <button
                onClick={() => {
                  if (!scheduleInput) return
                  onScheduleRetry(new Date(scheduleInput).toISOString())
                  setShowScheduler(false)
                }}
                disabled={!scheduleInput || schedulePending}
                className="text-[10px] px-2 py-0.5 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50 transition-colors"
              >
                Set
              </button>
              <button
                onClick={() => setShowScheduler(false)}
                className="text-[10px] text-muted hover:text-secondary"
              >
                ✕
              </button>
            </div>
          ) : null}
        </td>
      </tr>
      {expanded && ex.stepResults?.length > 0 && (
        <tr>
          <td colSpan={9} className="px-4 pb-4 pt-0">
            <div className="ml-6 bg-canvas rounded-lg p-3">
              <h4 className="text-xs font-semibold text-secondary mb-2">Step Results</h4>
              <div className="space-y-1.5">
                {ex.stepResults.map((sr, i) => {
                  const stepMeta  = STEP_TYPE_CATALOG[sr.stepType]
                  const isOk      = sr.status === 'OK'
                  const isFailed  = sr.status === 'FAILED'
                  const isSkipped = sr.status === 'SKIPPED'
                  const isLast    = i === (typeof totalSteps === 'number' ? totalSteps - 1 : Infinity)
                  const showSkip  = canSkip && isFailed && !isLast
                  return (
                    <div key={i} className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs ${isFailed ? 'bg-red-50' : isOk ? 'bg-surface' : isSkipped ? 'bg-amber-50' : 'bg-hover'}`}>
                      {isOk      && <CheckCircleIcon className="w-4 h-4 text-emerald-500 flex-shrink-0" />}
                      {isFailed  && <XCircleIcon     className="w-4 h-4 text-red-500 flex-shrink-0" />}
                      {isSkipped && <ArrowPathIcon   className="w-4 h-4 text-amber-500 flex-shrink-0" />}
                      {!isOk && !isFailed && !isSkipped && <ClockIcon className="w-4 h-4 text-muted flex-shrink-0" />}
                      <span className="font-medium text-primary">{stepMeta?.icon} {stepMeta?.label || sr.stepType}</span>
                      <span className="text-muted">({sr.durationMs}ms)</span>
                      {sr.error && <span className="text-red-600 truncate max-w-52">{sr.error}</span>}
                      <div className="flex-1" />
                      <span className={`font-semibold ${isOk ? 'text-emerald-600' : isFailed ? 'text-red-600' : isSkipped ? 'text-amber-600' : 'text-secondary'}`}>
                        {sr.status}
                      </span>
                      {showSkip && (
                        <button
                          onClick={e => { e.stopPropagation(); onSkipStep(i) }}
                          disabled={skipPending}
                          title="Skip this step and resume from the next"
                          className="ml-1 px-2 py-0.5 text-[10px] font-semibold rounded bg-amber-100 text-amber-700 border border-amber-300 hover:bg-amber-200 disabled:opacity-50 transition-colors flex-shrink-0"
                        >
                          {skipPending ? '…' : 'Skip →'}
                        </button>
                      )}
                    </div>
                  )
                })}
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

// ─── Approval Row ────────────────────────────────────────────────────────────
function ApprovalRow({ ap, onApprove, onReject, busy }) {
  const [showRejectBox, setShowRejectBox] = useState(false)
  const [rejectNote, setRejectNote] = useState('')
  const [approveNote, setApproveNote] = useState('')
  const [showApproveNote, setShowApproveNote] = useState(false)

  const paused = ap.requestedAt ? new Date(ap.requestedAt).toLocaleString() : '—'

  return (
    <div className="flex flex-col gap-2 p-3 bg-purple-50 border border-purple-100 rounded-xl">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-mono text-xs font-semibold text-purple-700 bg-purple-100 px-2 py-0.5 rounded">
              {ap.trackId}
            </span>
            <span className="text-sm font-medium text-gray-800">{ap.flowName || '—'}</span>
            <span className="text-xs text-secondary truncate max-w-48">{ap.originalFilename}</span>
          </div>
          <div className="mt-1 flex items-center gap-3 text-xs text-secondary">
            <span>Step {ap.stepIndex + 1}</span>
            <span>Paused {paused}</span>
            {ap.requiredApprovers && (
              <span className="text-purple-600">Approvers: {ap.requiredApprovers}</span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <button
            onClick={() => { setShowApproveNote(!showApproveNote); setShowRejectBox(false) }}
            disabled={busy}
            className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 transition-colors">
            Approve
          </button>
          <button
            onClick={() => { setShowRejectBox(!showRejectBox); setShowApproveNote(false) }}
            disabled={busy}
            className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 transition-colors">
            Reject
          </button>
        </div>
      </div>

      {showApproveNote && (
        <div className="flex gap-2 mt-1">
          <input
            type="text"
            value={approveNote}
            onChange={e => setApproveNote(e.target.value)}
            placeholder="Optional note for audit trail…"
            className="flex-1 text-xs px-3 py-1.5 border border-border rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-400"
          />
          <button
            onClick={() => { onApprove(approveNote); setShowApproveNote(false) }}
            disabled={busy}
            className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 transition-colors">
            Confirm Approve
          </button>
        </div>
      )}

      {showRejectBox && (
        <div className="flex gap-2 mt-1">
          <input
            type="text"
            value={rejectNote}
            onChange={e => setRejectNote(e.target.value)}
            placeholder="Rejection reason (required)…"
            className="flex-1 text-xs px-3 py-1.5 border border-red-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-red-400"
          />
          <button
            onClick={() => {
              if (!rejectNote.trim()) { toast.error('Rejection reason required'); return }
              onReject(rejectNote)
              setShowRejectBox(false)
            }}
            disabled={busy}
            className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 transition-colors">
            Confirm Reject
          </button>
        </div>
      )}
    </div>
  )
}

// ═══════════════════════════════════════════════════════════════════════
//  MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════════════
export default function Flows() {
  const qc = useQueryClient()
  // Phase 2 — URL param filter: /flows?partnerId=X deep-links from PartnerDetail.
  // Principle: Flexibility (shareable URLs), Speed (client-side filter, no refetch).
  const [searchParams, setSearchParams] = useSearchParams()
  const partnerIdFilter = searchParams.get('partnerId')
  const clearPartnerFilter = () => {
    const next = new URLSearchParams(searchParams)
    next.delete('partnerId')
    setSearchParams(next, { replace: true })
  }
  const [showEditor, setShowEditor] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [form, setForm] = useState({ ...defaultForm })
  const [showAddStep, setShowAddStep] = useState(false)
  const [filter, setFilter] = useState('all') // 'all' | 'active' | 'inactive'
  const [selectedIds, setSelectedIds] = useState(new Set()) // trackIds selected for bulk restart
  const [dryRunResult, setDryRunResult] = useState(null)   // non-null = show dry run modal
  const [dryRunPrompt, setDryRunPrompt] = useState(null)   // { flowId, flowName } → prompt for filename
  const [dryRunFilename, setDryRunFilename] = useState('')
  const [activeTab, setActiveTab] = useState('flows')      // 'flows' | 'catalog'
  const [functionCatalog, setFunctionCatalog] = useState([])
  const [importName, setImportName] = useState('')
  const [importRuntime, setImportRuntime] = useState('GRPC')
  const [importEndpoint, setImportEndpoint] = useState('')
  const [importDesc, setImportDesc] = useState('')
  const [search, setSearch] = useState('')
  const [aiSuggestions, setAiSuggestions] = useState(null)      // { steps: [...] } or null
  const [aiAvailable, setAiAvailable] = useState(true)
  const [drawerTrackId, setDrawerTrackId] = useState(null)     // execution detail drawer
  const [confirmDeleteFlow, setConfirmDeleteFlow] = useState(null)
  const [showQuickFlow, setShowQuickFlow] = useState(false)
  const [selectedFlows, setSelectedFlows] = useState(new Set())
  const [showBulkFlowConfirm, setShowBulkFlowConfirm] = useState(null) // 'enable' | 'disable' | 'delete'
  const [bulkFlowLoading, setBulkFlowLoading] = useState(false)
  const lastFlowClickedIdRef = useRef(null)

  // ─── Dynamic function catalog fetch ───
  const loadCatalog = useCallback(() => {
    configApi.get('/api/flows/functions/catalog')
      .then(r => {
        const data = r.data
        if (Array.isArray(data) && data.length > 0) {
          setFunctionCatalog(data)
          // Build dynamic step type catalog from backend response
          const dynamicCatalog = { ...FALLBACK_STEP_TYPES }
          const categoryMap = {}
          data.forEach(fn => {
            if (fn.type && !dynamicCatalog[fn.type]) {
              dynamicCatalog[fn.type] = {
                label: fn.label || fn.type,
                icon: fn.icon || '?',
                category: fn.category || 'Custom',
                color: fn.color || 'text-secondary bg-hover',
                configFields: fn.configFields || fn.configSchema ?
                  (fn.configFields || [{ key: 'config', label: 'Configuration', placeholder: fn.configSchema || '' }]) : [],
              }
            }
            const cat = fn.category || 'Custom'
            if (!categoryMap[cat]) categoryMap[cat] = []
            if (!categoryMap[cat].includes(fn.type)) categoryMap[cat].push(fn.type)
          })
          STEP_TYPE_CATALOG = dynamicCatalog
          // Rebuild categories to include any new types from backend
          const existingCatNames = new Set(FALLBACK_STEP_CATEGORIES.map(c => c.name))
          const newCategories = [...FALLBACK_STEP_CATEGORIES]
          Object.entries(categoryMap).forEach(([name, types]) => {
            if (!existingCatNames.has(name)) {
              newCategories.push({ name, types })
            }
          })
          STEP_CATEGORIES = newCategories
        }
      })
      .catch(() => {
        STEP_TYPE_CATALOG = { ...FALLBACK_STEP_TYPES }
        STEP_CATEGORIES = [...FALLBACK_STEP_CATEGORIES]
      })
  }, [])

  useEffect(() => { loadCatalog() }, [loadCatalog])

  const handleImportFunction = useCallback(() => {
    if (!importName.trim()) { toast.error('Function name is required'); return }
    configApi.post('/api/flows/functions/import', {
      name: importName, runtime: importRuntime, endpoint: importEndpoint, description: importDesc
    }).then(() => {
      loadCatalog()
      setImportName('')
      setImportEndpoint('')
      setImportDesc('')
      toast.success(`Function "${importName}" imported`)
    }).catch(err => toast.error(err.response?.data?.error || 'Failed to import function'))
  }, [importName, importRuntime, importEndpoint, importDesc, loadCatalog])

  // ─── Queries ───
  const { data: flowsData, isLoading } = useQuery({
    queryKey: ['flows'],
    queryFn: () => configApi.get('/api/flows').then(r => r.data)
  })
  const flows = flowsData || []
  // Skeleton only on first fetch; 100ms flash guard (Stability).
  const isFirstLoad = isLoading && !flowsData
  const showSkeleton = useDelayedFlag(isFirstLoad, 100)

  const { data: executions = [] } = useQuery({
    queryKey: ['flow-executions'],
    queryFn: () => configApi.get('/api/flows/executions?size=20').then(r => r.data?.content || r.data || []),
    meta: { silent: true }, refetchInterval: 10000
  })

  // Approval queue — the global query error handler will surface a toast
  // when this fails (deduplicated by the `pending-approvals` query key).
  // Per-query onError was removed in React Query v5; meta.errorMessage is
  // how the global handler picks up a contextual label per query.
  const { data: pendingApprovals = [], isError: approvalsError } = useQuery({
    queryKey: ['pending-approvals'],
    queryFn: getPendingApprovals,
    meta: { silent: true }, refetchInterval: 15000,
    retry: 1,
    meta: { errorMessage: "Couldn't load pending approvals" },
  })

  const approveMut = useMutation({
    mutationFn: ({ trackId, stepIndex, note }) => approveStep(trackId, stepIndex, note),
    onSuccess: () => {
      qc.invalidateQueries(['pending-approvals'])
      qc.invalidateQueries(['flow-executions'])
      toast.success('Flow approved — resuming execution')
    },
    onError: err => toast.error(err.response?.data?.message || 'Approval failed')
  })

  const rejectMut = useMutation({
    mutationFn: ({ trackId, stepIndex, note }) => rejectStep(trackId, stepIndex, note),
    onSuccess: () => {
      qc.invalidateQueries(['pending-approvals'])
      qc.invalidateQueries(['flow-executions'])
      toast.success('Flow rejected and cancelled')
    },
    onError: err => toast.error(err.response?.data?.message || 'Rejection failed')
  })

  const bulkRestartMut = useMutation({
    mutationFn: (trackIds) =>
      onboardingApi.post('/api/flow-executions/bulk-restart', { trackIds }).then(r => r.data),
    onSuccess: (data) => {
      setSelectedIds(new Set())
      qc.invalidateQueries(['flow-executions'])
      const msg = data.queued === 0
        ? `No executions restarted (${data.skipped} skipped — check status)`
        : `${data.queued} execution${data.queued !== 1 ? 's' : ''} queued for restart` +
          (data.skipped > 0 ? ` · ${data.skipped} skipped` : '')
      data.queued > 0 ? toast.success(msg) : toast.error(msg)
    },
    onError: err => toast.error(err.response?.data?.error || 'Bulk restart failed')
  })

  const skipStepMut = useMutation({
    mutationFn: ({ trackId, stepIndex }) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/skip/${stepIndex}`).then(r => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries(['flow-executions'])
      toast.success(`Step ${data.skippedStep + 1} skipped — resuming from step ${data.resumeAtStep + 1}`)
    },
    onError: err => toast.error(err.response?.data?.message || err.response?.data?.error || 'Skip failed — step snapshot may not exist')
  })

  const scheduleRetryMut = useMutation({
    mutationFn: ({ trackId, scheduledAt }) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/schedule-retry`, { scheduledAt }).then(r => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries(['flow-executions'])
      qc.invalidateQueries(['scheduled-retries'])
      toast.success(`Retry scheduled for ${new Date(data.scheduledAt).toLocaleString()}`)
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to schedule retry')
  })

  const dryRunMut = useMutation({
    mutationFn: ({ flowId, filename }) =>
      onboardingApi.post(`/api/flows/${flowId}/dry-run`, { filename }).then(r => r.data),
    onSuccess: (data) => setDryRunResult(data),
    onError: err => toast.error(err.response?.data?.message || 'Dry run failed'),
  })

  const cancelScheduleMut = useMutation({
    mutationFn: (trackId) =>
      onboardingApi.delete(`/api/flow-executions/${trackId}/schedule-retry`).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['flow-executions'])
      qc.invalidateQueries(['scheduled-retries'])
      toast.success('Scheduled retry cancelled')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to cancel schedule')
  })

  const aiSuggestMut = useMutation({
    mutationFn: (payload) => aiApi.post('/api/v1/ai/nlp/suggest-flow', payload).then(r => r.data),
    onSuccess: (data) => {
      const steps = data.steps || data.suggestedSteps || []
      if (steps.length === 0) {
        toast.error('AI returned no suggestions for this configuration')
        return
      }
      setAiSuggestions({ steps })
      toast.success(`AI suggested ${steps.length} step${steps.length !== 1 ? 's' : ''}`)
    },
    onError: (err) => {
      if (err.code === 'ERR_NETWORK' || err.response?.status === 503 || err.response?.status === 502) {
        setAiAvailable(false)
        toast.error('AI engine is not available')
      } else {
        toast.error(err.response?.data?.message || 'AI suggestion failed')
      }
    }
  })

  const { data: scheduledRetries = [] } = useQuery({
    queryKey: ['scheduled-retries'],
    queryFn: () => onboardingApi.get('/api/flow-executions/scheduled-retries').then(r => r.data),
    meta: { silent: true }, refetchInterval: 60000  // was 30s — scheduled retries change infrequently
  })

  const { data: accounts = [] } = useQuery({
    queryKey: ['accounts-for-flows'],
    queryFn: () => onboardingApi.get('/api/accounts').then(r => r.data),
    staleTime: 60000
  })

  const { data: externalDests = [] } = useQuery({
    queryKey: ['ext-dests-for-flows'],
    queryFn: () => configApi.get('/api/external-destinations').then(r => r.data),
    staleTime: 60000
  })

  const { data: servers = [] } = useQuery({
    queryKey: ['servers-for-flows'],
    queryFn: () => onboardingApi.get('/api/servers?activeOnly=true').then(r => r.data),
    staleTime: 60000
  })

  const { data: partners = [] } = useQuery({
    queryKey: ['partners-for-flows'],
    queryFn: () => onboardingApi.get('/api/partners').then(r => r.data?.content || r.data || []),
    staleTime: 30000
  })

  const { data: functionQueues = [] } = useQuery({
    queryKey: ['function-queues-for-flows'],
    queryFn: () => configApi.get('/api/function-queues').then(r => r.data),
    staleTime: 60000
  })

  // ─── Cascading protocol filters ───
  const filteredAccounts = useMemo(() =>
    form.protocol && form.protocol !== 'ANY'
      ? accounts.filter(a => a.protocol === form.protocol)
      : accounts,
    [accounts, form.protocol]
  )

  const filteredServers = useMemo(() =>
    form.protocol && form.protocol !== 'ANY'
      ? servers.filter(s => s.protocol === form.protocol)
      : servers,
    [servers, form.protocol]
  )

  const handleProtocolChange = useCallback((newProtocol) => {
    setForm(f => {
      const updates = { ...f, protocol: newProtocol }
      // Clear source account if it doesn't match the new protocol
      if (newProtocol && newProtocol !== 'ANY' && f.sourceAccountId) {
        const acct = accounts.find(a => a.id === f.sourceAccountId)
        if (acct && acct.protocol !== newProtocol) updates.sourceAccountId = ''
      }
      // Clear server if it doesn't match the new protocol
      if (newProtocol && newProtocol !== 'ANY' && f.serverId) {
        const srv = servers.find(s => s.id === f.serverId)
        if (srv && srv.protocol !== newProtocol) updates.serverId = ''
      }
      // Clear destination account if it doesn't match the new protocol
      if (newProtocol && newProtocol !== 'ANY' && f.destinationAccountId) {
        const acct = accounts.find(a => a.id === f.destinationAccountId)
        if (acct && acct.protocol !== newProtocol) updates.destinationAccountId = ''
      }
      return updates
    })
  }, [accounts, servers])

  // ─── Mutations ───
  const saveMut = useMutation({
    mutationFn: (data) => {
      const payload = buildPayload(data)
      return editingId
        ? configApi.put(`/api/flows/${editingId}`, payload).then(r => r.data)
        : configApi.post('/api/flows', payload).then(r => r.data)
    },
    onSuccess: () => {
      qc.invalidateQueries(['flows'])
      closeEditor()
      toast.success(editingId ? 'Flow updated' : 'Flow created')
    },
    onError: err => {
      const status = err?.response?.status
      const serverMsg = err?.response?.data?.error || err?.response?.data?.message
      if (status === 409) toast.error(serverMsg || 'A flow with this name already exists. Try a different name.')
      else if (status === 400) toast.error(serverMsg || 'Invalid flow configuration. Please check your inputs.')
      else if (status >= 500) toast.error('Something went wrong on the server. Please try again or contact support.')
      else toast.error(serverMsg || 'Failed to save flow')
    }
  })

  // ─── VIP form validation for the Create / Edit Flow modal ─────────────
  // Rules intentionally kept to the fields that would have been caught by the
  // old `!form.name || form.steps.length === 0` disable — plus a friendly
  // steps-missing message so users aren't stuck staring at a greyed button.
  const flowRules = useMemo(() => ([
    { field: 'name', label: 'Flow name', required: true },
    {
      field: 'steps',
      label: 'Processing pipeline',
      required: true,
      validate: (v) => (Array.isArray(v) && v.length === 0 ? 'Add at least one step so the flow has something to do' : null),
    },
  ]), [])
  const {
    errors: flowErrors,
    handleSubmit: handleFlowSubmit,
    clearFieldError: clearFlowError,
    clearAllErrors: clearFlowErrors,
  } = useGentleValidation({
    rules: flowRules,
    onValid: (f) => saveMut.mutate(f),
    recordKind: 'flow',
  })
  const onFlowKeyDown = useEnterAdvances({
    onSubmit: () => handleFlowSubmit(form)(null),
  })

  const toggleMut = useMutation({
    mutationFn: (id) => configApi.patch(`/api/flows/${id}/toggle`).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['flows']); toast.success('Flow toggled') }
  })

  const deleteMut = useMutation({
    mutationFn: (id) => configApi.delete(`/api/flows/${id}`),
    onSuccess: () => { qc.invalidateQueries(['flows']); toast.success('Flow deactivated') },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to deactivate flow — the item may be in use')
  })

  // ─── Helpers ───
  const buildPayload = useCallback((data) => {
    const payload = {
      name: data.name,
      description: data.description,
      filenamePattern: data.filenamePattern || null,
      sourcePath: data.sourcePath || '/inbox',
      destinationPath: data.destinationPath || '/outbox',
      priority: data.priority,
      active: data.active,
      steps: data.steps.map((s, i) => ({ type: s.type, config: s.config || {}, order: i })),
      matchCriteria: data.matchCriteria || null,
      direction: data.direction || null,
    }
    if (data.sourceAccountId) {
      payload.sourceAccount = { id: data.sourceAccountId }
    }
    if (data.deliveryMode === 'mailbox' && data.destinationAccountId) {
      payload.destinationAccount = { id: data.destinationAccountId }
    }
    if ((data.deliveryMode === 'external' || data.deliveryMode === 'both') && data.externalDestinationId) {
      payload.externalDestination = { id: data.externalDestinationId }
    }
    if (data.deliveryMode === 'both' && data.destinationAccountId) {
      payload.destinationAccount = { id: data.destinationAccountId }
    }
    return payload
  }, [])

  const openCreate = useCallback(() => {
    setEditingId(null)
    setForm({ ...defaultForm })
    setShowEditor(true)
  }, [])

  const openEdit = useCallback((flow) => {
    setEditingId(flow.id)
    const deliveryMode = flow.externalDestinationId && flow.destinationAccountId ? 'both'
      : flow.externalDestinationId ? 'external'
      : flow.destinationAccountId ? 'mailbox' : 'none'
    // Use matchCriteria if present, otherwise auto-generate from legacy fields
    const criteria = flow.matchCriteria || buildCriteriaFromLegacy(flow)
    setForm({
      name: flow.name || '',
      description: flow.description || '',
      filenamePattern: flow.filenamePattern || '',
      sourcePath: flow.sourcePath || '/inbox',
      destinationPath: flow.destinationPath || '/outbox',
      priority: flow.priority || 100,
      active: flow.active ?? true,
      steps: (flow.steps || []).map(s => ({ type: s.type, config: s.config || {}, order: s.order })),
      sourceAccountId: flow.sourceAccountId || '',
      protocol: flow.protocol || 'ANY',
      serverId: flow.server?.id || '',
      deliveryMode,
      externalDestinationId: flow.externalDestinationId || '',
      destinationAccountId: flow.destinationAccountId || '',
      matchCriteria: criteria,
      direction: flow.direction || null,
    })
    setShowEditor(true)
  }, [])

  const closeEditor = useCallback(() => {
    setShowEditor(false)
    setEditingId(null)
    setForm({ ...defaultForm })
    setShowAddStep(false)
    clearFlowErrors()
  }, [clearFlowErrors])

  const toggleFlowSelect = (id, event) => {
    // Shift-click range select across currently filtered flow list (Flexibility).
    if (event?.shiftKey && lastFlowClickedIdRef.current != null) {
      const ids = filteredFlows.map(f => f.id)
      const a = ids.indexOf(lastFlowClickedIdRef.current)
      const b = ids.indexOf(id)
      if (a !== -1 && b !== -1) {
        const [lo, hi] = a < b ? [a, b] : [b, a]
        const range = ids.slice(lo, hi + 1)
        setSelectedFlows(s => {
          const n = new Set(s)
          range.forEach(rid => n.add(rid))
          return n
        })
        lastFlowClickedIdRef.current = id
        return
      }
    }
    setSelectedFlows(s => {
      const n = new Set(s)
      n.has(id) ? n.delete(id) : n.add(id)
      return n
    })
    lastFlowClickedIdRef.current = id
  }

  const addStep = useCallback((type) => {
    const meta = STEP_TYPE_CATALOG[type]
    const defaultConfig = {}
    if (meta?.configFields) {
      meta.configFields.forEach(f => {
        if (f.type === 'select' && f.options?.length > 0) defaultConfig[f.key] = f.options[0]
      })
    }
    setForm(f => ({
      ...f, steps: [...f.steps, { type, config: defaultConfig, order: f.steps.length }]
    }))
  }, [])

  const removeStep = useCallback((idx) => {
    setForm(f => ({
      ...f,
      steps: f.steps.filter((_, i) => i !== idx).map((s, i) => ({ ...s, order: i }))
    }))
  }, [])

  const moveStep = useCallback((idx, dir) => {
    setForm(f => {
      const steps = [...f.steps]
      const targetIdx = idx + dir
      if (targetIdx < 0 || targetIdx >= steps.length) return f
      ;[steps[idx], steps[targetIdx]] = [steps[targetIdx], steps[idx]]
      return { ...f, steps: steps.map((s, i) => ({ ...s, order: i })) }
    })
  }, [])

  const updateStepConfig = useCallback((stepIdx, key, value) => {
    setForm(f => {
      const steps = [...f.steps]
      steps[stepIdx] = { ...steps[stepIdx], config: { ...steps[stepIdx].config, [key]: value } }
      return { ...f, steps }
    })
  }, [])

  const applyTemplate = useCallback((template) => {
    setForm(f => ({
      ...f,
      steps: template.steps.map((s, i) => ({ type: s.type, config: { ...s.config }, order: i }))
    }))
    toast.success(`Applied "${template.name}" template`)
  }, [])

  // ─── Sorting ───
  const [sortBy, setSortBy] = useState('name')
  const [sortDir, setSortDir] = useState('asc')
  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  // ─── Filtered flows ───
  const filteredFlows = useMemo(() => {
    let result = flows
    if (filter === 'active') result = result.filter(f => f.active)
    if (filter === 'inactive') result = result.filter(f => !f.active)
    // Phase 2 — /flows?partnerId=X filter. Best effort: flows carry partnerId via the
    // flow payload (see flow.partnerId usage around line 1317). Falls back to substring
    // match on name/description when the partner entity isn't directly joined.
    // TODO: once flow source/destination are fully partner-resolved server-side, prefer
    // matching against partner slug/displayName lists instead of substring.
    if (partnerIdFilter) {
      const partnerObj = partners.find(p => String(p.id) === String(partnerIdFilter))
      const partnerName = (partnerObj?.companyName || partnerObj?.name || '').toLowerCase()
      result = result.filter(f => {
        if (String(f.partnerId || '') === String(partnerIdFilter)) return true
        if (partnerName && (f.name?.toLowerCase().includes(partnerName) || f.description?.toLowerCase().includes(partnerName))) return true
        return false
      })
    }
    if (search) {
      const q = search.toLowerCase()
      result = result.filter(f =>
        f.name?.toLowerCase().includes(q) || f.description?.toLowerCase().includes(q) || f.sourcePath?.toLowerCase().includes(q)
      )
    }
    // Sort
    const arr = [...result]
    arr.sort((a, b) => {
      const va = a[sortBy] ?? ''
      const vb = b[sortBy] ?? ''
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [flows, filter, search, sortBy, sortDir, partnerIdFilter, partners])

  // ─── Execution counts per flow ───
  const executionCountByFlowId = useMemo(() => {
    const counts = {}
    executions.forEach(ex => {
      const fid = ex.flow?.id || ex.flowId
      if (fid) counts[fid] = (counts[fid] || 0) + 1
    })
    return counts
  }, [executions])

  const activeCount = flows.filter(f => f.active).length
  const inactiveCount = flows.filter(f => !f.active).length

  const toggleFlowSelectAll = () => {
    if (selectedFlows.size === filteredFlows.length) setSelectedFlows(new Set())
    else setSelectedFlows(new Set(filteredFlows.map(f => f.id)))
  }

  const handleBulkFlowAction = async (action) => {
    const ids = [...selectedFlows]
    if (ids.length === 0) return
    setBulkFlowLoading(true)
    try {
      if (action === 'delete') {
        // NOTE: there's no dedicated /api/flows/bulk DELETE — fan out single
        // deletes with Promise.allSettled so partial failures don't abort.
        const results = await Promise.allSettled(
          ids.map(id => configApi.delete(`/api/flows/${id}`).then(r => r.data))
        )
        const succeeded = results.filter(r => r.status === 'fulfilled').length
        const failed = results.filter(r => r.status === 'rejected').length
        if (failed === 0) toast.success(`Deleted ${succeeded} flow${succeeded !== 1 ? 's' : ''}`)
        else toast.error(`Deleted ${succeeded} of ${ids.length} flows — ${failed} failed`)
      } else {
        // For 'enable': toggle only disabled flows; for 'disable': toggle only active flows
        const targetFlows = ids.filter(id => {
          const flow = flows.find(f => f.id === id)
          return action === 'enable' ? !flow?.active : flow?.active
        })
        if (targetFlows.length === 0) {
          toast.error(`No flows to ${action} — all selected are already ${action === 'enable' ? 'active' : 'disabled'}`)
          setShowBulkFlowConfirm(null)
          return
        }
        const results = await Promise.allSettled(
          targetFlows.map(id => configApi.patch(`/api/flows/${id}/toggle`).then(r => r.data))
        )
        const succeeded = results.filter(r => r.status === 'fulfilled').length
        const failed = results.filter(r => r.status === 'rejected').length
        const label = action === 'enable' ? 'Activated' : 'Deactivated'
        if (failed === 0) toast.success(`${label} ${succeeded} flow${succeeded !== 1 ? 's' : ''}`)
        else toast.error(`${label} ${succeeded} of ${targetFlows.length} flows — ${failed} failed`)
      }
      setSelectedFlows(new Set())
      setShowBulkFlowConfirm(null)
      qc.invalidateQueries(['flows'])
    } finally {
      setBulkFlowLoading(false)
    }
  }

  // Selection-aware flags for bulk toolbar visibility (Guidance).
  const selectedFlowObjs = useMemo(
    () => filteredFlows.filter(f => selectedFlows.has(f.id)),
    [filteredFlows, selectedFlows],
  )
  const hasInactiveFlowSelected = selectedFlowObjs.some(f => !f.active)
  const hasActiveFlowSelected = selectedFlowObjs.some(f => f.active)

  // Clear flow selection when filter or search changes (Stability).
  useEffect(() => {
    setSelectedFlows(new Set())
    lastFlowClickedIdRef.current = null
  }, [filter, search, partnerIdFilter])

  // Note: removed early return for LoadingSpinner — header/filters/tabs stay
  // visible and the flow list slot renders 9 Skeleton.Card placeholders
  // (Speed perceived / Guidance).

  return (
    <div className="space-y-6">
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">File Processing Flows</h1>
          <p className="text-secondary text-sm">
            {flows.length} flow{flows.length !== 1 ? 's' : ''} configured
            {activeCount > 0 && <span className="text-emerald-600 ml-1">({activeCount} active)</span>}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="relative">
            <MagnifyingGlassIcon className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search flows..." className="pl-9 w-64" />
          </div>
          <button className="btn-secondary flex items-center gap-1.5" onClick={() => setShowQuickFlow(true)}>
            <BoltIcon className="w-4 h-4" /> Quick Flow
          </button>
          <button className="btn-primary" onClick={openCreate}>
            <PlusIcon className="w-4 h-4" /> New Flow
          </button>
        </div>
      </div>

      {/* ─── Main Tabs ─── */}
      <div className="flex items-center gap-2 border-b border-border pb-2">
        {[
          { key: 'flows', label: 'Flows' },
          { key: 'catalog', label: 'Function Catalog' },
        ].map(t => (
          <button key={t.key} onClick={() => setActiveTab(t.key)}
            className={`px-4 py-2 text-sm font-medium rounded-t-lg transition-colors ${
              activeTab === t.key
                ? 'bg-surface text-blue-700 border border-border border-b-white -mb-[1px]'
                : 'text-secondary hover:text-primary hover:bg-canvas'
            }`}>
            {t.label}
          </button>
        ))}
      </div>

      {/* ─── Filter bar (flows tab only) ─── */}
      {activeTab === 'flows' && (
      <div className="flex items-center gap-2 flex-wrap">
        <FunnelIcon className="w-4 h-4 text-muted" />
        {[
          { key: 'all', label: `All (${flows.length})` },
          { key: 'active', label: `Active (${activeCount})` },
          { key: 'inactive', label: `Inactive (${inactiveCount})` },
        ].map(f => (
          <button key={f.key} onClick={() => setFilter(f.key)}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              filter === f.key ? 'bg-blue-100 text-blue-700' : 'bg-hover text-secondary hover:bg-gray-200'
            }`}>
            {f.label}
          </button>
        ))}
        {/* Phase 2 — active cross-link chip. Transparency: user sees why the list is
            restricted and can clear it in one click (Resilience). */}
        {partnerIdFilter && (
          <button
            onClick={clearPartnerFilter}
            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium bg-purple-100 text-purple-700 hover:bg-purple-200"
            title="Clear partner filter"
          >
            Partner: <span className="font-mono">{(partners.find(p => String(p.id) === String(partnerIdFilter))?.companyName) || partnerIdFilter}</span>
            <span className="ml-0.5">×</span>
          </button>
        )}
      </div>
      )}

      {/* ═══ Function Catalog Tab ═══ */}
      {activeTab === 'catalog' && (
        <>
          <div className="card">
            <h3 className="font-semibold text-primary mb-1">Function Catalog</h3>
            <p className="text-sm text-secondary mb-4">All registered flow functions — built-in + imported</p>
            {functionCatalog.length === 0 ? (
              <div className="text-center py-8 text-muted text-sm">
                <p>No catalog data from backend. Showing built-in functions only.</p>
                <div className="overflow-x-auto mt-4">
                  <table className="w-full">
                    <thead><tr className="border-b border-border">
                      <th className="table-header">Type</th>
                      <th className="table-header">Label</th>
                      <th className="table-header">Category</th>
                      <th className="table-header">Config Fields</th>
                    </tr></thead>
                    <tbody>
                      {Object.entries(STEP_TYPE_CATALOG).map(([type, meta]) => (
                        <tr key={type} className="table-row">
                          <td className="table-cell"><code className="text-xs font-mono bg-hover px-1.5 py-0.5 rounded">{type}</code></td>
                          <td className="table-cell text-sm"><span className={`inline-flex items-center gap-1 ${meta.color} px-2 py-0.5 rounded-full text-xs font-medium`}>{meta.icon} {meta.label}</span></td>
                          <td className="table-cell text-xs text-secondary">{meta.category}</td>
                          <td className="table-cell text-xs text-muted">{meta.configFields?.length > 0 ? meta.configFields.map(f => f.key).join(', ') : '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead><tr className="border-b border-border">
                    <th className="table-header">Type</th>
                    <th className="table-header">I/O Mode</th>
                    <th className="table-header">Description</th>
                    <th className="table-header">Config Schema</th>
                  </tr></thead>
                  <tbody>
                    {functionCatalog.map(fn => (
                      <tr key={fn.type} className="table-row">
                        <td className="table-cell"><code className="text-xs font-mono bg-hover px-1.5 py-0.5 rounded">{fn.type}</code></td>
                        <td className="table-cell">
                          <span className={`badge ${fn.ioMode === 'STREAMING' ? 'bg-emerald-100 text-emerald-700' : fn.ioMode === 'METADATA_ONLY' ? 'bg-blue-100 text-blue-700' : 'bg-amber-100 text-amber-700'}`}>
                            {fn.ioMode || 'STANDARD'}
                          </span>
                        </td>
                        <td className="table-cell text-sm text-secondary">{fn.description || '—'}</td>
                        <td className="table-cell text-xs text-muted font-mono">{fn.configSchema || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Function Import */}
          <div className="card">
            <h3 className="font-semibold text-primary mb-1">Import External Function</h3>
            <p className="text-sm text-secondary mb-4">Register a gRPC service or WASM module as a flow function</p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-xs font-medium text-secondary">Function Name</label>
                <input placeholder="e.g. custom-transform" value={importName} onChange={e => setImportName(e.target.value)} />
              </div>
              <div>
                <label className="text-xs font-medium text-secondary">Runtime</label>
                <select value={importRuntime} onChange={e => setImportRuntime(e.target.value)}>
                  <option value="GRPC">gRPC Service</option>
                  <option value="WASM">WASM Module</option>
                </select>
              </div>
              <div>
                <label className="text-xs font-medium text-secondary">Endpoint URL (for gRPC)</label>
                <input placeholder="grpc://localhost:50051" value={importEndpoint} onChange={e => setImportEndpoint(e.target.value)} />
              </div>
              <div>
                <label className="text-xs font-medium text-secondary">Description</label>
                <input placeholder="What this function does" value={importDesc} onChange={e => setImportDesc(e.target.value)} />
              </div>
            </div>
            <div className="mt-3">
              <button onClick={handleImportFunction} className="btn-primary text-sm">Import Function</button>
            </div>
          </div>
        </>
      )}

      {/* ─── Sort controls (flows tab only) ─── */}
      {activeTab === 'flows' && filteredFlows.length > 0 && (
        <div className="flex items-center gap-2 text-xs text-secondary">
          <ArrowsUpDownIcon className="w-3.5 h-3.5 text-muted" />
          <span className="text-muted">Sort:</span>
          {[
            { key: 'name', label: 'Name' },
            { key: 'priority', label: 'Priority' },
            { key: 'active', label: 'Active' },
          ].map(col => (
            <button key={col.key} onClick={() => toggleSort(col.key)}
              className={`px-2 py-0.5 rounded text-xs font-medium transition-colors ${
                sortBy === col.key ? 'bg-blue-100 text-blue-700' : 'bg-hover text-secondary hover:bg-gray-200'
              }`}>
              {col.label} {sortBy === col.key && (sortDir === 'asc' ? '\u2191' : '\u2193')}
            </button>
          ))}
        </div>
      )}

      {/* ─── Flow Bulk Actions — sticky top toolbar, visible on selection ─── */}
      {activeTab === 'flows' && selectedFlows.size > 0 && (
        <div className="sticky top-0 z-20 flex items-center gap-3 bg-[rgba(100,140,255,0.12)] border border-[rgba(100,140,255,0.25)] rounded-xl px-4 py-2 backdrop-blur">
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-[rgba(100,140,255,0.22)] text-primary">
            {selectedFlows.size} selected
          </span>
          <button className="text-xs text-secondary hover:text-primary underline" onClick={toggleFlowSelectAll}>
            {selectedFlows.size === filteredFlows.length ? 'Deselect all' : 'Select all'}
          </button>
          <button className="text-xs text-secondary hover:text-primary underline" onClick={() => setSelectedFlows(new Set())}>Clear</button>
          <div className="flex-1" />
          {hasInactiveFlowSelected && (
            <button className="btn-secondary text-sm" onClick={() => setShowBulkFlowConfirm('enable')}>Activate</button>
          )}
          {hasActiveFlowSelected && (
            <button className="btn-secondary text-sm" onClick={() => setShowBulkFlowConfirm('disable')}>Deactivate</button>
          )}
          <button
            className="btn-secondary text-sm"
            style={{ background: 'rgba(220,38,38,0.15)', color: 'rgb(248,113,113)', border: '1px solid rgba(220,38,38,0.4)' }}
            onClick={() => setShowBulkFlowConfirm('delete')}
          >
            Delete
          </button>
        </div>
      )}

      {/* ─── Flow List ─── */}
      {activeTab === 'flows' && isFirstLoad ? (
        showSkeleton ? (
          <div className="grid gap-3">
            {Array.from({ length: 9 }).map((_, i) => (
              <Skeleton.Card key={i} lines={2} />
            ))}
          </div>
        ) : (
          <div style={{ minHeight: '480px' }} aria-hidden="true" />
        )
      ) : activeTab === 'flows' && (filteredFlows.length === 0 ? (
        <div className="card">
          <EmptyState
            title={filter !== 'all' ? `No ${filter} flows` : 'No flows configured'}
            description={filter !== 'all' ? 'Try changing the filter.' : 'Create a flow to define how files are processed when they arrive.'}
            action={filter === 'all' && (
              <button className="btn-primary" onClick={openCreate}>
                <PlusIcon className="w-4 h-4" /> New Flow
              </button>
            )}
          />
        </div>
      ) : (
        <div className="grid gap-3">
          {filteredFlows.map(flow => (
            <div key={flow.id} className="card hover:shadow-md transition-shadow cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => openEdit(flow)}>
              <div className="flex items-start justify-between gap-4">
                <div className="flex items-center self-center mr-1" onClick={e => e.stopPropagation()}>
                  <input type="checkbox" checked={selectedFlows.has(flow.id)} onClick={e => toggleFlowSelect(flow.id, e)} onChange={() => {}} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-semibold text-primary">{flow.name}</h3>
                    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${
                      flow.active
                        ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                        : 'bg-red-50 text-red-700 border border-red-200'
                    }`}>
                      <span className={`w-1.5 h-1.5 rounded-full ${flow.active ? 'bg-emerald-500' : 'bg-red-500'}`} />
                      {flow.active ? 'Active' : 'Disabled'}
                    </span>
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-600 border border-slate-200">
                      P{flow.priority}
                    </span>
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-600 border border-blue-200">
                      {flow.steps?.length || 0} step{(flow.steps?.length || 0) !== 1 ? 's' : ''}
                    </span>
                  </div>
                  {flow.description && (
                    <p className="text-sm text-secondary mt-1">{flow.description}</p>
                  )}
                  {/* Usage chips: source, destination, partner, external dest, execution count */}
                  <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                    {flow.sourceAccountId && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-blue-50 text-blue-700 border border-blue-200" onClick={e => e.stopPropagation()}>
                        Source: <ConfigLink type="account" id={flow.sourceAccountId} name={flow.sourceAccountUsername || flow.sourceAccountId} navigateTo="/accounts" />
                      </span>
                    )}
                    {flow.destinationAccountId && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-green-50 text-green-700 border border-green-200" onClick={e => e.stopPropagation()}>
                        Dest: <ConfigLink type="account" id={flow.destinationAccountId} name={flow.destinationAccountUsername || flow.destinationAccountId} navigateTo="/accounts" />
                      </span>
                    )}
                    {flow.partnerId && (() => {
                      const p = partners.find(pp => pp.id === flow.partnerId)
                      return (
                        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-purple-50 text-purple-700 border border-purple-200" onClick={e => e.stopPropagation()}>
                          Partner: <ConfigLink type="partner" id={flow.partnerId} name={p?.companyName || p?.name || flow.partnerId} navigateTo={`/partners/${flow.partnerId}`} />
                        </span>
                      )
                    })()}
                    {flow.externalDestinationId && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-amber-50 text-amber-700 border border-amber-200" onClick={e => e.stopPropagation()}>
                        Ext: <ConfigLink type="destination" id={flow.externalDestinationId} name={flow.externalDestinationName || 'External'} />
                      </span>
                    )}
                    {executionCountByFlowId[flow.id] > 0 && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 text-slate-600 border border-slate-200">
                        {executionCountByFlowId[flow.id]} execution{executionCountByFlowId[flow.id] !== 1 ? 's' : ''}
                      </span>
                    )}
                  </div>
                  <div className="mt-1">
                    {flow.matchCriteria ? (
                      <MatchSummaryBadges criteria={flow.matchCriteria} />
                    ) : (
                      <div className="flex items-center gap-4 text-xs text-muted">
                        {flow.sourceAccountId && (
                          <span>Source: <span className="font-medium text-secondary">{flow.sourceAccountUsername || flow.sourceAccountId}</span></span>
                        )}
                        {flow.filenamePattern && (
                          <span className="font-mono">Pattern: {flow.filenamePattern}</span>
                        )}
                        {flow.sourcePath && (
                          <span className="font-mono">Path: {flow.sourcePath}</span>
                        )}
                      </div>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  {/* Phase 2 — "View Executions" deep-link to unified Activity Monitor.
                      Guidance: every flow has a direct path to its execution history. */}
                  <Link
                    to={`/operations/activity?flowId=${encodeURIComponent(flow.id)}`}
                    onClick={e => e.stopPropagation()}
                    title="View recent executions for this flow"
                    aria-label="View recent executions for this flow"
                    className="flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium rounded-lg transition-colors text-blue-600 hover:bg-blue-50 hover:text-blue-700"
                  >
                    <ClockIcon className="w-3.5 h-3.5" />
                    Executions
                  </Link>
                  <button
                    onClick={(e) => { e.stopPropagation(); setDryRunPrompt({ flowId: flow.id, flowName: flow.name }); setDryRunFilename('') }}
                    disabled={dryRunMut.isPending}
                    className="flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium rounded-lg transition-colors text-violet-600 hover:bg-violet-50 hover:text-violet-700 disabled:opacity-50"
                    title="Simulate this flow without writing to storage or delivering to partners"
                  >
                    <BeakerIcon className="w-3.5 h-3.5" />
                    Dry Run
                  </button>
                  <button onClick={(e) => { e.stopPropagation(); openEdit(flow) }}
                    className="p-2 text-muted hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                    title="Edit flow" aria-label="Edit flow">
                    <PencilSquareIcon className="w-4 h-4" />
                  </button>
                  <button onClick={(e) => { e.stopPropagation(); toggleMut.mutate(flow.id) }}
                    className={`px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                      flow.active
                        ? 'text-red-600 hover:bg-red-50'
                        : 'text-emerald-600 hover:bg-emerald-50'
                    }`}>
                    {flow.active ? 'Disable' : 'Enable'}
                  </button>
                  <button onClick={(e) => {
                    e.stopPropagation()
                    setConfirmDeleteFlow(flow)
                  }}
                    className="p-2 text-muted hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                    title="Delete flow" aria-label="Delete flow">
                    <TrashIcon className="w-4 h-4" />
                  </button>
                </div>
              </div>
              {/* Pipeline visualization */}
              <div className="mt-3 pt-3 border-t border-border">
                <MiniPipeline steps={flow.steps || []} />
              </div>
            </div>
          ))}
        </div>
      ))}

      {/* ─── Pending Approvals ─── */}
      {activeTab === 'flows' && pendingApprovals.length > 0 && (
        <div className="card border-l-4 border-purple-500">
          <div className="flex items-center gap-2 mb-4">
            <HandRaisedIcon className="w-5 h-5 text-purple-500" />
            <h3 className="font-semibold text-primary">Pending Approvals</h3>
            <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-purple-100 text-purple-700">
              {pendingApprovals.length}
            </span>
            <span className="text-xs text-muted ml-auto">(auto-refresh 15s)</span>
          </div>
          <div className="space-y-2">
            {pendingApprovals.map(ap => (
              <ApprovalRow
                key={ap.id}
                ap={ap}
                onApprove={(note) => approveMut.mutate({ trackId: ap.trackId, stepIndex: ap.stepIndex, note })}
                onReject={(note) => rejectMut.mutate({ trackId: ap.trackId, stepIndex: ap.stepIndex, note })}
                busy={approveMut.isPending || rejectMut.isPending}
              />
            ))}
          </div>
        </div>
      )}

      {/* ─── Scheduled Retries (only shown when there are any) ─── */}
      {activeTab === 'flows' && scheduledRetries.length > 0 && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-4">
          <div className="flex items-center gap-2 mb-3">
            <ClockIcon className="w-4 h-4 text-blue-500" />
            <h3 className="font-semibold text-blue-800 text-sm">
              Scheduled Retries ({scheduledRetries.length})
            </h3>
          </div>
          <div className="space-y-2">
            {scheduledRetries.map(r => (
              <div key={r.trackId} className="flex items-center gap-3 text-xs bg-surface rounded-lg px-3 py-2 border border-blue-100">
                <span className="font-mono font-bold text-blue-600">{r.trackId}</span>
                <span className="text-secondary truncate max-w-40">{r.originalFilename}</span>
                <span className="text-muted">{r.flowName || '—'}</span>
                <div className="flex-1" />
                <span className="text-secondary">by {r.scheduledBy}</span>
                <span className="font-semibold text-blue-700">
                  {new Date(r.scheduledAt).toLocaleString()}
                </span>
                <button
                  onClick={() => cancelScheduleMut.mutate(r.trackId)}
                  disabled={cancelScheduleMut.isPending}
                  className="text-muted hover:text-red-500 transition-colors"
                  title="Cancel scheduled retry"
                  aria-label="Cancel scheduled retry"
                >
                  <XCircleIcon className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ─── Execution History ─── */}
      {activeTab === 'flows' && <div className="card">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <ClockIcon className="w-5 h-5 text-muted" />
            <h3 className="font-semibold text-primary">Execution History</h3>
            <span className="text-xs text-muted">(auto-refresh 10s)</span>
          </div>
          <div className="flex items-center gap-2">
            {/* Bulk restart toolbar — visible when rows are selected */}
            {selectedIds.size > 0 && (
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-red-50 border border-red-200">
                <span className="text-xs font-medium text-red-700">
                  {selectedIds.size} selected
                </span>
                <button
                  onClick={() => bulkRestartMut.mutate([...selectedIds])}
                  disabled={bulkRestartMut.isPending}
                  className="flex items-center gap-1.5 px-2.5 py-1 text-xs font-semibold rounded-md bg-red-600 text-white hover:bg-red-700 disabled:opacity-60 transition-colors"
                >
                  <ArrowPathIcon className={`w-3.5 h-3.5 ${bulkRestartMut.isPending ? 'animate-spin' : ''}`} />
                  {bulkRestartMut.isPending ? 'Restarting…' : `Restart ${selectedIds.size}`}
                </button>
                <button
                  onClick={() => setSelectedIds(new Set())}
                  className="text-xs text-red-500 hover:text-red-700 transition-colors"
                >
                  Clear
                </button>
              </div>
            )}
            {/* Select-all-failed button */}
            {executions.some(ex => RESTARTABLE.has(ex.status)) && selectedIds.size === 0 && (
              <button
                onClick={() => {
                  const failedIds = executions.filter(ex => RESTARTABLE.has(ex.status)).map(ex => ex.trackId)
                  setSelectedIds(new Set(failedIds))
                }}
                className="text-xs text-secondary hover:text-red-600 px-2 py-1 rounded hover:bg-red-50 transition-colors"
              >
                Select all failed
              </button>
            )}
            <button onClick={() => qc.invalidateQueries(['flow-executions'])}
              className="p-1.5 text-muted hover:text-blue-600 rounded-lg hover:bg-blue-50 transition-colors"
              title="Refresh now" aria-label="Refresh now">
              <ArrowPathIcon className="w-4 h-4" />
            </button>
          </div>
        </div>
        <p className="text-xs text-muted mb-2">Tip: Double-click any row to open detailed view</p>
        {executions.length === 0 ? (
          <div className="text-center py-8 text-muted text-sm">
            No flow executions yet. Executions will appear here when files are processed.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border">
                  <th className="table-header w-8"></th>
                  <th className="table-header">Track ID</th>
                  <th className="table-header">Flow</th>
                  <th className="table-header">Filename</th>
                  <th className="table-header">Status</th>
                  <th className="table-header">Progress</th>
                  <th className="table-header">Duration</th>
                  <th className="table-header">Started</th>
                  <th className="table-header">Retry</th>
                </tr>
              </thead>
              <tbody>
                {executions.map(ex => (
                  <ExecutionRow
                    key={ex.trackId || ex.id}
                    ex={ex}
                    selected={selectedIds.has(ex.trackId)}
                    onToggle={(id) => setSelectedIds(prev => {
                      const next = new Set(prev)
                      next.has(id) ? next.delete(id) : next.add(id)
                      return next
                    })}
                    onSkipStep={(stepIndex) => skipStepMut.mutate({ trackId: ex.trackId, stepIndex })}
                    skipPending={skipStepMut.isPending}
                    onScheduleRetry={(scheduledAt) => scheduleRetryMut.mutate({ trackId: ex.trackId, scheduledAt })}
                    onCancelSchedule={() => cancelScheduleMut.mutate(ex.trackId)}
                    schedulePending={scheduleRetryMut.isPending || cancelScheduleMut.isPending}
                    onOpenDrawer={setDrawerTrackId}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>}

      {/* ═══ Dry Run Filename Prompt ═══ */}
      {dryRunPrompt && (
        <Modal title={`Dry Run: ${dryRunPrompt.flowName}`} onClose={() => setDryRunPrompt(null)}>
          <div className="space-y-4">
            <p className="text-sm text-secondary">
              Simulate this flow without writing to storage or delivering to partners.
              Optionally provide a sample filename to test pattern matching.
            </p>
            <div>
              <label className="text-xs font-medium text-secondary">Sample Filename (optional)</label>
              <input
                value={dryRunFilename}
                onChange={e => setDryRunFilename(e.target.value)}
                placeholder="e.g. invoice-2024.xml"
                className="mt-1 font-mono text-sm"
                onKeyDown={e => {
                  if (e.key === 'Enter') {
                    dryRunMut.mutate({ flowId: dryRunPrompt.flowId, filename: dryRunFilename || 'test-file.xml' })
                    setDryRunPrompt(null)
                  }
                }}
              />
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setDryRunPrompt(null)} className="btn-secondary">Cancel</button>
              <button
                onClick={() => {
                  dryRunMut.mutate({ flowId: dryRunPrompt.flowId, filename: dryRunFilename || 'test-file.xml' })
                  setDryRunPrompt(null)
                }}
                disabled={dryRunMut.isPending}
                className="btn-primary flex items-center gap-1.5"
              >
                <BeakerIcon className="w-4 h-4" />
                {dryRunMut.isPending ? 'Running...' : 'Run Simulation'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ═══ Dry Run Results Modal ═══ */}
      {dryRunResult && (
        <DryRunModal result={dryRunResult} onClose={() => setDryRunResult(null)} />
      )}

      <ConfirmDialog
        open={!!confirmDeleteFlow}
        variant="danger"
        title="Delete flow?"
        message={confirmDeleteFlow ? `Are you sure you want to delete flow "${confirmDeleteFlow.name}"? This will deactivate it. This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteMut.isPending}
        onConfirm={() => { deleteMut.mutate(confirmDeleteFlow.id); setConfirmDeleteFlow(null) }}
        onCancel={() => setConfirmDeleteFlow(null)}
      />

      {/* Bulk Flow Confirm — ConfirmDialog primitive */}
      <ConfirmDialog
        open={!!showBulkFlowConfirm}
        variant={showBulkFlowConfirm === 'delete' ? 'danger' : 'warning'}
        title={
          showBulkFlowConfirm === 'delete'
            ? `Delete ${selectedFlows.size} flow${selectedFlows.size !== 1 ? 's' : ''}?`
            : showBulkFlowConfirm === 'enable'
              ? `Activate ${selectedFlows.size} flow${selectedFlows.size !== 1 ? 's' : ''}?`
              : showBulkFlowConfirm === 'disable'
                ? `Deactivate ${selectedFlows.size} flow${selectedFlows.size !== 1 ? 's' : ''}?`
                : ''
        }
        message={
          showBulkFlowConfirm === 'delete'
            ? 'This will permanently remove the selected processing flows. Any file matching these flows will fall through to the default handler.'
            : showBulkFlowConfirm === 'enable'
              ? 'Selected flows will start processing matching files immediately.'
              : 'Selected flows will stop processing matching files until re-activated.'
        }
        confirmLabel={
          showBulkFlowConfirm === 'delete' ? 'Delete all'
          : showBulkFlowConfirm === 'enable' ? 'Activate all'
          : 'Deactivate all'
        }
        loading={bulkFlowLoading}
        onConfirm={() => handleBulkFlowAction(showBulkFlowConfirm)}
        onCancel={() => setShowBulkFlowConfirm(null)}
      />

      {/* Execution Detail Drawer — triggered by double-clicking an execution row */}
      <ExecutionDetailDrawer
        trackId={drawerTrackId}
        open={!!drawerTrackId}
        onClose={() => setDrawerTrackId(null)}
        showActions
      />

      {/* ═══ Quick Flow Wizard ═══ */}
      <QuickFlowWizard open={showQuickFlow} onClose={() => setShowQuickFlow(false)} />

      {/* ═══ Flow Builder Modal ═══ */}
      {showEditor && (
        <Modal title={editingId ? 'Edit Processing Flow' : 'Create Processing Flow'} size="xl" onClose={closeEditor}>
          <form onSubmit={handleFlowSubmit(form)} onKeyDown={onFlowKeyDown} className="space-y-6">

            {/* ─── Templates (create only) ─── */}
            {!editingId && (
              <div>
                <label className="text-xs font-semibold text-secondary uppercase tracking-wider">Quick Start Templates</label>
                <div className="mt-2 grid grid-cols-2 gap-2">
                  {FLOW_TEMPLATES.map(tpl => (
                    <button key={tpl.name} type="button" onClick={() => applyTemplate(tpl)}
                      className="flex items-start gap-3 p-3 rounded-lg border border-border hover:border-blue-300 hover:bg-blue-50/50 text-left transition-all group">
                      <SparklesIcon className="w-5 h-5 text-blue-400 group-hover:text-blue-600 flex-shrink-0 mt-0.5" />
                      <div>
                        <div className="text-sm font-medium text-primary group-hover:text-blue-700">{tpl.name}</div>
                        <div className="text-xs text-muted mt-0.5">{tpl.desc}</div>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* ─── Basic Info ─── */}
            <div>
              <label className="text-xs font-semibold text-secondary uppercase tracking-wider">Flow Details</label>
              <div className="mt-2 grid grid-cols-2 gap-4">
                <FormField
                  label="Flow name"
                  required
                  name="name"
                  error={flowErrors.name}
                  valid={!flowErrors.name && !!form.name}
                  helper="What users will see in the flow list — make it descriptive"
                >
                  <input
                    value={form.name}
                    onChange={e => { setForm(f => ({ ...f, name: e.target.value })); clearFlowError('name') }}
                    placeholder="e.g. partner-inbound-pgp"
                  />
                </FormField>
                <FormField
                  label="Priority"
                  name="priority"
                  helper="Lower numbers match first when multiple flows could handle the same file"
                  tooltip="When a file arrives, the platform walks flows in priority order and runs the first one whose match criteria pass. Leave at 100 unless you're intentionally ordering overlapping flows."
                >
                  <div className="flex items-center gap-3">
                    <input
                      type="range"
                      min="1"
                      max="1000"
                      value={form.priority}
                      onChange={e => setForm(f => ({ ...f, priority: parseInt(e.target.value) }))}
                      className="flex-1 h-2 bg-gray-200 rounded-lg appearance-auto cursor-pointer accent-blue-600"
                    />
                    <input
                      type="number"
                      min="1"
                      max="1000"
                      value={form.priority}
                      onChange={e => setForm(f => ({ ...f, priority: parseInt(e.target.value) || 100 }))}
                      className="w-20 text-center text-sm"
                    />
                  </div>
                </FormField>
              </div>
              <div className="mt-3">
                <FormField
                  label="Description"
                  name="description"
                  helper="One-sentence summary of what this flow does — used for grouping in the Flows page (encryption, EDI, delivery, etc.)"
                >
                  <input
                    value={form.description}
                    onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                    placeholder="What this flow does..."
                  />
                </FormField>
              </div>
              {flowErrors.steps && (
                <p className="mt-3 text-xs flex items-start gap-1.5" style={{ color: 'rgb(245, 158, 11)' }} role="alert">
                  <span
                    aria-hidden="true"
                    className="inline-flex items-center justify-center w-3 h-3 rounded-full mt-0.5 flex-shrink-0"
                    style={{ background: 'rgb(245, 158, 11)', color: '#1a1510', fontSize: '8px', fontWeight: 'bold' }}
                  >!</span>
                  <span>{flowErrors.steps}</span>
                </p>
              )}
            </div>

            {/* ─── Match Criteria (replaces Source Configuration) ─── */}
            <MatchCriteriaBuilder
              value={form.matchCriteria}
              onChange={(criteria) => setForm(f => ({ ...f, matchCriteria: criteria }))}
              accounts={accounts}
              partners={partners}
            />

            {/* ─── Source Configuration ─── */}
            <div>
              <label className="text-xs font-semibold text-secondary uppercase tracking-wider">Source Configuration</label>
              <div className="mt-2 grid grid-cols-2 gap-4">
                <div>
                  <label htmlFor="flow-protocol">Protocol Filter</label>
                  <select id="flow-protocol" value={form.protocol} onChange={e => handleProtocolChange(e.target.value)}>
                    {PROTOCOLS.map(p => (
                      <option key={p} value={p}>{p === 'ANY' ? 'Any Protocol' : p}</option>
                    ))}
                  </select>
                  <p className="text-[10px] text-muted mt-1">Filters account and server dropdowns below</p>
                </div>
                <div>
                  <label htmlFor="flow-direction">Direction</label>
                  <select id="flow-direction" value={form.direction || ''} onChange={e => setForm(f => ({ ...f, direction: e.target.value || null }))}>
                    <option value="">Any Direction</option>
                    <option value="INBOUND">Inbound</option>
                    <option value="OUTBOUND">Outbound</option>
                  </select>
                </div>
              </div>
              <div className="mt-3 grid grid-cols-3 gap-4">
                <div>
                  <label htmlFor="flow-source-account">Source Account</label>
                  <select id="flow-source-account" value={form.sourceAccountId} onChange={e => setForm(f => ({ ...f, sourceAccountId: e.target.value }))}>
                    <option value="">-- Select Account --</option>
                    {filteredAccounts.map(a => (
                      <option key={a.id} value={a.id}>{a.username} ({a.protocol})</option>
                    ))}
                  </select>
                  {form.protocol !== 'ANY' && filteredAccounts.length === 0 && (
                    <p className="text-xs text-amber-500 mt-1">No {form.protocol} accounts found</p>
                  )}
                </div>
                <div>
                  <label htmlFor="flow-server">Server</label>
                  <select id="flow-server" value={form.serverId} onChange={e => setForm(f => ({ ...f, serverId: e.target.value }))}>
                    <option value="">-- Select Server --</option>
                    {filteredServers.map(s => (
                      <option key={s.id} value={s.id}>{s.name || s.hostname} ({s.protocol})</option>
                    ))}
                  </select>
                  {form.protocol !== 'ANY' && filteredServers.length === 0 && (
                    <p className="text-xs text-amber-500 mt-1">No {form.protocol} servers found</p>
                  )}
                </div>
                <div>
                  <label htmlFor="flow-source-path">Legacy: Source Path</label>
                  <input id="flow-source-path" value={form.sourcePath}
                    onChange={e => setForm(f => ({ ...f, sourcePath: e.target.value }))}
                    placeholder="/inbox" className="font-mono text-sm" />
                </div>
              </div>
            </div>

            {/* ─── Processing Pipeline ─── */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <label className="text-xs font-semibold text-secondary uppercase tracking-wider">
                  Processing Pipeline ({form.steps.length} step{form.steps.length !== 1 ? 's' : ''})
                </label>
                {form.steps.length > 0 && (
                  <MiniPipeline steps={form.steps} />
                )}
              </div>
              <div className="space-y-2 mt-2">
                {form.steps.map((step, i) => (
                  <StepCard
                    key={`${step.type}-${i}`}
                    step={step}
                    index={i}
                    total={form.steps.length}
                    onRemove={() => removeStep(i)}
                    onMoveUp={() => moveStep(i, -1)}
                    onMoveDown={() => moveStep(i, 1)}
                    onConfigChange={(key, val) => updateStepConfig(i, key, val)}
                    availableQueues={functionQueues}
                  />
                ))}
                {form.steps.length === 0 && (
                  <div className="text-center py-6 border-2 border-dashed border-border rounded-lg">
                    <ArrowsUpDownIcon className="w-8 h-8 text-gray-300 mx-auto mb-2" />
                    <p className="text-sm text-muted">No steps added yet</p>
                    <p className="text-xs text-gray-300 mt-1">Use a template above or add steps manually below</p>
                  </div>
                )}
              </div>
              <div className="mt-3 relative flex items-center gap-2">
                <button type="button" onClick={() => setShowAddStep(!showAddStep)}
                  className="btn-secondary text-sm">
                  <PlusIcon className="w-4 h-4" /> Add Step
                </button>
                {showAddStep && (
                  <>
                    <div className="fixed inset-0 z-20" onClick={() => setShowAddStep(false)} />
                    <AddStepDropdown onAdd={addStep} onClose={() => setShowAddStep(false)} />
                  </>
                )}
                <button
                  type="button"
                  onClick={() => {
                    setAiSuggestions(null)
                    aiSuggestMut.mutate({
                      sourceAccountId: form.sourceAccountId || null,
                      filenamePattern: form.filenamePattern || null,
                      direction: form.direction || null,
                      existingSteps: form.steps.map(s => s.type),
                    })
                  }}
                  disabled={!aiAvailable || aiSuggestMut.isPending}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg transition-colors text-violet-600 hover:bg-violet-50 hover:text-violet-700 border border-violet-200 disabled:opacity-40 disabled:cursor-not-allowed"
                  title={!aiAvailable ? 'AI engine not available' : 'Get AI-suggested steps based on flow configuration'}
                >
                  <SparklesIcon className="w-4 h-4" />
                  {aiSuggestMut.isPending ? 'Suggesting...' : 'AI Suggest'}
                </button>
              </div>

              {/* AI Suggestions panel */}
              {aiSuggestions && aiSuggestions.steps.length > 0 && (
                <div className="mt-3 p-3 rounded-lg border border-violet-200 bg-violet-50/50">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-semibold text-violet-700 flex items-center gap-1">
                      <SparklesIcon className="w-3.5 h-3.5" /> AI-Suggested Steps
                    </span>
                    <button type="button" onClick={() => setAiSuggestions(null)}
                      className="text-xs text-muted hover:text-secondary">Dismiss</button>
                  </div>
                  <div className="space-y-1.5">
                    {aiSuggestions.steps.map((step, i) => {
                      const meta = STEP_TYPE_CATALOG[step.type] || { label: step.type, icon: '?', color: 'text-secondary bg-hover' }
                      return (
                        <div key={i} className="flex items-center gap-2 text-sm">
                          <span className="text-xs font-bold text-muted w-5 text-center">{i + 1}</span>
                          <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${meta.color}`}>
                            {meta.icon} {meta.label}
                          </span>
                          {step.reason && <span className="text-xs text-secondary italic">{step.reason}</span>}
                        </div>
                      )
                    })}
                  </div>
                  <div className="flex gap-2 mt-3">
                    <button type="button"
                      onClick={() => {
                        const newSteps = aiSuggestions.steps.map((s, i) => ({
                          type: s.type, config: s.config || {}, order: i
                        }))
                        setForm(f => ({ ...f, steps: newSteps }))
                        setAiSuggestions(null)
                        toast.success('AI suggestions applied')
                      }}
                      className="text-xs px-3 py-1.5 rounded-lg bg-violet-600 text-white hover:bg-violet-700 transition-colors">
                      Accept All
                    </button>
                    <button type="button"
                      onClick={() => {
                        const merged = [
                          ...form.steps,
                          ...aiSuggestions.steps.map((s, i) => ({
                            type: s.type, config: s.config || {}, order: form.steps.length + i
                          }))
                        ]
                        setForm(f => ({ ...f, steps: merged }))
                        setAiSuggestions(null)
                        toast.success('AI suggestions appended')
                      }}
                      className="text-xs px-3 py-1.5 rounded-lg border border-violet-300 text-violet-700 hover:bg-violet-100 transition-colors">
                      Append to Current
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* ─── Delivery Configuration ─── */}
            <div>
              <label className="text-xs font-semibold text-secondary uppercase tracking-wider">Delivery Configuration</label>
              <div className="mt-2">
                <label>Delivery Mode</label>
                <div className="grid grid-cols-4 gap-2 mt-1">
                  {[
                    { key: 'none', label: 'None', desc: 'No auto-delivery', icon: StopIcon },
                    { key: 'mailbox', label: 'Mailbox', desc: 'Internal account', icon: InboxIcon },
                    { key: 'external', label: 'External', desc: 'External destination', icon: PaperAirplaneIcon },
                    { key: 'both', label: 'Both', desc: 'Mailbox + External', icon: ArrowsUpDownIcon },
                  ].map(mode => (
                    <button key={mode.key} type="button" onClick={() => setForm(f => ({ ...f, deliveryMode: mode.key }))}
                      className={`flex flex-col items-center gap-1 p-3 rounded-lg border text-center transition-all ${
                        form.deliveryMode === mode.key
                          ? 'border-blue-300 bg-blue-50 text-blue-700'
                          : 'border-border text-secondary hover:border-border hover:bg-canvas'
                      }`}>
                      <mode.icon className="w-5 h-5" />
                      <span className="text-xs font-medium">{mode.label}</span>
                      <span className="text-[10px] text-muted">{mode.desc}</span>
                    </button>
                  ))}
                </div>
              </div>
              {(form.deliveryMode === 'mailbox' || form.deliveryMode === 'both') && (
                <div className="mt-3 grid grid-cols-2 gap-4">
                  <div>
                    <label htmlFor="flow-dest-account">Destination Account</label>
                    <select id="flow-dest-account" value={form.destinationAccountId}
                      onChange={e => setForm(f => ({ ...f, destinationAccountId: e.target.value }))}>
                      <option value="">-- Select Account --</option>
                      {filteredAccounts.map(a => (
                        <option key={a.id} value={a.id}>{a.username} ({a.protocol})</option>
                      ))}
                    </select>
                    {form.protocol !== 'ANY' && filteredAccounts.length === 0 && (
                      <p className="text-xs text-amber-500 mt-1">No {form.protocol} accounts found</p>
                    )}
                  </div>
                  <div>
                    <label htmlFor="flow-dest-path">Destination Path</label>
                    <input id="flow-dest-path" value={form.destinationPath}
                      onChange={e => setForm(f => ({ ...f, destinationPath: e.target.value }))}
                      placeholder="/outbox" className="font-mono text-sm" />
                  </div>
                </div>
              )}
              {(form.deliveryMode === 'external' || form.deliveryMode === 'both') && (
                <div className="mt-3">
                  <label htmlFor="flow-ext-dest">External Destination</label>
                  <select id="flow-ext-dest" value={form.externalDestinationId}
                    onChange={e => setForm(f => ({ ...f, externalDestinationId: e.target.value }))}>
                    <option value="">-- Select Destination --</option>
                    {externalDests.map(d => (
                      <option key={d.id} value={d.id}>{d.name} ({d.type} - {d.host})</option>
                    ))}
                  </select>
                  {externalDests.length === 0 && (
                    <p className="text-xs text-amber-500 mt-1">No external destinations configured. Add one in External Destinations first.</p>
                  )}
                </div>
              )}
            </div>

            {/* ─── Actions ─── */}
            <div className="flex items-center gap-3 justify-between pt-4 border-t border-border">
              <div className="flex items-center gap-2">
                <label className="flex items-center gap-2 cursor-pointer mb-0">
                  <input type="checkbox" checked={form.active} className="w-auto"
                    onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />
                  <span className="text-sm text-secondary">Active on save</span>
                </label>
              </div>
              <div className="flex gap-3">
                <button type="button" className="btn-secondary" onClick={closeEditor}>Cancel</button>
                <button type="submit" className="btn-primary" disabled={saveMut.isPending}>
                  {saveMut.isPending
                    ? (editingId ? 'Updating...' : 'Creating...')
                    : (editingId ? 'Update Flow' : 'Create Flow')
                  }
                </button>
              </div>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
