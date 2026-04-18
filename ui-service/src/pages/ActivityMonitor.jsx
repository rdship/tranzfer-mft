import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react'
import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { useNavigate, Link, useSearchParams } from 'react-router-dom'
import { onboardingApi, configApi } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { getFabricQueues, getFabricStuck, getFabricLatency, getFabricInstances } from '../api/fabric'
import CopyButton from '../components/CopyButton'
import useStickyFilters from '../hooks/useStickyFilters'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import Skeleton, { useDelayedFlag } from '../components/Skeleton'
import ConfigLink from '../components/ConfigLink'
import ConfirmDialog from '../components/ConfirmDialog'
import { LazyExecutionDetailDrawer as ExecutionDetailDrawer, LazyFileDownloadButton as FileDownloadButton, LazyConfigInlineEditor as ConfigInlineEditor } from '../components/LazyShared'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  MagnifyingGlassIcon, ArrowDownTrayIcon, ChevronLeftIcon, ChevronRightIcon,
  Cog6ToothIcon, XMarkIcon, ArrowPathIcon, ChevronUpIcon, ChevronDownIcon,
  FunnelIcon, TableCellsIcon, CheckCircleIcon, XCircleIcon, ClockIcon,
  DocumentTextIcon, ShieldCheckIcon, TruckIcon, ArrowRightIcon,
  BookmarkIcon, TrashIcon, LightBulbIcon, PlusIcon, CheckIcon,
  StopIcon, PlayIcon, PauseIcon, SparklesIcon,
} from '@heroicons/react/24/outline'
import AICopilotDrawer from '../components/AICopilotDrawer'

// Statuses on which the Restart button is actionable. Mirrors
// FlowRestartService.loadRestartable (server-side): PROCESSING and COMPLETED
// are rejected with 409, everything else runs restartFromBeginning.
// R125: tester/CTO flagged that the button was invisible for every state
// except FAILED/CANCELLED — an operator hitting an UNMATCHED or stuck
// PENDING row had no visible action even though the backend supported it.
const RESTARTABLE_STATUSES = new Set(['FAILED', 'CANCELLED', 'UNMATCHED', 'PAUSED', 'PENDING'])

// ── Saved Views (localStorage) ─────────────────────────────────────────
// User-defined Activity Monitor filter presets. Persisted per-browser under
// `tranzfer.activityViews`. Capped at 20 entries (oldest dropped on overflow).
const SAVED_VIEWS_KEY = 'tranzfer.activityViews'
const SAVED_VIEWS_MAX = 20

function loadSavedViews() {
  try {
    const raw = localStorage.getItem(SAVED_VIEWS_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch { return [] }
}

function persistSavedViews(views) {
  try { localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(views)) } catch { /* quota */ }
}

// Built-in views — always present, cannot be deleted. Each describes the
// target filter state only; any field not listed resets to its default.
const BUILT_IN_VIEWS = [
  { id: 'builtin-all',       name: 'All transfers',      description: 'Clear all filters', filters: {} },
  { id: 'builtin-stuck',     name: 'Stuck only',         description: 'Files past their lease', filters: { stuckOnly: true } },
  { id: 'builtin-failed',     name: 'Failed last 24h',      description: 'Status = FAILED',               filters: { statusFilter: 'FAILED' } },
  { id: 'builtin-inflight',   name: 'In-flight',            description: 'Status = DOWNLOADED',           filters: { statusFilter: 'DOWNLOADED' } },
  { id: 'builtin-completed',  name: 'Completed',            description: 'Flow-terminal success',         filters: { statusFilter: 'COMPLETED' } },
  { id: 'builtin-delivered',  name: 'Delivered (archived)', description: 'Status = MOVED_TO_SENT',        filters: { statusFilter: 'MOVED_TO_SENT' } },
]

// Short human label of what a saved view actually filters on.
function describeViewFilters(v) {
  const f = v.filters || {}
  const parts = []
  if (f.statusFilter && f.statusFilter !== 'ALL') parts.push(`status=${f.statusFilter}`)
  if (f.stuckOnly) parts.push('stuck')
  if (f.protocolFilter && f.protocolFilter !== 'ALL') parts.push(f.protocolFilter)
  if (f.filenameFilter) parts.push(`file~${f.filenameFilter}`)
  if (f.trackIdFilter) parts.push(`id~${f.trackIdFilter}`)
  if (f.sourceUserFilter) parts.push(`user~${f.sourceUserFilter}`)
  if (f.stepTypeFilter) parts.push(`step=${f.stepTypeFilter}`)
  return parts.length ? parts.join(' · ') : 'no filters'
}

// ── Column Definitions ──────────────────────────────────────────────────
// IMPORTANT: getAllColumns() is a function, not a module-level const.
// Module-level const with JSX referencing imported components (CopyButton)
// causes TDZ "Cannot access before initialization" when the page chunk
// loads before the shared-app chunk. Wrapping in a function defers
// evaluation to render time, when all imports are guaranteed initialized.
function getAllColumns() { return [
  // R127: Track ID no longer styled as a link. It's an identifier (not
  // navigable), so the prior purple/blue underline-styled treatment was
  // misleading. Uses .id-mono — monospace, tabular nums, primary text —
  // and keeps the copy button on the right so users can still grab it.
  { key: 'trackId', label: 'Track ID', defaultVisible: true, width: 'w-36', render: (v) => v
      ? <span className="inline-flex items-center gap-1 id-mono">{v}<CopyButton value={v} label="trackId" size="xs" /></span>
      : <span>--</span> },
  { key: 'filename', label: 'Filename', defaultVisible: true, width: 'min-w-[180px] max-w-[280px]', render: (v) => <span className="truncate block" title={v}>{v || '--'}</span> },
  { key: 'status', label: 'Status', defaultVisible: true, width: 'w-36', render: (v) => statusBadge(v) },
  { key: 'sourceUsername', label: 'Source User', defaultVisible: true, width: 'w-32', render: (v) => v || '--' },
  { key: 'sourceProtocol', label: 'Protocol', defaultVisible: true, width: 'w-28', render: (v) => v ? <span className="badge badge-gray">{v}</span> : '--' },
  { key: 'sourcePartnerName', label: 'Source Partner', defaultVisible: true, width: 'w-36', render: (v) => v || '--' },
  { key: 'destPartnerName', label: 'Dest Partner', defaultVisible: true, width: 'w-36', render: (v) => v || '--' },
  { key: 'fileSizeBytes', label: 'File Size', defaultVisible: true, width: 'w-28', render: (v) => v != null ? formatBytes(v) : '--' },
  { key: 'uploadedAt', label: 'Uploaded', defaultVisible: true, width: 'w-40', render: (v) => formatTimestamp(v) },
  { key: 'completedAt', label: 'Completed', defaultVisible: true, width: 'w-40', render: (v) => formatTimestamp(v) },
  { key: 'destUsername', label: 'Dest User', defaultVisible: false, width: 'w-32', render: (v) => v || '--' },
  { key: 'destProtocol', label: 'Dest Protocol', defaultVisible: false, width: 'w-28', render: (v) => v ? <span className="badge badge-gray">{v}</span> : '--' },
  { key: 'sourcePath', label: 'Source Path', defaultVisible: false, width: 'min-w-[200px]', render: (v) => <span className="font-mono text-xs truncate block" title={v}>{v || '--'}</span> },
  { key: 'destPath', label: 'Dest Path', defaultVisible: false, width: 'min-w-[200px]', render: (v) => <span className="font-mono text-xs truncate block" title={v}>{v || '--'}</span> },
  { key: 'externalDestName', label: 'External Dest', defaultVisible: false, width: 'w-36', render: (v) => v || '--' },
  { key: 'sourceChecksum', label: 'Source Checksum', defaultVisible: false, width: 'w-44', render: (v) => <span className="font-mono text-xs truncate block" title={v}>{v ? v.substring(0, 16) + '...' : '--'}</span> },
  { key: 'destinationChecksum', label: 'Dest Checksum', defaultVisible: false, width: 'w-44', render: (v) => <span className="font-mono text-xs truncate block" title={v}>{v ? v.substring(0, 16) + '...' : '--'}</span> },
  { key: 'integrityStatus', label: 'Integrity', defaultVisible: false, width: 'w-28', render: (v) => v === 'VERIFIED' ? <span className="badge badge-green">{v}</span> : v === 'MISMATCH' ? <span className="badge badge-red">{v}</span> : <span className="text-muted">{v || '--'}</span> },
  { key: 'encryptionOption', label: 'Encryption', defaultVisible: false, width: 'w-28', render: (v) => v || '--' },
  { key: 'flowName', label: 'Flow Name', defaultVisible: false, width: 'w-36', render: (v) => v || '--' },
  { key: 'flowStatus', label: 'Flow Status', defaultVisible: false, width: 'w-28', render: (v) => v || '--' },
  { key: 'routedAt', label: 'Routed At', defaultVisible: false, width: 'w-40', render: (v) => formatTimestamp(v) },
  { key: 'downloadedAt', label: 'Downloaded At', defaultVisible: false, width: 'w-40', render: (v) => formatTimestamp(v) },
  { key: 'retryCount', label: 'Retries', defaultVisible: false, width: 'w-20', render: (v) => v != null ? v : '--' },
  { key: 'errorMessage', label: 'Error', defaultVisible: false, width: 'min-w-[200px] max-w-[300px]', render: (v) => v ? <span className="text-red-600 truncate block text-xs" title={v}>{v}</span> : '--' },
  { key: 'errorCategory', label: 'Error Type', defaultVisible: false, width: 'w-24',
    render: (v) => {
      if (!v) return '--'
      const colors = { NETWORK: 'bg-orange-100 text-orange-700', AUTH: 'bg-red-100 text-red-700',
                       STORAGE: 'bg-blue-100 text-blue-700', BUSINESS: 'bg-purple-100 text-purple-700',
                       SYSTEM: 'bg-gray-100 text-gray-700' }
      return <span className={`${colors[v] || 'bg-gray-100'} px-1.5 py-0.5 rounded text-[10px] font-medium`}>{v}</span>
    }
  },
  { key: 'healthScore', label: 'Health', defaultVisible: true, width: 'w-16',
    render: (v) => {
      const color = v >= 80 ? 'text-green-600' : v >= 50 ? 'text-yellow-600' : 'text-red-600'
      const bg = v >= 80 ? 'bg-green-50' : v >= 50 ? 'bg-yellow-50' : 'bg-red-50'
      return <span className={`${color} ${bg} px-1.5 py-0.5 rounded text-xs font-bold`}>{v ?? '--'}</span>
    }
  },
  { key: 'durationMs', label: 'Duration', defaultVisible: true, width: 'w-20',
    render: (v) => {
      if (v == null) return '--'
      if (v < 1000) return `${v}ms`
      if (v < 60000) return `${(v/1000).toFixed(1)}s`
      return `${(v/60000).toFixed(1)}m`
    }
  },
  {
    key: 'currentStepType',
    label: 'Current Step',
    defaultVisible: true,
    width: 'min-w-[180px]',
    render: (_v, row) => {
      if (!row?.currentStepType) return <span className="text-muted">--</span>
      return (
        <div>
          <div className="text-xs text-primary font-mono">
            Step {row.currentStep}: {row.currentStepType}
          </div>
          {row.processingInstance && (
            <div className="text-xs text-muted">on {row.processingInstance}</div>
          )}
          {row.isStuck && (
            <div className="text-xs text-red-600 font-semibold">{'\u26A0 STUCK'}</div>
          )}
          {!row.isStuck && row.leaseRemainingMs != null && (
            <div className="text-xs text-secondary">
              Lease: {Math.max(0, Math.floor(row.leaseRemainingMs / 1000))}s
            </div>
          )}
        </div>
      )
    },
  },
]}

function getDefaultVisibleKeys() { return getAllColumns().filter(c => c.defaultVisible).map(c => c.key) }
const STATUS_OPTIONS = ['ALL', 'PENDING', 'IN_OUTBOX', 'DOWNLOADED', 'COMPLETED', 'MOVED_TO_SENT', 'FAILED']
const PROTOCOL_OPTIONS = ['ALL', 'SFTP', 'FTP', 'FTP_WEB', 'HTTPS', 'AS2', 'AS4']
const PAGE_SIZES = [10, 25, 50, 100]

// ── Helpers ─────────────────────────────────────────────────────────────
function formatBytes(bytes) {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return (bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + ' ' + units[i]
}

function statusBadge(status) {
  const map = {
    PENDING: 'badge-yellow',
    IN_OUTBOX: 'badge-blue',
    DOWNLOADED: 'badge-purple',
    COMPLETED: 'badge-green',
    MOVED_TO_SENT: 'badge-green',
    FAILED: 'badge-red',
  }
  if (!status) return <span className="text-muted">--</span>
  return <span className={`badge ${map[status] || 'badge-gray'}`}>{status.replace(/_/g, ' ')}</span>
}

function formatTimestamp(ts) {
  if (!ts) return <span className="text-muted">--</span>
  try {
    return <span className="text-xs text-secondary font-mono">{format(new Date(ts), 'MMM dd, yyyy HH:mm:ss')}</span>
  } catch {
    return <span className="text-muted">--</span>
  }
}

// ── Config-reference column keys mapped to ConfigLink props ────────────
const CONFIG_LINK_COLUMNS = {
  sourceUsername:   { type: 'account',     navigateTo: '/accounts' },
  destUsername:     { type: 'account',     navigateTo: '/accounts' },
  sourcePartnerName:{ type: 'partner',    navigateTo: '/partners' },
  destPartnerName:  { type: 'partner',    navigateTo: '/partners' },
  flowName:         { type: 'flow',       navigateTo: '/flows' },
  externalDestName: { type: 'destination',navigateTo: '/external-destinations' },
}

function renderConfigCell(col, row, onEdit) {
  const linkMeta = CONFIG_LINK_COLUMNS[col.key]
  if (linkMeta && row[col.key]) {
    return (
      <ConfigLink
        type={linkMeta.type}
        name={row[col.key]}
        onEdit={onEdit}
        navigateTo={linkMeta.navigateTo}
      />
    )
  }
  return col.render(row[col.key], row)
}

// ── Column Preferences Hook ─────────────────────────────────────────────
function useColumnPreferences() {
  const STORAGE_KEY = 'activity-monitor-columns'
  const [visibleKeys, setVisibleKeys] = useState(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        const parsed = JSON.parse(stored)
        if (Array.isArray(parsed) && parsed.length > 0) return parsed
      }
    } catch { /* ignore */ }
    return getDefaultVisibleKeys()
  })

  const setAndPersist = useCallback((keys) => {
    setVisibleKeys(keys)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(keys))
  }, [])

  const toggle = useCallback((key) => {
    setAndPersist(
      visibleKeys.includes(key) ? visibleKeys.filter(k => k !== key) : [...visibleKeys, key]
    )
  }, [visibleKeys, setAndPersist])

  const resetToDefaults = useCallback(() => {
    setAndPersist(getDefaultVisibleKeys())
  }, [setAndPersist])

  const visibleColumns = useMemo(() =>
    getAllColumns().filter(c => visibleKeys.includes(c.key)),
    [visibleKeys]
  )

  return { visibleKeys, visibleColumns, toggle, resetToDefaults }
}

