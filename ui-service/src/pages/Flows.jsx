import { useState, useCallback, useMemo, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi, onboardingApi } from '../api/client'
import { getPendingApprovals, approveStep, rejectStep } from '../api/approvals'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import MatchCriteriaBuilder, { MatchSummaryBadges, buildCriteriaFromLegacy } from '../components/MatchCriteriaBuilder'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilSquareIcon, ChevronUpIcon, ChevronDownIcon,
  FunnelIcon, ArrowPathIcon, ClockIcon, CheckCircleIcon, XCircleIcon,
  ArrowsUpDownIcon, SparklesIcon, StopIcon,
  ChevronRightIcon, InboxIcon, PaperAirplaneIcon, HandRaisedIcon,
  BeakerIcon, ExclamationTriangleIcon, QuestionMarkCircleIcon,
} from '@heroicons/react/24/outline'

// ─── Step type definitions with icons, labels, categories, and config fields ───
const STEP_TYPE_CATALOG = {
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

const STEP_CATEGORIES = [
  { name: 'Encryption',   types: ['ENCRYPT_PGP', 'DECRYPT_PGP', 'ENCRYPT_AES', 'DECRYPT_AES'] },
  { name: 'Compression',  types: ['COMPRESS_GZIP', 'DECOMPRESS_GZIP', 'COMPRESS_ZIP', 'DECOMPRESS_ZIP'] },
  { name: 'Transform',    types: ['RENAME'] },
  { name: 'Validation',   types: ['SCREEN'] },
  { name: 'Delivery',     types: ['MAILBOX', 'FILE_DELIVERY', 'ROUTE'] },
  { name: 'Data',         types: ['CONVERT_EDI'] },
  { name: 'Scripting',    types: ['EXECUTE_SCRIPT'] },
]

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
  PENDING:    { bg: 'bg-gray-50',    text: 'text-gray-600',    border: 'border-gray-200',    dot: 'bg-gray-400' },
  PAUSED:     { bg: 'bg-blue-50',    text: 'text-blue-700',    border: 'border-blue-200',    dot: 'bg-blue-500' },
  UNMATCHED:  { bg: 'bg-slate-50',  text: 'text-slate-600',   border: 'border-slate-200',   dot: 'bg-slate-400' },
}

const defaultForm = {
  name: '', description: '', filenamePattern: '', sourcePath: '/inbox',
  destinationPath: '/outbox', priority: 100, active: true, steps: [],
  sourceAccountId: '', protocol: 'ANY',
  deliveryMode: 'none', externalDestinationId: '', destinationAccountId: '',
  matchCriteria: null, direction: null,
}

// ─── Mini pipeline visualization ───
function MiniPipeline({ steps }) {
  if (!steps || steps.length === 0) return <span className="text-xs text-gray-400 italic">No steps</span>
  return (
    <div className="flex items-center gap-1 flex-wrap">
      {steps.map((step, i) => {
        const meta = STEP_TYPE_CATALOG[step.type]
        return (
          <div key={i} className="flex items-center gap-1">
            {i > 0 && <ChevronRightIcon className="w-3 h-3 text-gray-300 flex-shrink-0" />}
            <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${meta?.color || 'text-gray-600 bg-gray-100'}`}>
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
function StepCard({ step, index, total, onRemove, onMoveUp, onMoveDown, onConfigChange }) {
  const [expanded, setExpanded] = useState(false)
  const meta = STEP_TYPE_CATALOG[step.type] || { label: step.type, icon: '?', color: 'text-gray-600 bg-gray-100', configFields: [] }

  return (
    <div className={`border rounded-lg transition-all ${expanded ? 'border-blue-300 shadow-sm' : 'border-gray-200'}`}>
      <div className="flex items-center gap-2 p-3">
        <span className="text-xs font-bold text-gray-400 w-6 text-center flex-shrink-0">{index + 1}</span>
        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold flex-shrink-0 ${meta.color}`}>
          <span>{meta.icon}</span> {meta.label}
        </span>
        <div className="flex-1" />
        {meta.configFields.length > 0 && (
          <button type="button" onClick={() => setExpanded(!expanded)}
            className="text-xs text-gray-400 hover:text-blue-600 transition-colors px-1">
            {expanded ? 'Collapse' : 'Configure'}
          </button>
        )}
        <div className="flex items-center gap-0.5 flex-shrink-0">
          <button type="button" onClick={onMoveUp} disabled={index === 0}
            className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
            <ChevronUpIcon className="w-4 h-4" />
          </button>
          <button type="button" onClick={onMoveDown} disabled={index === total - 1}
            className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-30 disabled:cursor-not-allowed transition-colors">
            <ChevronDownIcon className="w-4 h-4" />
          </button>
          <button type="button" onClick={onRemove}
            className="p-1 text-gray-400 hover:text-red-600 transition-colors">
            <TrashIcon className="w-4 h-4" />
          </button>
        </div>
      </div>
      {expanded && meta.configFields.length > 0 && (
        <div className="px-3 pb-3 pt-1 border-t border-gray-100 space-y-2">
          {meta.configFields.map(field => (
            <div key={field.key}>
              <label className="text-xs font-medium text-gray-600">{field.label}</label>
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
              {field.helper && <p className="text-xs text-gray-400 mt-0.5">{field.helper}</p>}
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
    <div className="absolute z-30 mt-1 w-80 bg-white rounded-xl shadow-xl border border-gray-200 p-3 space-y-3 max-h-96 overflow-y-auto">
      {STEP_CATEGORIES.map(cat => (
        <div key={cat.name}>
          <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">{cat.name}</h4>
          <div className="grid grid-cols-1 gap-1">
            {cat.types.map(type => {
              const meta = STEP_TYPE_CATALOG[type]
              return (
                <button key={type} type="button" onClick={() => { onAdd(type); onClose() }}
                  className="flex items-center gap-2 px-3 py-2 rounded-lg text-left hover:bg-gray-50 transition-colors">
                  <span className={`inline-flex items-center justify-center w-7 h-7 rounded-lg text-sm ${meta.color}`}>{meta.icon}</span>
                  <span className="text-sm font-medium text-gray-700">{meta.label}</span>
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

function ExecutionRow({ ex, selected, onToggle, onSkipStep, skipPending, onScheduleRetry, onCancelSchedule, schedulePending }) {
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
        className={`table-row cursor-pointer hover:bg-gray-50 transition-colors ${selected ? 'bg-red-50' : ''}`}
        onClick={() => setExpanded(!expanded)}
      >
        {/* Checkbox — stop propagation so row expand doesn't fire */}
        <td className="table-cell w-8" onClick={e => e.stopPropagation()}>
          {selectable ? (
            <input
              type="checkbox"
              checked={!!selected}
              onChange={() => onToggle(ex.trackId)}
              className="rounded border-gray-300 text-red-600 focus:ring-red-500 cursor-pointer"
            />
          ) : (
            <span className="block w-4" />
          )}
        </td>
        <td className="table-cell">
          <div className="flex items-center gap-1">
            <ChevronRightIcon className={`w-3.5 h-3.5 text-gray-400 transition-transform ${expanded ? 'rotate-90' : ''}`} />
            <span className="font-mono text-xs font-bold text-blue-600">{ex.trackId}</span>
          </div>
        </td>
        <td className="table-cell text-sm text-gray-700">{ex.flow?.name || '—'}</td>
        <td className="table-cell text-xs text-gray-500 truncate max-w-40 font-mono">{ex.originalFilename}</td>
        <td className="table-cell">
          <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium border ${style.bg} ${style.text} ${style.border}`}>
            <span className={`w-1.5 h-1.5 rounded-full ${style.dot}`} />
            {ex.status}
          </span>
        </td>
        <td className="table-cell">
          <div className="flex items-center gap-1.5">
            <div className="flex-1 h-1.5 bg-gray-100 rounded-full overflow-hidden max-w-16">
              <div className="h-full bg-blue-500 rounded-full transition-all"
                style={{ width: `${totalSteps !== '?' ? (ex.currentStep / totalSteps) * 100 : 0}%` }} />
            </div>
            <span className="text-xs text-gray-500">{ex.currentStep}/{totalSteps}</span>
          </div>
        </td>
        <td className="table-cell text-xs text-gray-500">{duration}</td>
        <td className="table-cell text-xs text-gray-500">
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
                className="p-0.5 text-gray-400 hover:text-red-500 transition-colors"
                title="Cancel scheduled retry"
              >
                <XCircleIcon className="w-3.5 h-3.5" />
              </button>
            </div>
          ) : selectable && !showScheduler ? (
            <button
              onClick={() => { setShowScheduler(true); setScheduleInput('') }}
              className="text-[10px] text-gray-400 hover:text-blue-600 px-1.5 py-0.5 rounded hover:bg-blue-50 transition-colors whitespace-nowrap"
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
                className="text-[10px] border border-gray-200 rounded px-1.5 py-0.5 focus:outline-none focus:ring-1 focus:ring-blue-400"
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
                className="text-[10px] text-gray-400 hover:text-gray-600"
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
            <div className="ml-6 bg-gray-50 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-gray-500 mb-2">Step Results</h4>
              <div className="space-y-1.5">
                {ex.stepResults.map((sr, i) => {
                  const stepMeta  = STEP_TYPE_CATALOG[sr.stepType]
                  const isOk      = sr.status === 'OK'
                  const isFailed  = sr.status === 'FAILED'
                  const isSkipped = sr.status === 'SKIPPED'
                  const isLast    = i === (typeof totalSteps === 'number' ? totalSteps - 1 : Infinity)
                  const showSkip  = canSkip && isFailed && !isLast
                  return (
                    <div key={i} className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-xs ${isFailed ? 'bg-red-50' : isOk ? 'bg-white' : isSkipped ? 'bg-amber-50' : 'bg-gray-100'}`}>
                      {isOk      && <CheckCircleIcon className="w-4 h-4 text-emerald-500 flex-shrink-0" />}
                      {isFailed  && <XCircleIcon     className="w-4 h-4 text-red-500 flex-shrink-0" />}
                      {isSkipped && <ArrowPathIcon   className="w-4 h-4 text-amber-500 flex-shrink-0" />}
                      {!isOk && !isFailed && !isSkipped && <ClockIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />}
                      <span className="font-medium text-gray-700">{stepMeta?.icon} {stepMeta?.label || sr.stepType}</span>
                      <span className="text-gray-400">({sr.durationMs}ms)</span>
                      {sr.error && <span className="text-red-600 truncate max-w-52">{sr.error}</span>}
                      <div className="flex-1" />
                      <span className={`font-semibold ${isOk ? 'text-emerald-600' : isFailed ? 'text-red-600' : isSkipped ? 'text-amber-600' : 'text-gray-500'}`}>
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
            <span className="text-xs text-gray-500 truncate max-w-48">{ap.originalFilename}</span>
          </div>
          <div className="mt-1 flex items-center gap-3 text-xs text-gray-500">
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
            className="flex-1 text-xs px-3 py-1.5 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-400"
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
  const [showEditor, setShowEditor] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [form, setForm] = useState({ ...defaultForm })
  const [showAddStep, setShowAddStep] = useState(false)
  const [filter, setFilter] = useState('all') // 'all' | 'active' | 'inactive'
  const [selectedIds, setSelectedIds] = useState(new Set()) // trackIds selected for bulk restart
  const [dryRunResult, setDryRunResult] = useState(null)   // non-null = show dry run modal

  // ─── Queries ───
  const { data: flows = [], isLoading } = useQuery({
    queryKey: ['flows'],
    queryFn: () => configApi.get('/api/flows').then(r => r.data).catch(() => [])
  })

  const { data: executions = [] } = useQuery({
    queryKey: ['flow-executions'],
    queryFn: () => configApi.get('/api/flows/executions?size=20').then(r => r.data?.content || r.data || []).catch(() => []),
    refetchInterval: 10000
  })

  const { data: pendingApprovals = [] } = useQuery({
    queryKey: ['pending-approvals'],
    queryFn: () => getPendingApprovals().catch(() => []),
    refetchInterval: 15000
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

  const { data: scheduledRetries = [] } = useQuery({
    queryKey: ['scheduled-retries'],
    queryFn: () => onboardingApi.get('/api/flow-executions/scheduled-retries').then(r => r.data).catch(() => []),
    refetchInterval: 60000  // was 30s — scheduled retries change infrequently
  })

  const { data: accounts = [] } = useQuery({
    queryKey: ['accounts-for-flows'],
    queryFn: () => onboardingApi.get('/api/accounts').then(r => r.data).catch(() => []),
    staleTime: 60000
  })

  const { data: externalDests = [] } = useQuery({
    queryKey: ['ext-dests-for-flows'],
    queryFn: () => configApi.get('/api/external-destinations').then(r => r.data).catch(() => []),
    staleTime: 60000
  })

  const { data: partners = [] } = useQuery({
    queryKey: ['partners-for-flows'],
    queryFn: () => onboardingApi.get('/api/partners').then(r => r.data?.content || r.data || []).catch(() => []),
    staleTime: 300000
  })

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
    onError: err => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to save flow')
  })

  const toggleMut = useMutation({
    mutationFn: (id) => configApi.patch(`/api/flows/${id}/toggle`).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['flows']); toast.success('Flow toggled') }
  })

  const deleteMut = useMutation({
    mutationFn: (id) => configApi.delete(`/api/flows/${id}`),
    onSuccess: () => { qc.invalidateQueries(['flows']); toast.success('Flow deactivated') }
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
    const deliveryMode = flow.externalDestination && flow.destinationAccount ? 'both'
      : flow.externalDestination ? 'external'
      : flow.destinationAccount ? 'mailbox' : 'none'
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
      sourceAccountId: flow.sourceAccount?.id || '',
      protocol: 'ANY',
      deliveryMode,
      externalDestinationId: flow.externalDestination?.id || '',
      destinationAccountId: flow.destinationAccount?.id || '',
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
  }, [])

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

  // ─── Filtered flows ───
  const filteredFlows = useMemo(() => {
    if (filter === 'active') return flows.filter(f => f.active)
    if (filter === 'inactive') return flows.filter(f => !f.active)
    return flows
  }, [flows, filter])

  const activeCount = flows.filter(f => f.active).length
  const inactiveCount = flows.filter(f => !f.active).length

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">File Processing Flows</h1>
          <p className="text-gray-500 text-sm">
            {flows.length} flow{flows.length !== 1 ? 's' : ''} configured
            {activeCount > 0 && <span className="text-emerald-600 ml-1">({activeCount} active)</span>}
          </p>
        </div>
        <button className="btn-primary" onClick={openCreate}>
          <PlusIcon className="w-4 h-4" /> New Flow
        </button>
      </div>

      {/* ─── Filter bar ─── */}
      <div className="flex items-center gap-2">
        <FunnelIcon className="w-4 h-4 text-gray-400" />
        {[
          { key: 'all', label: `All (${flows.length})` },
          { key: 'active', label: `Active (${activeCount})` },
          { key: 'inactive', label: `Inactive (${inactiveCount})` },
        ].map(f => (
          <button key={f.key} onClick={() => setFilter(f.key)}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              filter === f.key ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
            }`}>
            {f.label}
          </button>
        ))}
      </div>

      {/* ─── Flow List ─── */}
      {filteredFlows.length === 0 ? (
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
            <div key={flow.id} className="card hover:shadow-md transition-shadow">
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-semibold text-gray-900">{flow.name}</h3>
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
                    <p className="text-sm text-gray-500 mt-1">{flow.description}</p>
                  )}
                  <div className="mt-1">
                    {flow.matchCriteria ? (
                      <MatchSummaryBadges criteria={flow.matchCriteria} />
                    ) : (
                      <div className="flex items-center gap-4 text-xs text-gray-400">
                        {flow.sourceAccount && (
                          <span>Source: <span className="font-medium text-gray-500">{flow.sourceAccount.username}</span></span>
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
                  <button
                    onClick={() => dryRunMut.mutate({ flowId: flow.id, filename: 'test-file.xml' })}
                    disabled={dryRunMut.isPending}
                    className="flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium rounded-lg transition-colors text-violet-600 hover:bg-violet-50 hover:text-violet-700 disabled:opacity-50"
                    title="Simulate this flow without writing to storage or delivering to partners"
                  >
                    <BeakerIcon className="w-3.5 h-3.5" />
                    Dry Run
                  </button>
                  <button onClick={() => openEdit(flow)}
                    className="p-2 text-gray-400 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                    title="Edit flow">
                    <PencilSquareIcon className="w-4 h-4" />
                  </button>
                  <button onClick={() => toggleMut.mutate(flow.id)}
                    className={`px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                      flow.active
                        ? 'text-red-600 hover:bg-red-50'
                        : 'text-emerald-600 hover:bg-emerald-50'
                    }`}>
                    {flow.active ? 'Disable' : 'Enable'}
                  </button>
                  <button onClick={() => {
                    if (window.confirm(`Delete flow "${flow.name}"? This will deactivate it.`))
                      deleteMut.mutate(flow.id)
                  }}
                    className="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                    title="Delete flow">
                    <TrashIcon className="w-4 h-4" />
                  </button>
                </div>
              </div>
              {/* Pipeline visualization */}
              <div className="mt-3 pt-3 border-t border-gray-100">
                <MiniPipeline steps={flow.steps || []} />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ─── Pending Approvals ─── */}
      {pendingApprovals.length > 0 && (
        <div className="card border-l-4 border-purple-500">
          <div className="flex items-center gap-2 mb-4">
            <HandRaisedIcon className="w-5 h-5 text-purple-500" />
            <h3 className="font-semibold text-gray-900">Pending Approvals</h3>
            <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-purple-100 text-purple-700">
              {pendingApprovals.length}
            </span>
            <span className="text-xs text-gray-400 ml-auto">(auto-refresh 15s)</span>
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
      {scheduledRetries.length > 0 && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-4">
          <div className="flex items-center gap-2 mb-3">
            <ClockIcon className="w-4 h-4 text-blue-500" />
            <h3 className="font-semibold text-blue-800 text-sm">
              Scheduled Retries ({scheduledRetries.length})
            </h3>
          </div>
          <div className="space-y-2">
            {scheduledRetries.map(r => (
              <div key={r.trackId} className="flex items-center gap-3 text-xs bg-white rounded-lg px-3 py-2 border border-blue-100">
                <span className="font-mono font-bold text-blue-600">{r.trackId}</span>
                <span className="text-gray-600 truncate max-w-40">{r.originalFilename}</span>
                <span className="text-gray-400">{r.flowName || '—'}</span>
                <div className="flex-1" />
                <span className="text-gray-500">by {r.scheduledBy}</span>
                <span className="font-semibold text-blue-700">
                  {new Date(r.scheduledAt).toLocaleString()}
                </span>
                <button
                  onClick={() => cancelScheduleMut.mutate(r.trackId)}
                  disabled={cancelScheduleMut.isPending}
                  className="text-gray-400 hover:text-red-500 transition-colors"
                  title="Cancel scheduled retry"
                >
                  <XCircleIcon className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ─── Execution History ─── */}
      <div className="card">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <ClockIcon className="w-5 h-5 text-gray-400" />
            <h3 className="font-semibold text-gray-900">Execution History</h3>
            <span className="text-xs text-gray-400">(auto-refresh 10s)</span>
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
                className="text-xs text-gray-500 hover:text-red-600 px-2 py-1 rounded hover:bg-red-50 transition-colors"
              >
                Select all failed
              </button>
            )}
            <button onClick={() => qc.invalidateQueries(['flow-executions'])}
              className="p-1.5 text-gray-400 hover:text-blue-600 rounded-lg hover:bg-blue-50 transition-colors"
              title="Refresh now">
              <ArrowPathIcon className="w-4 h-4" />
            </button>
          </div>
        </div>
        {executions.length === 0 ? (
          <div className="text-center py-8 text-gray-400 text-sm">
            No flow executions yet. Executions will appear here when files are processed.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-gray-100">
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
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ═══ Dry Run Results Modal ═══ */}
      {dryRunResult && (
        <DryRunModal result={dryRunResult} onClose={() => setDryRunResult(null)} />
      )}

      {/* ═══ Flow Builder Modal ═══ */}
      {showEditor && (
        <Modal title={editingId ? 'Edit Processing Flow' : 'Create Processing Flow'} size="xl" onClose={closeEditor}>
          <form onSubmit={e => { e.preventDefault(); saveMut.mutate(form) }} className="space-y-6">

            {/* ─── Templates (create only) ─── */}
            {!editingId && (
              <div>
                <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Quick Start Templates</label>
                <div className="mt-2 grid grid-cols-2 gap-2">
                  {FLOW_TEMPLATES.map(tpl => (
                    <button key={tpl.name} type="button" onClick={() => applyTemplate(tpl)}
                      className="flex items-start gap-3 p-3 rounded-lg border border-gray-200 hover:border-blue-300 hover:bg-blue-50/50 text-left transition-all group">
                      <SparklesIcon className="w-5 h-5 text-blue-400 group-hover:text-blue-600 flex-shrink-0 mt-0.5" />
                      <div>
                        <div className="text-sm font-medium text-gray-700 group-hover:text-blue-700">{tpl.name}</div>
                        <div className="text-xs text-gray-400 mt-0.5">{tpl.desc}</div>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* ─── Basic Info ─── */}
            <div>
              <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Flow Details</label>
              <div className="mt-2 grid grid-cols-2 gap-4">
                <div>
                  <label>Flow Name</label>
                  <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    required placeholder="e.g. partner-inbound-pgp" />
                </div>
                <div>
                  <label>Priority (lower = matched first)</label>
                  <div className="flex items-center gap-3">
                    <input type="range" min="1" max="1000" value={form.priority}
                      onChange={e => setForm(f => ({ ...f, priority: parseInt(e.target.value) }))}
                      className="flex-1 h-2 bg-gray-200 rounded-lg appearance-auto cursor-pointer accent-blue-600" />
                    <input type="number" min="1" max="1000" value={form.priority}
                      onChange={e => setForm(f => ({ ...f, priority: parseInt(e.target.value) || 100 }))}
                      className="w-20 text-center text-sm" />
                  </div>
                </div>
              </div>
              <div className="mt-3">
                <label>Description</label>
                <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                  placeholder="What this flow does..." />
              </div>
            </div>

            {/* ─── Match Criteria (replaces Source Configuration) ─── */}
            <MatchCriteriaBuilder
              value={form.matchCriteria}
              onChange={(criteria) => setForm(f => ({ ...f, matchCriteria: criteria }))}
              accounts={accounts}
              partners={partners}
            />

            {/* Direction filter */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-xs text-gray-500">Direction</label>
                <select value={form.direction || ''} onChange={e => setForm(f => ({ ...f, direction: e.target.value || null }))}>
                  <option value="">Any Direction</option>
                  <option value="INBOUND">Inbound</option>
                  <option value="OUTBOUND">Outbound</option>
                </select>
              </div>
              <div>
                <label className="text-xs text-gray-500">Legacy: Source Path</label>
                <input value={form.sourcePath}
                  onChange={e => setForm(f => ({ ...f, sourcePath: e.target.value }))}
                  placeholder="/inbox" className="font-mono text-sm" />
              </div>
            </div>

            {/* ─── Processing Pipeline ─── */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
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
                  />
                ))}
                {form.steps.length === 0 && (
                  <div className="text-center py-6 border-2 border-dashed border-gray-200 rounded-lg">
                    <ArrowsUpDownIcon className="w-8 h-8 text-gray-300 mx-auto mb-2" />
                    <p className="text-sm text-gray-400">No steps added yet</p>
                    <p className="text-xs text-gray-300 mt-1">Use a template above or add steps manually below</p>
                  </div>
                )}
              </div>
              <div className="mt-3 relative">
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
              </div>
            </div>

            {/* ─── Delivery Configuration ─── */}
            <div>
              <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Delivery Configuration</label>
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
                          : 'border-gray-200 text-gray-500 hover:border-gray-300 hover:bg-gray-50'
                      }`}>
                      <mode.icon className="w-5 h-5" />
                      <span className="text-xs font-medium">{mode.label}</span>
                      <span className="text-[10px] text-gray-400">{mode.desc}</span>
                    </button>
                  ))}
                </div>
              </div>
              {(form.deliveryMode === 'mailbox' || form.deliveryMode === 'both') && (
                <div className="mt-3 grid grid-cols-2 gap-4">
                  <div>
                    <label>Destination Account</label>
                    <select value={form.destinationAccountId}
                      onChange={e => setForm(f => ({ ...f, destinationAccountId: e.target.value }))}>
                      <option value="">-- Select Account --</option>
                      {accounts.map(a => (
                        <option key={a.id} value={a.id}>{a.username} ({a.protocol})</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label>Destination Path</label>
                    <input value={form.destinationPath}
                      onChange={e => setForm(f => ({ ...f, destinationPath: e.target.value }))}
                      placeholder="/outbox" className="font-mono text-sm" />
                  </div>
                </div>
              )}
              {(form.deliveryMode === 'external' || form.deliveryMode === 'both') && (
                <div className="mt-3">
                  <label>External Destination</label>
                  <select value={form.externalDestinationId}
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
            <div className="flex items-center gap-3 justify-between pt-4 border-t border-gray-200">
              <div className="flex items-center gap-2">
                <label className="flex items-center gap-2 cursor-pointer mb-0">
                  <input type="checkbox" checked={form.active} className="w-auto"
                    onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />
                  <span className="text-sm text-gray-600">Active on save</span>
                </label>
              </div>
              <div className="flex gap-3">
                <button type="button" className="btn-secondary" onClick={closeEditor}>Cancel</button>
                <button type="submit" className="btn-primary"
                  disabled={saveMut.isPending || !form.name || form.steps.length === 0}>
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