// ── Debounce Hook ───────────────────────────────────────────────────────
function useDebounce(value, delay) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}

// ── Transfer Detail Panel (inline expansion) ───────────────────────────
function TransferDetailPanel({ row, flowExec, events, navigate, onEditConfig }) {
  const stepStatusIcon = (status) => {
    if (!status) return <ClockIcon className="w-4 h-4 text-muted" />
    if (status === 'FAILED') return <XCircleIcon className="w-4 h-4 text-red-500" />
    if (status.startsWith('OK')) return <CheckCircleIcon className="w-4 h-4 text-green-500" />
    return <ClockIcon className="w-4 h-4 text-yellow-500" />
  }

  return (
    <div className="bg-gradient-to-b from-blue-50/80 to-white px-6 py-5 space-y-5 animate-slideDown">
      {/* Top row: Transfer Summary + File Details */}
      <div className="grid grid-cols-3 gap-5">
        {/* Transfer Summary */}
        <div className="space-y-3">
          <h4 className="text-xs font-semibold text-secondary uppercase tracking-wider flex items-center gap-1.5">
            <DocumentTextIcon className="w-3.5 h-3.5" /> Transfer Summary
          </h4>
          <div className="bg-surface rounded-lg border border-border p-3 space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-secondary">Track ID</span>
              <span className="font-mono text-xs font-bold text-blue-600">{row.trackId || '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Filename</span>
              <span className="font-medium text-primary truncate max-w-[180px]" title={row.filename}>{row.filename || '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Size</span>
              <span className="text-primary">{row.fileSizeBytes != null ? formatBytes(row.fileSizeBytes) : '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Protocol</span>
              <span className="text-primary">{row.sourceProtocol || '--'}</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-secondary">Source</span>
              <span className="text-primary flex items-center gap-1">
                <ConfigLink type="account" name={row.sourceUsername} onEdit={onEditConfig} navigateTo="/accounts" />
                {row.sourcePartnerName && <>(<ConfigLink type="partner" name={row.sourcePartnerName} onEdit={onEditConfig} navigateTo="/partners" />)</>}
              </span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-secondary">Destination</span>
              <span className="text-primary">
                {row.destUsername ? (
                  <ConfigLink type="account" name={row.destUsername} onEdit={onEditConfig} navigateTo="/accounts" />
                ) : row.destPartnerName ? (
                  <ConfigLink type="partner" name={row.destPartnerName} onEdit={onEditConfig} navigateTo="/partners" />
                ) : row.externalDestName ? (
                  <ConfigLink type="destination" name={row.externalDestName} onEdit={onEditConfig} navigateTo="/external-destinations" />
                ) : '--'}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Status</span>
              {statusBadge(row.status)}
            </div>
          </div>
        </div>

        {/* File Details & Integrity */}
        <div className="space-y-3">
          <h4 className="text-xs font-semibold text-secondary uppercase tracking-wider flex items-center gap-1.5">
            <ShieldCheckIcon className="w-3.5 h-3.5" /> File Details & Integrity
          </h4>
          <div className="bg-surface rounded-lg border border-border p-3 space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-secondary">Source Checksum</span>
              <span className="font-mono text-xs text-secondary truncate max-w-[140px]" title={row.sourceChecksum}>
                {row.sourceChecksum ? row.sourceChecksum.substring(0, 16) + '...' : '--'}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Dest Checksum</span>
              <span className="font-mono text-xs text-secondary truncate max-w-[140px]" title={row.destinationChecksum}>
                {row.destinationChecksum ? row.destinationChecksum.substring(0, 16) + '...' : '--'}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Integrity</span>
              {row.integrityStatus === 'VERIFIED' ? (
                <span className="badge badge-green text-xs">VERIFIED</span>
              ) : row.integrityStatus === 'MISMATCH' ? (
                <span className="badge badge-red text-xs">MISMATCH</span>
              ) : (
                <span className="text-muted text-xs">{row.integrityStatus || '--'}</span>
              )}
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Encryption</span>
              <span className="text-primary">{row.encryptionOption || 'None'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Source Path</span>
              <span className="font-mono text-xs text-secondary truncate max-w-[160px]" title={row.sourcePath}>{row.sourcePath || '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Dest Path</span>
              <span className="font-mono text-xs text-secondary truncate max-w-[160px]" title={row.destPath}>{row.destPath || '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Retries</span>
              <span className="text-primary">{row.retryCount ?? 0}</span>
            </div>
          </div>
        </div>

        {/* Timestamps & Delivery */}
        <div className="space-y-3">
          <h4 className="text-xs font-semibold text-secondary uppercase tracking-wider flex items-center gap-1.5">
            <TruckIcon className="w-3.5 h-3.5" /> Timeline & Delivery
          </h4>
          <div className="bg-surface rounded-lg border border-border p-3 space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-secondary">Uploaded</span>
              <span className="text-xs text-primary font-mono">{row.uploadedAt ? format(new Date(row.uploadedAt), 'MMM dd HH:mm:ss') : '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Routed</span>
              <span className="text-xs text-primary font-mono">{row.routedAt ? format(new Date(row.routedAt), 'MMM dd HH:mm:ss') : '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Downloaded</span>
              <span className="text-xs text-primary font-mono">{row.downloadedAt ? format(new Date(row.downloadedAt), 'MMM dd HH:mm:ss') : '--'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Completed</span>
              <span className="text-xs text-primary font-mono">{row.completedAt ? format(new Date(row.completedAt), 'MMM dd HH:mm:ss') : '--'}</span>
            </div>
            {row.uploadedAt && row.completedAt && (
              <div className="flex justify-between pt-1 border-t border-border">
                <span className="text-secondary font-medium">Total Duration</span>
                <span className="text-xs font-semibold text-blue-600">
                  {((new Date(row.completedAt) - new Date(row.uploadedAt)) / 1000).toFixed(1)}s
                </span>
              </div>
            )}
            {row.flowName && (
              <div className="flex justify-between items-center pt-1 border-t border-border">
                <span className="text-secondary">Flow</span>
                <ConfigLink type="flow" name={row.flowName} onEdit={onEditConfig} navigateTo="/flows" />
              </div>
            )}
            {row.flowStatus && (
              <div className="flex justify-between">
                <span className="text-secondary">Flow Status</span>
                <span className="text-primary">{row.flowStatus}</span>
              </div>
            )}
            {row.externalDestName && (
              <div className="flex justify-between items-center">
                <span className="text-secondary">External Dest</span>
                <ConfigLink type="destination" name={row.externalDestName} onEdit={onEditConfig} navigateTo="/external-destinations" />
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Flow Execution Steps Timeline */}
      {flowExec && flowExec.steps && flowExec.steps.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold text-secondary uppercase tracking-wider mb-3 flex items-center gap-1.5">
            <ArrowRightIcon className="w-3.5 h-3.5" /> Flow Execution Steps
          </h4>
          <div className="bg-surface rounded-lg border border-border p-4">
            <div className="flex items-center gap-2 overflow-x-auto pb-2">
              {flowExec.steps.map((step, idx) => (
                <React.Fragment key={idx}>
                  <div className={`flex-shrink-0 flex items-center gap-2 px-3 py-2 rounded-lg border text-xs ${
                    step.status === 'FAILED' ? 'bg-red-50 border-red-200' :
                    step.status?.startsWith('OK') ? 'bg-green-50 border-green-200' :
                    'bg-canvas border-border'
                  }`}>
                    {stepStatusIcon(step.status)}
                    <div>
                      <p className="font-medium text-gray-800">{step.type || step.name || `Step ${idx + 1}`}</p>
                      {step.durationMs != null && (
                        <p className="text-muted">{step.durationMs}ms</p>
                      )}
                      {step.queueName && (
                        <p className="text-[9px] text-blue-500 font-mono">{step.queueName}</p>
                      )}
                    </div>
                  </div>
                  {idx < flowExec.steps.length - 1 && (
                    <ArrowRightIcon className="w-3.5 h-3.5 text-gray-300 flex-shrink-0" />
                  )}
                </React.Fragment>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Event Journal (from flow-events) */}
      {events && events.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold text-secondary uppercase tracking-wider mb-3">
            Event Journal ({events.length} events)
          </h4>
          <div className="bg-surface rounded-lg border border-border overflow-hidden">
            <div className="max-h-48 overflow-y-auto">
              <table className="w-full text-xs">
                <thead className="bg-canvas sticky top-0">
                  <tr>
                    <th className="text-left px-3 py-2 text-secondary font-medium">Time</th>
                    <th className="text-left px-3 py-2 text-secondary font-medium">Event</th>
                    <th className="text-left px-3 py-2 text-secondary font-medium">Status</th>
                    <th className="text-left px-3 py-2 text-secondary font-medium">Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((evt, idx) => (
                    <tr key={idx} className="border-t border-gray-50 hover:bg-canvas/50">
                      <td className="px-3 py-1.5 font-mono text-secondary whitespace-nowrap">
                        {evt.timestamp ? format(new Date(evt.timestamp), 'HH:mm:ss.SSS') : '--'}
                      </td>
                      <td className="px-3 py-1.5 font-medium text-gray-800">{evt.event || evt.type || '--'}</td>
                      <td className="px-3 py-1.5">
                        {evt.status === 'COMPLETED' || evt.status === 'PASSED' ? (
                          <span className="badge badge-green text-xs">{evt.status}</span>
                        ) : evt.status === 'FAILED' ? (
                          <span className="badge badge-red text-xs">{evt.status}</span>
                        ) : (
                          <span className="text-secondary">{evt.status || '--'}</span>
                        )}
                      </td>
                      <td className="px-3 py-1.5 text-secondary truncate max-w-[300px]" title={evt.detail || evt.message}>
                        {evt.detail || evt.message || '--'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Error message if present */}
      {row.errorMessage && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3">
          <p className="text-xs font-semibold text-red-700 mb-1">Error</p>
          <p className="text-sm text-red-600">{row.errorMessage}</p>
        </div>
      )}

      {/* Quick actions */}
      <div className="flex items-center gap-2 pt-2 border-t border-border">
        <button
          onClick={(e) => { e.stopPropagation(); navigate(`/journey?trackId=${encodeURIComponent(row.trackId)}`) }}
          className="btn-secondary text-xs"
        >
          Open Full Journey View
        </button>
      </div>

      <style>{`
        @keyframes slideDown {
          from { opacity: 0; max-height: 0; }
          to { opacity: 1; max-height: 1000px; }
        }
        .animate-slideDown {
          animation: slideDown 0.25s ease-out;
        }
      `}</style>
    </div>
  )
}

// ── Main Component ──────────────────────────────────────────────────────
export default function ActivityMonitor() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const { isAdmin } = useAuth()
  const canOperate = isAdmin // OPERATOR+ can restart/stop/terminate
  const { visibleKeys, visibleColumns, toggle, resetToDefaults } = useColumnPreferences()

  // URL-driven filter state — enables deep-linking from Flow Fabric KPI cards
  // and queue bars. Any filter the Fabric dashboard passes as a query param
  // (?status=IN_PROGRESS, ?stepType=ENCRYPT, ?stuckOnly=true) is honored on load.
  const [searchParams, setSearchParams] = useSearchParams()
  const urlStatus     = searchParams.get('status')
  const urlStepType   = searchParams.get('stepType')
  const urlStuckOnly  = searchParams.get('stuckOnly') === 'true'
  const urlTrackId    = searchParams.get('trackId')

  // Sticky filters — restore from localStorage if the URL is empty.
  // URL always wins on a deep-link (?status=FAILED etc). If neither is
  // set, the user's last session's filters come back automatically.
  const stickyFilters = useStickyFilters('activity-monitor', {
    filenameFilter: '',
    trackIdFilter: '',
    statusFilter: 'ALL',
    sourceUserFilter: '',
    protocolFilter: 'ALL',
    stuckOnly: false,
    stepTypeFilter: '',
  })
  const hasUrlFilter = !!(urlStatus || urlStepType || urlStuckOnly || urlTrackId)
  const stickyInitial = hasUrlFilter ? null : stickyFilters.restoreInitial()

  // Pagination & sort state
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [sortBy, setSortBy] = useState('uploadedAt')
  const [sortDir, setSortDir] = useState('DESC')

  // Filters — URL wins > sticky wins > default. Every filter is URL-driven
  // for deep-linking AND sticky across reloads.
  const [filenameFilter, setFilenameFilter]     = useState(stickyInitial?.filenameFilter    || '')
  const [trackIdFilter, setTrackIdFilter]       = useState(urlTrackId || stickyInitial?.trackIdFilter || '')
  const [statusFilter, setStatusFilter]         = useState(urlStatus  || stickyInitial?.statusFilter  || 'ALL')
  const [sourceUserFilter, setSourceUserFilter] = useState(stickyInitial?.sourceUserFilter || '')
  const [protocolFilter, setProtocolFilter]     = useState(stickyInitial?.protocolFilter   || 'ALL')
  const [stuckOnly, setStuckOnly]               = useState(urlStuckOnly || (stickyInitial?.stuckOnly ?? false))
  // stepType filter is not a backend param (yet) — we filter client-side on fabricStatus
  const [stepTypeFilter, setStepTypeFilter]     = useState(urlStepType || stickyInitial?.stepTypeFilter || '')

  // Persist sticky filters whenever any of them change
  useEffect(() => {
    stickyFilters.persist({
      filenameFilter, trackIdFilter, statusFilter,
      sourceUserFilter, protocolFilter, stuckOnly, stepTypeFilter,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filenameFilter, trackIdFilter, statusFilter, sourceUserFilter, protocolFilter, stuckOnly, stepTypeFilter])

  // UI state
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [expandedTrackId, setExpandedTrackId] = useState(null)
  const [activeTab, setActiveTab] = useState('transfers') // 'transfers' | 'scheduled'

  // Bulk restart state
  const [selectedTrackIds, setSelectedTrackIds] = useState(new Set())
  const [showBulkConfirm, setShowBulkConfirm] = useState(false)

  // Schedule retry state
  const [scheduleModal, setScheduleModal] = useState(null) // { trackId, filename }
  const [scheduleInput, setScheduleInput] = useState('')

  // Execution detail drawer state (double-click)
  const [drawerTrackId, setDrawerTrackId] = useState(null)

  // R108: AI Copilot drawer state
  const [aiDrawerTrackId, setAiDrawerTrackId] = useState(null)

  // Keyboard navigation state
  const [selectedRowIdx, setSelectedRowIdx] = useState(-1)
  const filenameInputRef = useRef(null)

  // Inline config editor state
  const [editConfig, setEditConfig] = useState(null)

  // ── Saved views state ────────────────────────────────────────────────
  // Dropdown panel is closed by default. savedViews is hydrated from
  // localStorage; any mutation (save/delete) both sets state and persists.
  const [viewsOpen, setViewsOpen] = useState(false)
  const [savedViews, setSavedViews] = useState(() => loadSavedViews())
  const [savingView, setSavingView] = useState(false)
  const [newViewName, setNewViewName] = useState('')
  const [deleteViewTarget, setDeleteViewTarget] = useState(null) // { id, name }
  const viewsRef = useRef(null)
  const saveInputRef = useRef(null)

  // Debounced text filters
  const debouncedFilename = useDebounce(filenameFilter, 300)
  const debouncedTrackId = useDebounce(trackIdFilter, 300)
  const debouncedSourceUser = useDebounce(sourceUserFilter, 300)

  // Reset page when filters change
  const filterRef = useRef({ debouncedFilename, debouncedTrackId, statusFilter, debouncedSourceUser, protocolFilter })
  useEffect(() => {
    const prev = filterRef.current
    if (prev.debouncedFilename !== debouncedFilename || prev.debouncedTrackId !== debouncedTrackId ||
        prev.statusFilter !== statusFilter || prev.debouncedSourceUser !== debouncedSourceUser ||
        prev.protocolFilter !== protocolFilter) {
      setPage(0)
      filterRef.current = { debouncedFilename, debouncedTrackId, statusFilter, debouncedSourceUser, protocolFilter }
    }
  }, [debouncedFilename, debouncedTrackId, statusFilter, debouncedSourceUser, protocolFilter])

  // Build query params
  const queryParams = useMemo(() => {
    const params = { page, size, sortBy, sortDir }
    if (debouncedFilename) params.filename = debouncedFilename
    if (debouncedTrackId) params.trackId = debouncedTrackId
    if (statusFilter !== 'ALL') params.status = statusFilter
    if (debouncedSourceUser) params.sourceUsername = debouncedSourceUser
    if (protocolFilter !== 'ALL') params.protocol = protocolFilter
    return params
  }, [page, size, sortBy, sortDir, debouncedFilename, debouncedTrackId, statusFilter, debouncedSourceUser, protocolFilter])

  // Data fetching
  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['activity-monitor', queryParams],
    queryFn: () => onboardingApi.get('/api/activity-monitor', { params: queryParams }).then(r => r.data),
    placeholderData: keepPreviousData,
    refetchInterval: autoRefresh ? 30000 : false,
    meta: { silent: true, errorMessage: "Couldn't load transfers" },
  })

  // Skeleton only shows on the first fetch (no data yet), and only if the
  // fetch takes longer than ~100ms — avoids a flash on cached/fast responses.
  const isFirstLoad = isLoading && !data
  const showSkeleton = useDelayedFlag(isFirstLoad, 100)

  // ── SSE Real-Time Stream ──────────────────────────────────────────────
  const [liveCount, setLiveCount] = useState(0)
  const [sseConnected, setSseConnected] = useState(false)
  useEffect(() => {
    let es
    try {
      const baseUrl = onboardingApi.defaults.baseURL || ''
      const jwt = localStorage.getItem('token') || ''
      es = new EventSource(`${baseUrl}/api/activity-monitor/stream?token=${encodeURIComponent(jwt)}`)
      es.onopen = () => setSseConnected(true)
      es.onerror = () => setSseConnected(false)
      es.addEventListener('transfer-new', () => {
        setLiveCount(c => c + 1)
        qc.invalidateQueries({ queryKey: ['activity-monitor'] })
      })
      es.addEventListener('transfer-completed', () => {
        qc.invalidateQueries({ queryKey: ['activity-monitor'] })
      })
      es.addEventListener('transfer-failed', () => {
        qc.invalidateQueries({ queryKey: ['activity-monitor'] })
      })
    } catch (e) { /* SSE not available — fall back to polling */ }
    return () => { if (es) es.close() }
  }, [qc])

  const rawRows = data?.content || []
  // Client-side filters layered on top of the server query:
  //   stuckOnly   — hide non-stuck rows
  //   stepType    — hide rows whose current Fabric step doesn't match
  const rows = useMemo(() => {
    let out = rawRows
    if (stuckOnly)           out = out.filter(r => r.isStuck)
    if (stepTypeFilter)      out = out.filter(r => r.currentStepType === stepTypeFilter)
    return out
  }, [rawRows, stuckOnly, stepTypeFilter])
  const totalElements = (stuckOnly || stepTypeFilter) ? rows.length : (data?.totalElements || 0)
  const totalPages    = (stuckOnly || stepTypeFilter) ? 1 : (data?.totalPages || 0)

  // ── Restart mutation (must be before keyboard shortcuts useEffect) ───
  const restartOneMut = useMutation({
    mutationFn: (trackId) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/restart`).then(r => r.data),
    onSuccess: () => {
      toast.success('Transfer restart initiated')
      qc.invalidateQueries(['activity-monitor'])
    },
    onError: err => toast.error(err.response?.data?.message || 'Restart failed')
  })

  // ── Keyboard Shortcuts ────────────────────────────────────────────────
  useEffect(() => {
    const handler = (e) => {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT') return
      if (e.key === 'j') {
        setSelectedRowIdx(prev => {
          const max = (rows?.length || 1) - 1
          return Math.min(prev + 1, max)
        })
      }
      if (e.key === 'k') {
        setSelectedRowIdx(prev => Math.max(prev - 1, 0))
      }
      if (e.key === 'r') {
        if (selectedRowIdx >= 0 && rows[selectedRowIdx]) {
          const row = rows[selectedRowIdx]
          if (RESTARTABLE_STATUSES.has(row.status) && canOperate) {
            if (window.confirm(`Restart transfer ${row.trackId}?`)) restartOneMut.mutate(row.trackId)
          }
        }
      }
      if (e.key === 'd') {
        if (selectedRowIdx >= 0 && rows[selectedRowIdx]) {
          const row = rows[selectedRowIdx]
          if (row.trackId) setDrawerTrackId(row.trackId)
        }
      }
      if (e.key === '/') {
        e.preventDefault()
        filenameInputRef.current?.focus()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [selectedRowIdx, rows, canOperate, restartOneMut])

  // ── Fabric KPI strip (polled independently so it never blocks the main table) ──
  // Gives the user at-a-glance Fabric context while they explore the Activity list.
  // Click-through on any KPI reopens the Fabric dashboard with matching focus.
  // Fabric KPI queries are cosmetic strips — they MUST NOT throw toasts
  // on fast-boot because the fabric backend may not be ready yet.
  // `meta: { silent: true }` opts out of the global QueryCache onError.
  const { data: fabQueues }    = useQuery({ queryKey: ['am-fabric-queues'],    queryFn: getFabricQueues,    refetchInterval: 10000, retry: 0, meta: { silent: true } })
  const { data: fabInstances } = useQuery({ queryKey: ['am-fabric-instances'], queryFn: getFabricInstances, refetchInterval: 15000, retry: 0, meta: { silent: true } })
  const { data: fabStuck }     = useQuery({ queryKey: ['am-fabric-stuck'],     queryFn: getFabricStuck,     refetchInterval: 15000, retry: 0, meta: { silent: true } })
  const { data: fabLatency }   = useQuery({ queryKey: ['am-fabric-latency'],   queryFn: getFabricLatency,   refetchInterval: 30000, retry: 0, meta: { silent: true } })
  const fabInProgress = fabQueues?.inProgressByStepType
    ? Object.values(fabQueues.inProgressByStepType).reduce((a, b) => a + b, 0)
    : 0
  const fabHealthyInstances = fabInstances?.active?.length || 0
  const fabStuckCount = fabStuck?.totalElements ?? (Array.isArray(fabStuck) ? fabStuck.length : fabStuck?.items?.length ?? 0)
  const fabP95 = (() => {
    if (!fabLatency?.byStepType) return null
    const max = Math.max(...Object.values(fabLatency.byStepType).map(s => s.p95 || 0), 0)
    return max > 0 ? max : null
  })()

  // Sort toggle
  const handleSort = (key) => {
    if (sortBy === key) {
      setSortDir(d => d === 'ASC' ? 'DESC' : 'ASC')
    } else {
      setSortBy(key)
      setSortDir('DESC')
    }
    setPage(0)
  }

  // Row click → expand inline detail panel (toggle)
  const handleRowClick = (row) => {
    if (row.trackId) {
      setExpandedTrackId(prev => prev === row.trackId ? null : row.trackId)
    }
  }

  // Fetch flow execution detail for expanded row
  const { data: flowExecDetail } = useQuery({
    queryKey: ['flow-execution-detail', expandedTrackId],
    queryFn: () => configApi.get(`/api/flow-executions/${expandedTrackId}`).then(r => r.data),
    enabled: !!expandedTrackId,
    staleTime: 10000,
  })

  const { data: flowEvents = [] } = useQuery({
    queryKey: ['flow-events', expandedTrackId],
    queryFn: () => configApi.get(`/api/flow-executions/flow-events/${expandedTrackId}`).then(r => r.data),
    enabled: !!expandedTrackId,
    staleTime: 10000,
  })

  // ── Scheduled Retries ─────────────────────────────────────────────────
  const { data: scheduledRetries = [], isLoading: loadingRetries } = useQuery({
    queryKey: ['activity-scheduled-retries'],
    queryFn: () => onboardingApi.get('/api/flow-executions/scheduled-retries').then(r => r.data),
    meta: { silent: true }, refetchInterval: 60000
  })

  // ── Mutations ──────────────────────────────────────────────────────────
  const bulkRestartMut = useMutation({
    mutationFn: (trackIds) =>
      onboardingApi.post('/api/flow-executions/bulk-restart', { trackIds }).then(r => r.data),
    onSuccess: (data) => {
      setSelectedTrackIds(new Set())
      setShowBulkConfirm(false)
      qc.invalidateQueries(['activity-monitor'])
      qc.invalidateQueries(['activity-scheduled-retries'])
      const msg = data.queued === 0
        ? `No transfers restarted (${data.skipped} skipped)`
        : `${data.queued} transfer${data.queued !== 1 ? 's' : ''} queued for restart` +
          (data.skipped > 0 ? ` (${data.skipped} skipped)` : '')
      data.queued > 0 ? toast.success(msg) : toast.error(msg)
    },
    onError: err => toast.error(err.response?.data?.error || 'Bulk restart failed')
  })

  const scheduleRetryMut = useMutation({
    mutationFn: ({ trackId, scheduledAt }) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/schedule-retry`, { scheduledAt }).then(r => r.data),
    onSuccess: (data) => {
      setScheduleModal(null)
      qc.invalidateQueries(['activity-monitor'])
      qc.invalidateQueries(['activity-scheduled-retries'])
      toast.success(`Retry scheduled for ${new Date(data.scheduledAt).toLocaleString()}`)
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to schedule retry')
  })

  const terminateOneMut = useMutation({
    mutationFn: (trackId) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/terminate`).then(r => r.data),
    onSuccess: () => {
      toast.success('Transfer terminated')
      qc.invalidateQueries(['activity-monitor'])
    },
    onError: err => toast.error(err.response?.data?.message || 'Terminate failed')
  })

  // R106 pause / resume — engine polls between steps and preserves currentStep
  const pauseOneMut = useMutation({
    mutationFn: (trackId) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/pause`, {}).then(r => r.data),
    onSuccess: () => {
      toast.success('Pause requested — will take effect after current step')
      qc.invalidateQueries(['activity-monitor'])
    },
    onError: err => toast.error(err.response?.data?.message || 'Pause failed')
  })

  const resumeOneMut = useMutation({
    mutationFn: (trackId) =>
      onboardingApi.post(`/api/flow-executions/${trackId}/resume`).then(r => r.data),
    onSuccess: () => {
      toast.success('Resume queued')
      qc.invalidateQueries(['activity-monitor'])
    },
    onError: err => toast.error(err.response?.data?.message || 'Resume failed')
  })

  const cancelScheduleMut = useMutation({
    mutationFn: (trackId) =>
      onboardingApi.delete(`/api/flow-executions/${trackId}/schedule-retry`).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['activity-monitor'])
      qc.invalidateQueries(['activity-scheduled-retries'])
      toast.success('Scheduled retry cancelled')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to cancel')
  })

  // ── Checkbox helpers ───────────────────────────────────────────────────
  const RESTARTABLE_STATUSES = new Set(['FAILED'])
  const isRowSelectable = (row) => RESTARTABLE_STATUSES.has(row.status)

  const toggleRow = useCallback((trackId) => {
    setSelectedTrackIds(prev => {
      const next = new Set(prev)
      next.has(trackId) ? next.delete(trackId) : next.add(trackId)
      return next
    })
  }, [])

  const selectAllFailed = useCallback(() => {
    const failedIds = rows.filter(r => isRowSelectable(r)).map(r => r.trackId)
    setSelectedTrackIds(new Set(failedIds))
  }, [rows])

  // Clear all filters
  const clearFilters = () => {
    setFilenameFilter('')
    setTrackIdFilter('')
    setStatusFilter('ALL')
    setSourceUserFilter('')
    setProtocolFilter('ALL')
    setStuckOnly(false)
  }

  const hasFilters = filenameFilter || trackIdFilter || statusFilter !== 'ALL' || sourceUserFilter || protocolFilter !== 'ALL' || stuckOnly

  // ── Saved Views: apply / save / delete ────────────────────────────────
  // applyView reseeds every filter, sort, and page-size setter from the
  // supplied view, then syncs URL params so that deep-linking reflects the
  // new state. Any field missing from a built-in view resets to default.
  const applyView = useCallback((view) => {
    const f = view.filters || {}
    const sort = view.sort || {}
    setFilenameFilter(f.filenameFilter || '')
    setTrackIdFilter(f.trackIdFilter || '')
    setStatusFilter(f.statusFilter || 'ALL')
    setSourceUserFilter(f.sourceUserFilter || '')
    setProtocolFilter(f.protocolFilter || 'ALL')
    setStuckOnly(!!f.stuckOnly)
    setStepTypeFilter(f.stepTypeFilter || '')
    if (sort.sortBy) setSortBy(sort.sortBy)
    if (sort.sortDir) setSortDir(sort.sortDir)
    if (sort.size) setSize(sort.size)
    setPage(0)

    // Reflect into URL so browser back/forward + deep links still work.
    const next = {}
    if (f.statusFilter && f.statusFilter !== 'ALL') next.status = f.statusFilter
    if (f.stuckOnly) next.stuckOnly = 'true'
    if (f.trackIdFilter) next.trackId = f.trackIdFilter
    if (f.stepTypeFilter) next.stepType = f.stepTypeFilter
    setSearchParams(next, { replace: true })

    setViewsOpen(false)
    setSavingView(false)
    setNewViewName('')
    toast.success(`Applied view: ${view.name}`)
  }, [setSearchParams])

  // Persist the current filter/sort/page-size snapshot as a new saved view.
  // Caps at SAVED_VIEWS_MAX by dropping the oldest entry (by createdAt).
  const saveCurrentView = useCallback(() => {
    const name = (newViewName || '').trim()
    if (!name) { toast.error('Please name this view'); return }
    const uuid = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : `v-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    const snapshot = {
      id: uuid,
      name,
      createdAt: Date.now(),
      filters: {
        filenameFilter,
        trackIdFilter,
        statusFilter,
        sourceUserFilter,
        protocolFilter,
        stuckOnly,
        stepTypeFilter,
      },
      sort: { sortBy, sortDir, size },
    }
    setSavedViews(prev => {
      let next = [snapshot, ...prev]
      if (next.length > SAVED_VIEWS_MAX) {
        next = [...next].sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)).slice(0, SAVED_VIEWS_MAX)
      }
      persistSavedViews(next)
      return next
    })
    setSavingView(false)
    setNewViewName('')
    toast.success(`Saved view: ${name}`)
  }, [newViewName, filenameFilter, trackIdFilter, statusFilter, sourceUserFilter, protocolFilter, stuckOnly, stepTypeFilter, sortBy, sortDir, size])

  const confirmDeleteView = useCallback(() => {
    if (!deleteViewTarget) return
    setSavedViews(prev => {
      const next = prev.filter(v => v.id !== deleteViewTarget.id)
      persistSavedViews(next)
      return next
    })
    toast.success(`Deleted view: ${deleteViewTarget.name}`)
    setDeleteViewTarget(null)
  }, [deleteViewTarget])

  // Close the dropdown on outside click / Esc. Also auto-focus the name
  // input when the user enters "save" mode.
  useEffect(() => {
    if (!viewsOpen) return
    const onClick = (e) => {
      if (viewsRef.current && !viewsRef.current.contains(e.target)) {
        setViewsOpen(false)
        setSavingView(false)
        setNewViewName('')
      }
    }
    const onKey = (e) => {
      if (e.key === 'Escape') {
        setViewsOpen(false)
        setSavingView(false)
        setNewViewName('')
      }
    }
    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [viewsOpen])

  useEffect(() => {
    if (savingView && saveInputRef.current) saveInputRef.current.focus()
  }, [savingView])

  // Which view is currently active? (shallow compare of filter state)
  const activeViewId = useMemo(() => {
    const cur = {
      filenameFilter, trackIdFilter,
      statusFilter: statusFilter === 'ALL' ? '' : statusFilter,
      sourceUserFilter,
      protocolFilter: protocolFilter === 'ALL' ? '' : protocolFilter,
      stuckOnly: !!stuckOnly,
      stepTypeFilter,
    }
    const match = (f) => {
      const n = (v) => v == null ? '' : v
      return n(f.filenameFilter) === cur.filenameFilter
        && n(f.trackIdFilter) === cur.trackIdFilter
        && (n(f.statusFilter) === '' ? '' : n(f.statusFilter)) === (cur.statusFilter || '')
        && n(f.sourceUserFilter) === cur.sourceUserFilter
        && (n(f.protocolFilter) === '' ? '' : n(f.protocolFilter)) === (cur.protocolFilter || '')
        && !!f.stuckOnly === cur.stuckOnly
        && n(f.stepTypeFilter) === cur.stepTypeFilter
    }
    for (const b of BUILT_IN_VIEWS) if (match(b.filters || {})) return b.id
    for (const s of savedViews) if (match(s.filters || {})) return s.id
    return null
  }, [filenameFilter, trackIdFilter, statusFilter, sourceUserFilter, protocolFilter, stuckOnly, stepTypeFilter, savedViews])

  // CSV export
  const exportCSV = () => {
    const headers = visibleColumns.map(c => c.label)
    const csvRows = rows.map(row =>
      visibleColumns.map(c => {
        const val = row[c.key]
        if (val == null) return ''
        const str = String(val)
        return str.includes(',') || str.includes('"') || str.includes('\n') ? `"${str.replace(/"/g, '""')}"` : str
      })
    )
    const csv = [headers.join(','), ...csvRows.map(r => r.join(','))].join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `activity-monitor-${format(new Date(), 'yyyy-MM-dd-HHmmss')}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  // Pagination helpers
  const startItem = totalElements === 0 ? 0 : page * size + 1
  const endItem = Math.min((page + 1) * size, totalElements)

  // Page numbers for pagination
  const pageNumbers = useMemo(() => {
    const pages = []
    const maxButtons = 5
    let start = Math.max(0, page - Math.floor(maxButtons / 2))
    let end = Math.min(totalPages, start + maxButtons)
    if (end - start < maxButtons) start = Math.max(0, end - maxButtons)
    for (let i = start; i < end; i++) pages.push(i)
    return pages
  }, [page, totalPages])

  return (
    <div className="space-y-6">
      {/* ── Fabric context strip — always visible above the table ──────────
          Gives users at-a-glance Fabric state without leaving this page. Each
          KPI click-through opens the Fabric dashboard scoped appropriately.   */}
      <div
        className="grid grid-cols-2 md:grid-cols-4 gap-3 rounded-xl p-3"
        style={{ background: 'rgba(59, 130, 246, 0.04)', border: '1px solid rgb(var(--border))' }}
      >
        <FabricMiniCard
          label="In Fabric"
          value={fabInProgress}
          hint="In-progress steps across all instances"
          to="/operations/fabric"
          color="rgb(96, 165, 250)"
        />
        <FabricMiniCard
          label="Healthy Pods"
          value={fabHealthyInstances}
          hint="Fabric instances with live heartbeat"
          to="/operations/fabric"
          color="rgb(74, 222, 128)"
        />
        <FabricMiniCard
          label="Stuck"
          value={fabStuckCount}
          hint={fabStuckCount > 0 ? 'Files past lease — click to filter' : 'No stuck work'}
          to="/operations/activity?stuckOnly=true"
          color={fabStuckCount > 0 ? 'rgb(248, 113, 113)' : 'rgb(148, 163, 184)'}
        />
        <FabricMiniCard
          label="p95 Latency"
          value={fabP95 != null ? `${fabP95}ms` : '—'}
          hint="Highest p95 across all step types"
          to="/operations/fabric"
          color="rgb(250, 204, 21)"
        />
      </div>

      {/* ── Header ───────────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-primary">
              Activity Monitor
              {sseConnected ? (
                <span className="ml-2 inline-flex items-center text-xs font-normal text-green-600">
                  <span className="w-2 h-2 bg-green-500 rounded-full mr-1 animate-pulse" />
                  Live{liveCount > 0 ? ` (${liveCount} new)` : ''}
                </span>
              ) : (
                <span className="ml-2 inline-flex items-center text-xs font-normal text-amber-600">
                  <span className="w-2 h-2 bg-amber-500 rounded-full mr-1" />
                  Polling (30s)
                </span>
              )}
            </h1>
            {totalElements > 0 && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-blue-50 text-blue-700 text-xs font-semibold rounded-full ring-1 ring-inset ring-blue-600/10">
                {totalElements.toLocaleString()} transfers
              </span>
            )}
            {stepTypeFilter && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-semibold rounded-full"
                    style={{ background: 'rgba(234, 179, 8, 0.15)', color: 'rgb(250, 204, 21)' }}>
                Step: {stepTypeFilter}
                <Link to="/operations/activity" className="ml-1 hover:underline">✕</Link>
              </span>
            )}
            {stuckOnly && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-semibold rounded-full"
                    style={{ background: 'rgba(239, 68, 68, 0.15)', color: 'rgb(248, 113, 113)' }}>
                Stuck only
                <Link to="/operations/activity" className="ml-1 hover:underline">✕</Link>
              </span>
            )}
            {isFetching && !isLoading && (
              <div className="w-4 h-4 border-2 border-blue-400 border-t-transparent rounded-full animate-spin" />
            )}
          </div>
          {/* R127: subtitle "Monitor all file transfers across the platform"
              removed per tester's UX review — boilerplate that consumed a
              full line of chrome on the default viewport. */}
        </div>
        <div className="flex items-center gap-2">
          {/* Auto-refresh toggle */}
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg border transition-all ${
              autoRefresh
                ? 'bg-green-50 text-green-700 border-green-200 hover:bg-green-100'
                : 'bg-surface text-secondary border-border hover:bg-canvas'
            }`}
            title={autoRefresh ? 'Auto-refresh every 30s' : 'Auto-refresh paused'}
          >
            <ArrowPathIcon className={`w-3.5 h-3.5 ${autoRefresh ? 'animate-spin' : ''}`} style={autoRefresh ? { animationDuration: '3s' } : {}} />
            {autoRefresh ? '30s' : 'Paused'}
          </button>

          {/* CSV Export */}
          <button onClick={exportCSV} className="btn-secondary" disabled={rows.length === 0}>
            <ArrowDownTrayIcon className="w-4 h-4" />
            Export
          </button>

          {/* Saved Views dropdown */}
          <div className="relative" ref={viewsRef}>
            <button
              onClick={() => { setViewsOpen(o => !o); setSavingView(false); setNewViewName('') }}
              className="btn-secondary"
              title="Saved filter views"
              aria-haspopup="true"
              aria-expanded={viewsOpen}
            >
              <BookmarkIcon className="w-4 h-4" />
              Views
              {activeViewId && (
                <span
                  className="ml-1 w-1.5 h-1.5 rounded-full"
                  style={{ background: 'rgb(var(--accent))' }}
                  aria-label="active view"
                />
              )}
            </button>

            {viewsOpen && (
              <div
                className="absolute right-0 mt-2 w-[300px] rounded-lg shadow-xl z-50 overflow-hidden"
                style={{
                  background: 'rgb(var(--surface))',
                  border: '1px solid rgb(var(--border))',
                }}
                role="menu"
              >
                {/* Built-in views */}
                <div className="px-3 pt-3 pb-1">
                  <div className="text-[10px] font-semibold uppercase tracking-wider text-muted mb-1.5">Built-in</div>
                  {BUILT_IN_VIEWS.map(v => {
                    const active = activeViewId === v.id
                    return (
                      <button
                        key={v.id}
                        onClick={() => applyView(v)}
                        onKeyDown={(e) => { if (e.key === 'Enter') applyView(v) }}
                        className="w-full flex items-center justify-between gap-2 px-2 py-1.5 rounded-md text-left hover:bg-canvas focus:outline-none focus:bg-canvas transition-colors"
                        role="menuitem"
                      >
                        <div className="flex-1 min-w-0">
                          <div className="text-xs font-medium text-primary truncate">{v.name}</div>
                          <div className="text-[10px] text-muted truncate">{v.description}</div>
                        </div>
                        {active && (
                          <CheckIcon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: 'rgb(var(--accent))' }} />
                        )}
                      </button>
                    )
                  })}
                </div>

                <div style={{ borderTop: '1px solid rgb(var(--border))' }} />

                {/* Saved views */}
                <div className="px-3 pt-2 pb-1">
                  <div className="text-[10px] font-semibold uppercase tracking-wider text-muted mb-1.5">Saved</div>
                  {savedViews.length === 0 ? (
                    <div className="flex items-start gap-2 px-2 py-3 text-[11px] text-muted">
                      <LightBulbIcon className="w-4 h-4 flex-shrink-0 mt-0.5" />
                      <span>No saved views yet. Apply some filters and click 'Save current view'.</span>
                    </div>
                  ) : (
                    <div className="max-h-[220px] overflow-y-auto">
                      {[...savedViews]
                        .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0))
                        .map(v => {
                          const active = activeViewId === v.id
                          return (
                            <div
                              key={v.id}
                              className="group w-full flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-canvas transition-colors"
                            >
                              <button
                                onClick={() => applyView(v)}
                                onKeyDown={(e) => { if (e.key === 'Enter') applyView(v) }}
                                className="flex-1 min-w-0 text-left focus:outline-none"
                                role="menuitem"
                              >
                                <div className="text-xs font-medium text-primary truncate flex items-center gap-1.5">
                                  {v.name}
                                  {active && (
                                    <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'rgb(var(--accent))' }} />
                                  )}
                                </div>
                                <div className="text-[10px] text-muted truncate">{describeViewFilters(v)}</div>
                              </button>
                              <button
                                onClick={(e) => { e.stopPropagation(); setDeleteViewTarget({ id: v.id, name: v.name }) }}
                                className="p-1 rounded text-muted hover:text-red-500 hover:bg-red-500/10 transition-colors"
                                title={`Delete ${v.name}`}
                                aria-label={`Delete ${v.name}`}
                              >
                                <TrashIcon className="w-3.5 h-3.5" />
                              </button>
                            </div>
                          )
                        })}
                    </div>
                  )}
                </div>

                <div style={{ borderTop: '1px solid rgb(var(--border))' }} />

                {/* Save current view */}
                <div className="px-3 py-2">
                  {savingView ? (
                    <div className="flex items-center gap-1.5">
                      <input
                        ref={saveInputRef}
                        type="text"
                        value={newViewName}
                        onChange={e => setNewViewName(e.target.value)}
                        onKeyDown={e => {
                          if (e.key === 'Enter') { e.preventDefault(); saveCurrentView() }
                          else if (e.key === 'Escape') { e.preventDefault(); setSavingView(false); setNewViewName('') }
                        }}
                        placeholder="View name..."
                        maxLength={60}
                        className="flex-1 text-xs px-2 py-1.5 rounded-md text-primary focus:outline-none"
                        style={{
                          background: 'rgb(var(--border))',
                          border: '1px solid rgb(55, 55, 65)',
                        }}
                      />
                      <button
                        onClick={saveCurrentView}
                        className="px-2 py-1.5 text-xs font-semibold rounded-md text-white"
                        style={{ background: 'rgb(var(--accent))' }}
                      >
                        Save
                      </button>
                      <button
                        onClick={() => { setSavingView(false); setNewViewName('') }}
                        className="p-1.5 text-muted hover:text-primary rounded-md"
                        title="Cancel"
                        aria-label="Cancel save"
                      >
                        <XMarkIcon className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => setSavingView(true)}
                      disabled={!hasFilters}
                      className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 text-xs font-semibold rounded-md text-white disabled:opacity-50 disabled:cursor-not-allowed transition-opacity"
                      style={{ background: 'rgb(var(--accent))' }}
                      title={hasFilters ? 'Save current filters as a view' : 'Apply some filters first'}
                    >
                      <PlusIcon className="w-3.5 h-3.5" />
                      Save current view
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* Column Settings */}
          <button
            onClick={() => setSettingsOpen(true)}
            className="btn-secondary"
          >
            <Cog6ToothIcon className="w-4 h-4" />
            Columns
          </button>
        </div>
      </div>

      {/* Delete saved-view confirmation */}
      <ConfirmDialog
        open={!!deleteViewTarget}
        variant="danger"
        title="Delete view?"
        message={deleteViewTarget ? `This permanently removes the saved view "${deleteViewTarget.name}" from this browser.` : ''}
        confirmLabel="Delete view"
        cancelLabel="Keep"
        onConfirm={confirmDeleteView}
        onCancel={() => setDeleteViewTarget(null)}
      />

      {/* ── Tab bar ─────────────────────────────────────────────── */}
      <div className="flex items-center gap-2 border-b border-border pb-2">
        {[
          { key: 'transfers', label: 'Transfers' },
          { key: 'scheduled', label: `Scheduled Retries${scheduledRetries.length > 0 ? ` (${scheduledRetries.length})` : ''}` },
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

      {/* ── Bulk Restart Toolbar ────────────────────────────────── */}
      {activeTab === 'transfers' && selectedTrackIds.size > 0 && (
        <div className="flex items-center gap-3 px-4 py-3 rounded-lg bg-red-50 border border-red-200">
          <span className="text-sm font-medium text-red-700">
            {selectedTrackIds.size} transfer{selectedTrackIds.size !== 1 ? 's' : ''} selected
          </span>
          <button
            onClick={() => setShowBulkConfirm(true)}
            disabled={bulkRestartMut.isPending}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-60 transition-colors"
          >
            <ArrowPathIcon className={`w-3.5 h-3.5 ${bulkRestartMut.isPending ? 'animate-spin' : ''}`} />
            {bulkRestartMut.isPending ? 'Restarting...' : 'Bulk Restart'}
          </button>
          <button
            onClick={() => setSelectedTrackIds(new Set())}
            className="text-xs text-red-500 hover:text-red-700 transition-colors"
          >
            Clear Selection
          </button>
          <div className="flex-1" />
          {rows.some(r => isRowSelectable(r)) && (
            <button
              onClick={selectAllFailed}
              className="text-xs text-secondary hover:text-red-600 px-2 py-1 rounded hover:bg-red-50 transition-colors"
            >
              Select all failed on page
            </button>
          )}
        </div>
      )}

      {/* ═══ Scheduled Retries Tab ═══ */}
      {activeTab === 'scheduled' && (
        <div className="space-y-4">
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <ClockIcon className="w-5 h-5 text-blue-500" />
                <h3 className="font-semibold text-primary">Pending Scheduled Retries</h3>
                <span className="text-xs text-muted">(auto-refresh 60s)</span>
              </div>
              <button onClick={() => qc.invalidateQueries(['activity-scheduled-retries'])}
                className="p-1.5 text-muted hover:text-blue-600 rounded-lg hover:bg-blue-50 transition-colors"
                title="Refresh now" aria-label="Refresh now">
                <ArrowPathIcon className="w-4 h-4" />
              </button>
            </div>
            {loadingRetries ? (
              <LoadingSpinner text="Loading scheduled retries..." />
            ) : scheduledRetries.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12">
                <ClockIcon className="w-10 h-10 text-muted mb-3" />
                <p className="text-sm text-secondary">No scheduled retries pending</p>
                <p className="text-xs text-muted mt-1">Schedule retries from the Transfers tab by clicking the row action menu on failed transfers.</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="table-header">Track ID</th>
                      <th className="table-header">Filename</th>
                      <th className="table-header">Flow</th>
                      <th className="table-header">Original Error</th>
                      <th className="table-header">Scheduled For</th>
                      <th className="table-header">Scheduled By</th>
                      <th className="table-header w-24">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scheduledRetries.map(r => (
                      <tr
                        key={r.trackId}
                        className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
                        onClick={() => navigate(`/journey?trackId=${encodeURIComponent(r.trackId)}`)}
                      >
                        <td className="table-cell">
                          <span className="font-mono text-xs font-bold text-blue-600">{r.trackId}</span>
                        </td>
                        <td className="table-cell text-sm text-primary truncate max-w-[200px]">{r.originalFilename || '--'}</td>
                        <td className="table-cell text-sm text-secondary">
                          <ConfigLink type="flow" name={r.flowName} onEdit={setEditConfig} navigateTo="/flows" />
                        </td>
                        <td className="table-cell">
                          <span className="text-xs text-red-600 truncate block max-w-[250px]" title={r.errorMessage}>
                            {r.errorMessage || '--'}
                          </span>
                        </td>
                        <td className="table-cell">
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-50 border border-blue-200 text-blue-700 rounded-full text-xs font-semibold">
                            <ClockIcon className="w-3 h-3" />
                            {new Date(r.scheduledAt).toLocaleString()}
                          </span>
                        </td>
                        <td className="table-cell text-xs text-secondary">{r.scheduledBy || '--'}</td>
                        <td className="table-cell" onClick={(e) => e.stopPropagation()}>
                          <button
                            onClick={() => cancelScheduleMut.mutate(r.trackId)}
                            disabled={cancelScheduleMut.isPending}
                            className="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-50"
                          >
                            <XCircleIcon className="w-3.5 h-3.5" />
                            Cancel
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ── Filter Bar ───────────────────────────────────────────── */}
      {activeTab === 'transfers' && <div className="card !p-4">
        <div className="flex items-center gap-3 flex-wrap">
          {/* R127: "FILTERS" label + funnel icon removed per tester's UX
              review — the inputs are self-evident, the label ate horizontal
              space that would otherwise fit another filter. */}

          {/* Filename */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted pointer-events-none" />
            <input
              ref={filenameInputRef}
              className="!w-48 !py-1.5 !pl-8 !pr-3 !text-xs"
              placeholder="Filename... (press /)"
              value={filenameFilter}
              onChange={e => setFilenameFilter(e.target.value)}
            />
          </div>

          {/* Track ID */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted pointer-events-none" />
            <input
              className="!w-40 !py-1.5 !pl-8 !pr-3 !text-xs font-mono"
              placeholder="Track ID..."
              value={trackIdFilter}
              onChange={e => setTrackIdFilter(e.target.value.toUpperCase())}
            />
          </div>

          {/* Status */}
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            className="!w-auto !py-1.5 !px-3 !text-xs"
          >
            {STATUS_OPTIONS.map(s => (
              <option key={s} value={s}>{s === 'ALL' ? 'All Statuses' : s.replace(/_/g, ' ')}</option>
            ))}
          </select>

          {/* Source Username */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted pointer-events-none" />
            <input
              className="!w-40 !py-1.5 !pl-8 !pr-3 !text-xs"
              placeholder="Source user..."
              value={sourceUserFilter}
              onChange={e => setSourceUserFilter(e.target.value)}
            />
          </div>

          {/* Protocol */}
          <select
            value={protocolFilter}
            onChange={e => setProtocolFilter(e.target.value)}
            className="!w-auto !py-1.5 !px-3 !text-xs"
          >
            {PROTOCOL_OPTIONS.map(p => (
              <option key={p} value={p}>{p === 'ALL' ? 'All Protocols' : p}</option>
            ))}
          </select>

          {/* Stuck (Fabric) toggle */}
          <label
            className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium rounded-lg border cursor-pointer transition-colors ${
              stuckOnly
                ? 'bg-red-50 text-red-700 border-red-200'
                : 'bg-surface text-secondary border-border hover:bg-canvas'
            }`}
            title="Show only files currently stuck in the Fabric"
          >
            <input
              type="checkbox"
              checked={stuckOnly}
              onChange={e => setStuckOnly(e.target.checked)}
              className="rounded border-border text-red-600 focus:ring-red-500 cursor-pointer"
            />
            Stuck only
          </label>

          {/* Clear filters */}
          {hasFilters && (
            <button
              onClick={clearFilters}
              className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-red-600 hover:text-red-700 hover:bg-red-50 rounded-lg transition-colors"
            >
              <XMarkIcon className="w-3.5 h-3.5" />
              Clear
            </button>
          )}
        </div>
      </div>}

      {/* ── Table ────────────────────────────────────────────────── */}
      {activeTab === 'transfers' && <div className="card !p-0 overflow-hidden">
        {isFirstLoad ? (
          showSkeleton ? (
            <Skeleton.Table
              rows={10}
              cols={[32, 120, 200, 80, 120, 80, 100, 80]}
              rowHeight={44}
            />
          ) : (
            /* Sub-100ms: keep the shell empty briefly to avoid skeleton flash */
            <div style={{ minHeight: '440px' }} aria-hidden="true" />
          )
        ) : rows.length === 0 ? (
          /* Empty state */
          <div className="flex flex-col items-center justify-center py-20 px-6">
            <div className="w-16 h-16 bg-hover rounded-2xl flex items-center justify-center mb-4">
              <TableCellsIcon className="w-8 h-8 text-muted" />
            </div>
            {hasFilters ? (
              <>
                <h3 className="text-base font-semibold text-primary mb-1">No transfers match</h3>
                <p className="text-sm text-secondary text-center max-w-sm">
                  No transfers match your current filters. Try adjusting or clearing them.
                </p>
                <button onClick={clearFilters} className="btn-secondary mt-4 text-xs">
                  <XMarkIcon className="w-3.5 h-3.5" /> Clear Filters
                </button>
              </>
            ) : totalElements === 0 ? (
              <>
                <h3 className="text-base font-semibold text-primary mb-1">No transfers yet</h3>
                <p className="text-sm text-secondary text-center max-w-md mb-4">
                  Upload a file via SFTP to see activity here. The Activity Monitor tracks every file
                  from arrival to delivery — checksums, routing decisions, flow execution, and delivery confirmation.
                </p>
                <div className="flex gap-3">
                  <a href="/flows" className="px-4 py-2 rounded-lg text-xs font-medium"
                     style={{ background: 'rgb(var(--accent))', color: '#fff' }}>
                    Configure Flows
                  </a>
                  <a href="/accounts" className="px-4 py-2 rounded-lg text-xs font-medium border border-border hover:bg-hover">
                    Create Account
                  </a>
                </div>
                <p className="text-[10px] text-muted mt-6 text-center max-w-xs">
                  {sseConnected ? '🟢 Live stream connected — transfers will appear instantly' : '🟡 Polling every 30s — transfers appear on next refresh'}
                </p>
              </>
            ) : (
              <>
                <h3 className="text-base font-semibold text-primary mb-1">No transfers found</h3>
                <p className="text-sm text-secondary text-center max-w-sm">
                  File transfers will appear here once they are processed by the platform.
                </p>
              </>
            )}
          </div>
        ) : (
          <>
            {/* R127: tip banner "Tip: Double-click any row to open detailed view"
                removed per tester's UX review. Discoverability lives in row-
                hover affordance, not a persistent banner. */}
            {/* Scrollable table container */}
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b border-border bg-canvas/80">
                    <th className="table-header w-10">
                      <span className="sr-only">Select</span>
                    </th>
                    {visibleColumns.map(col => (
                      <th
                        key={col.key}
                        className={`table-header cursor-pointer select-none hover:text-primary transition-colors whitespace-nowrap ${col.width}`}
                        onClick={() => handleSort(col.key)}
                      >
                        <div className="flex items-center gap-1">
                          {col.label}
                          {sortBy === col.key ? (
                            sortDir === 'ASC'
                              ? <ChevronUpIcon className="w-3 h-3 text-blue-600" />
                              : <ChevronDownIcon className="w-3 h-3 text-blue-600" />
                          ) : (
                            <div className="w-3 h-3" /> /* spacer for alignment */
                          )}
                        </div>
                      </th>
                    ))}
                    <th className="table-header w-24">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, i) => {
                    const isExpanded = expandedTrackId === row.trackId
                    const selectable = isRowSelectable(row)
                    const isSelected = selectedTrackIds.has(row.trackId)
                    return (
                      <React.Fragment key={row.trackId || i}>
                        <tr
                          className={`table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)] group ${isExpanded ? 'bg-blue-50/70' : ''} ${isSelected ? 'bg-red-50/50' : ''} ${selectedRowIdx === i ? 'ring-2 ring-inset ring-blue-400 bg-blue-50/40' : ''}`}
                          onClick={() => handleRowClick(row)}
                          onDoubleClick={() => row.trackId && setDrawerTrackId(row.trackId)}
                        >
                          <td className="table-cell w-10" onClick={e => e.stopPropagation()}>
                            {selectable ? (
                              <input
                                type="checkbox"
                                checked={isSelected}
                                onChange={() => toggleRow(row.trackId)}
                                className="rounded border-border text-red-600 focus:ring-red-500 cursor-pointer"
                              />
                            ) : (
                              <span className="block w-4" />
                            )}
                          </td>
                          {visibleColumns.map(col => (
                            <td
                              key={col.key}
                              className={`table-cell text-sm whitespace-nowrap ${col.width} ${
                                isExpanded ? 'bg-blue-50/70' : i % 2 === 1 ? 'bg-canvas/40' : ''
                              } group-hover:bg-blue-50/50 transition-colors`}
                            >
                              {renderConfigCell(col, row, setEditConfig)}
                            </td>
                          ))}
                          <td className="table-cell w-24" onClick={e => e.stopPropagation()}>
                            <div className="flex items-center gap-1.5 flex-wrap">
                              <FileDownloadButton
                                trackId={row.trackId}
                                filename={row.filename}
                              />
                              {canOperate && RESTARTABLE_STATUSES.has(row.status) && (
                                <button
                                  onClick={() => { if (window.confirm(`Restart transfer ${row.trackId}?`)) restartOneMut.mutate(row.trackId) }}
                                  disabled={restartOneMut.isPending}
                                  className="text-[10px] text-muted hover:text-green-600 px-1.5 py-0.5 rounded hover:bg-green-50 transition-colors whitespace-nowrap"
                                  title="Restart this transfer"
                                >
                                  <PlayIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                  Restart
                                </button>
                              )}
                              {canOperate && (row.status === 'PROCESSING' || row.status === 'IN_PROGRESS' || row.status === 'DOWNLOADED') && (
                                <button
                                  onClick={() => { if (window.confirm(`Terminate transfer ${row.trackId}?`)) terminateOneMut.mutate(row.trackId) }}
                                  disabled={terminateOneMut.isPending}
                                  className="text-[10px] text-muted hover:text-red-600 px-1.5 py-0.5 rounded hover:bg-red-50 transition-colors whitespace-nowrap"
                                  title="Terminate this transfer"
                                >
                                  <StopIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                  Stop
                                </button>
                              )}
                              {canOperate && (row.status === 'PROCESSING' || row.status === 'IN_PROGRESS') && row.flowStatus !== 'PAUSED' && (
                                <button
                                  onClick={() => { if (window.confirm(`Pause transfer ${row.trackId}? It will finish the current step then halt.`)) pauseOneMut.mutate(row.trackId) }}
                                  disabled={pauseOneMut.isPending}
                                  className="text-[10px] text-muted hover:text-amber-600 px-1.5 py-0.5 rounded hover:bg-amber-50 transition-colors whitespace-nowrap"
                                  title="Pause after current step (preserves position)"
                                >
                                  <PauseIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                  Pause
                                </button>
                              )}
                              {canOperate && row.flowStatus === 'PAUSED' && (
                                <button
                                  onClick={() => resumeOneMut.mutate(row.trackId)}
                                  disabled={resumeOneMut.isPending}
                                  className="text-[10px] text-muted hover:text-indigo-600 px-1.5 py-0.5 rounded hover:bg-indigo-50 transition-colors whitespace-nowrap"
                                  title="Resume from saved step"
                                >
                                  <PlayIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                  Resume
                                </button>
                              )}
                              {canOperate && row.status === 'FAILED' && (
                                <button
                                  onClick={() => { setScheduleModal({ trackId: row.trackId, filename: row.filename }); setScheduleInput('') }}
                                  className="text-[10px] text-muted hover:text-blue-600 px-1.5 py-0.5 rounded hover:bg-blue-50 transition-colors whitespace-nowrap"
                                  title="Schedule retry at a specific time"
                                >
                                  <ClockIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                  Schedule
                                </button>
                              )}
                              <button
                                onClick={() => setAiDrawerTrackId(row.trackId)}
                                className="text-[10px] text-muted hover:text-indigo-600 px-1.5 py-0.5 rounded hover:bg-indigo-50 transition-colors whitespace-nowrap"
                                title="Ask the AI copilot about this transfer"
                              >
                                <SparklesIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                AI
                              </button>
                            </div>
                          </td>
                        </tr>
                        {isExpanded && (
                          <tr>
                            <td colSpan={visibleColumns.length + 2} className="p-0 border-b border-blue-200">
                              <TransferDetailPanel row={row} flowExec={flowExecDetail} events={flowEvents} navigate={navigate} onEditConfig={setEditConfig} />
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    )
                  })}
                </tbody>
              </table>
            </div>

            {/* ── Pagination ─────────────────────────────────────── */}
            <div className="flex items-center justify-between px-6 py-4 border-t border-border bg-canvas/40">
              <div className="flex items-center gap-3 text-sm text-secondary">
                <span>
                  Showing <span className="font-medium text-primary">{startItem}</span>
                  {' '}&ndash;{' '}
                  <span className="font-medium text-primary">{endItem}</span>
                  {' '}of{' '}
                  <span className="font-medium text-primary">{totalElements.toLocaleString()}</span>
                  {' '}transfers
                </span>
                <span className="text-gray-300">|</span>
                <label className="flex items-center gap-1.5">
                  <span className="text-xs">Per page:</span>
                  <select
                    value={size}
                    onChange={e => { setSize(+e.target.value); setPage(0) }}
                    className="!w-auto !text-xs !px-2 !py-1"
                  >
                    {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
                  </select>
                </label>
              </div>

              <div className="flex items-center gap-1">
                <button
                  onClick={() => setPage(0)}
                  disabled={page === 0}
                  className="px-2 py-1 text-xs rounded-lg border border-border hover:bg-surface disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  First
                </button>
                <button
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="p-1.5 rounded-lg border border-border hover:bg-surface disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  <ChevronLeftIcon className="w-4 h-4" />
                </button>

                {pageNumbers.map(pn => (
                  <button
                    key={pn}
                    onClick={() => setPage(pn)}
                    className={`px-3 py-1 text-xs rounded-lg border transition-colors ${
                      pn === page
                        ? 'bg-blue-600 text-white border-blue-600 shadow-sm'
                        : 'border-border hover:bg-surface text-primary'
                    }`}
                  >
                    {pn + 1}
                  </button>
                ))}

                <button
                  onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                  className="p-1.5 rounded-lg border border-border hover:bg-surface disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  <ChevronRightIcon className="w-4 h-4" />
                </button>
                <button
                  onClick={() => setPage(totalPages - 1)}
                  disabled={page >= totalPages - 1}
                  className="px-2 py-1 text-xs rounded-lg border border-border hover:bg-surface disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  Last
                </button>
              </div>
            </div>
          </>
        )}
      </div>}

      {/* ── Pipeline Stats Footer (Phase 6) ─────────────────── */}
      <PipelineStatsFooter />

      {/* ── Bulk Restart Confirmation ──────────────────────── */}
      <ConfirmDialog
        open={showBulkConfirm}
        variant="warning"
        title="Confirm Bulk Restart"
        message={`Restart ${selectedTrackIds.size} failed transfer${selectedTrackIds.size !== 1 ? 's' : ''}? This will re-queue the selected transfers for processing. They will be retried from the beginning of their flow pipeline.`}
        evidence={[...selectedTrackIds].join('\n')}
        confirmLabel={`Restart ${selectedTrackIds.size} Transfer${selectedTrackIds.size !== 1 ? 's' : ''}`}
        cancelLabel="Cancel"
        loading={bulkRestartMut.isPending}
        onConfirm={() => bulkRestartMut.mutate([...selectedTrackIds])}
        onCancel={() => setShowBulkConfirm(false)}
      />

      {/* ── Schedule Retry Modal ─────────────────────────────────── */}
      {scheduleModal && (
        <Modal title="Schedule Retry" onClose={() => setScheduleModal(null)}>
          <div className="space-y-4">
            <p className="text-sm text-secondary">
              Schedule a retry for transfer <span className="font-mono font-bold text-blue-600">{scheduleModal.trackId}</span>
              {scheduleModal.filename && <> ({scheduleModal.filename})</>}
            </p>
            <div>
              <label className="text-xs font-medium text-secondary">Retry Date & Time</label>
              <input
                type="datetime-local"
                value={scheduleInput}
                onChange={e => setScheduleInput(e.target.value)}
                className="mt-1 text-sm"
                min={new Date(Date.now() + 60000).toISOString().slice(0, 16)}
              />
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setScheduleModal(null)} className="btn-secondary">Cancel</button>
              <button
                onClick={() => {
                  if (!scheduleInput) return
                  scheduleRetryMut.mutate({ trackId: scheduleModal.trackId, scheduledAt: new Date(scheduleInput).toISOString() })
                }}
                disabled={!scheduleInput || scheduleRetryMut.isPending}
                className="btn-primary flex items-center gap-1.5"
              >
                <ClockIcon className="w-4 h-4" />
                {scheduleRetryMut.isPending ? 'Scheduling...' : 'Schedule Retry'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Column Settings Panel (Slide-out) ────────────────────── */}
      {settingsOpen && (
        <>
          {/* Backdrop */}
          <div
            className="fixed inset-0 bg-black/20 backdrop-blur-sm z-40 transition-opacity"
            onClick={() => setSettingsOpen(false)}
          />
          {/* Panel */}
          <div className="fixed top-0 right-0 h-full w-80 bg-surface shadow-2xl z-50 flex flex-col animate-slide-in-right">
            <div className="flex items-center justify-between p-5 border-b border-border">
              <div>
                <h2 className="text-base font-semibold text-primary">Column Settings</h2>
                <p className="text-xs text-secondary mt-0.5">{visibleKeys.length} of {getAllColumns().length} columns visible</p>
              </div>
              <button
                onClick={() => setSettingsOpen(false)}
                className="p-1.5 hover:bg-hover rounded-lg transition-colors"
              >
                <XMarkIcon className="w-5 h-5 text-secondary" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-5">
              <div className="space-y-1">
                {getAllColumns().map(col => (
                  <label
                    key={col.key}
                    className="flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-canvas cursor-pointer transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={visibleKeys.includes(col.key)}
                      onChange={() => toggle(col.key)}
                      className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500/40 focus:ring-offset-0 cursor-pointer"
                    />
                    <div className="flex-1 min-w-0">
                      <span className="text-sm font-medium text-primary">{col.label}</span>
                      {col.defaultVisible && (
                        <span className="ml-1.5 text-xs text-muted">(default)</span>
                      )}
                    </div>
                  </label>
                ))}
              </div>
            </div>

            <div className="p-5 border-t border-border">
              <button
                onClick={resetToDefaults}
                className="w-full btn-secondary justify-center text-xs"
              >
                <ArrowPathIcon className="w-3.5 h-3.5" />
                Reset to Defaults
              </button>
            </div>
          </div>
        </>
      )}

      {/* Execution Detail Drawer — triggered by double-clicking a row */}
      <ExecutionDetailDrawer
        trackId={drawerTrackId}
        open={!!drawerTrackId}
        onClose={() => setDrawerTrackId(null)}
        showActions
      />

      {/* R108 AI Copilot Drawer — triggered by clicking the AI button on a row */}
      <AICopilotDrawer
        trackId={aiDrawerTrackId}
        open={!!aiDrawerTrackId}
        onClose={() => setAiDrawerTrackId(null)}
        onActionCompleted={() => qc.invalidateQueries(['activity-monitor'])}
      />

      {/* Inline Config Editor — triggered by clicking any config link */}
      {editConfig && (
        <ConfigInlineEditor
          open={!!editConfig}
          onClose={() => setEditConfig(null)}
          configType={editConfig.type}
          configId={editConfig.id}
          configName={editConfig.name}
        />
      )}

      {/* Slide-in animation via inline style tag */}
      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to { transform: translateX(0); }
        }
        .animate-slide-in-right {
          animation: slideInRight 0.2s ease-out;
        }
      `}</style>
    </div>
  )
}

// ── FabricMiniCard — compact KPI shown in the Activity Monitor context strip ──
function FabricMiniCard({ label, value, hint, to, color }) {
  return (
    <Link
      to={to}
      title={hint}
      className="flex items-center justify-between rounded-lg p-2.5 transition-colors hover:bg-surface-hover"
      style={{ border: '1px solid rgb(var(--border))' }}
    >
      <div>
        <div className="text-[10px] uppercase tracking-wide font-semibold" style={{ color: 'rgb(148, 163, 184)' }}>
          {label}
        </div>
        <div className="text-xl font-bold mt-0.5" style={{ color }}>{value}</div>
      </div>
      <svg className="w-4 h-4 opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
      </svg>
    </Link>
  )
}

/** Phase 6: Pipeline stats footer — shows processing throughput below Activity Monitor. */
function PipelineStatsFooter() {
  const { data } = useQuery({
    queryKey: ['pipeline-health-footer'],
    queryFn: () => onboardingApi.get('/api/pipeline/health').then(r => r.data).catch(() => null),
    meta: { silent: true }, refetchInterval: 15000,
    retry: false,
  })
  if (!data) return null
  const rules = data.ruleEngine || {}
  const writers = data.batchWriters || {}
  const items = [
    { label: 'Rules Active', value: rules.ruleCount },
    { label: 'Matched', value: rules.totalMatches?.toLocaleString() },
    { label: 'Unmatched', value: rules.totalUnmatched },
    { label: 'Record Buffer', value: writers.records?.pending || 0 },
    { label: 'Records Flushed', value: (writers.records?.flushed || 0).toLocaleString() },
    { label: 'Snapshot Buffer', value: writers.snapshots?.pending || 0 },
    { label: 'Partner Cache', value: (data.partnerCache?.size || 0) + ' entries' },
  ].filter(i => i.value !== undefined && i.value !== null)

  return (
    <div className="flex items-center gap-4 px-4 py-2 text-[10px] opacity-50 border-t border-border bg-canvas/30 rounded-b-lg overflow-x-auto">
      <span className="font-medium">Pipeline:</span>
      {items.map(i => (
        <span key={i.label}>{i.label}: <span className="font-semibold">{i.value}</span></span>
      ))}
    </div>
  )
}
