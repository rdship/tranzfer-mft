import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import * as quarantineApi from '../api/quarantine'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  ShieldCheckIcon, ShieldExclamationIcon, TrashIcon,
  MagnifyingGlassIcon, ChevronDownIcon, ChevronUpIcon,
  ExclamationTriangleIcon, LockOpenIcon, FunnelIcon,
  BugAntIcon, EyeSlashIcon, NoSymbolIcon, DocumentIcon
} from '@heroicons/react/24/outline'

// ── Helpers ─────────────────────────────────────────────────────────────

const reasonBadge = (reason) => {
  switch (reason?.toUpperCase()) {
    case 'MALWARE':   return 'badge-red'
    case 'DLP':       return 'badge-orange'
    case 'SANCTIONS': return 'badge-yellow'
    default:          return 'badge-gray'
  }
}

const detectorBadge = (detector) => {
  switch (detector?.toUpperCase()) {
    case 'AV':        return 'badge-red'
    case 'DLP':       return 'badge-orange'
    case 'SANCTIONS': return 'badge-purple'
    default:          return 'badge-gray'
  }
}

const formatBytes = (bytes) => {
  if (!bytes && bytes !== 0) return '-'
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

const REASON_FILTERS = ['ALL', 'MALWARE', 'DLP', 'SANCTIONS']

const REASON_ICONS = {
  MALWARE:   BugAntIcon,
  DLP:       EyeSlashIcon,
  SANCTIONS: NoSymbolIcon,
}

// ── Main Component ──────────────────────────────────────────────────────

export default function Quarantine() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [reasonFilter, setReasonFilter] = useState('ALL')
  const [expandedRow, setExpandedRow] = useState(null)
  const [confirmAction, setConfirmAction] = useState(null) // { id, type: 'release'|'delete' }
  const [releaseReason, setReleaseReason] = useState('')
  const [selected, setSelected] = useState(new Set())
  const [showBulkConfirm, setShowBulkConfirm] = useState(null) // 'release' | 'delete'
  const [bulkReleaseReason, setBulkReleaseReason] = useState('')

  // ── Queries ──

  const { data, isLoading } = useQuery({
    queryKey: ['quarantine-files', page],
    queryFn: () => quarantineApi.getQuarantinedFiles(page, 20),
    refetchInterval: 15000,
  })

  const { data: stats } = useQuery({
    queryKey: ['quarantine-stats'],
    queryFn: quarantineApi.getQuarantineStats,
    refetchInterval: 30000,
  })

  const files = data?.content || data || []
  const totalPages = data?.totalPages || 0
  const totalElements = data?.totalElements ?? files.length

  // ── Mutations ──

  const release = useMutation({
    mutationFn: quarantineApi.releaseFromQuarantine,
    onSuccess: () => {
      toast.success('File released from quarantine')
      queryClient.invalidateQueries({ queryKey: ['quarantine-files'] })
      queryClient.invalidateQueries({ queryKey: ['quarantine-stats'] })
      setConfirmAction(null)
      setReleaseReason('')
    },
    onError: (err) => {
      toast.error(err?.response?.data?.message || 'Release failed')
      setConfirmAction(null)
    },
  })

  const deleteFile = useMutation({
    mutationFn: quarantineApi.deleteFromQuarantine,
    onSuccess: () => {
      toast.success('File permanently deleted')
      queryClient.invalidateQueries({ queryKey: ['quarantine-files'] })
      queryClient.invalidateQueries({ queryKey: ['quarantine-stats'] })
      setConfirmAction(null)
    },
    onError: (err) => {
      toast.error(err?.response?.data?.message || 'Delete failed')
      setConfirmAction(null)
    },
  })

  // ── Filtering ──

  const filtered = files
    .filter(f => reasonFilter === 'ALL' || f.reason?.toUpperCase() === reasonFilter)
    .filter(f => {
      if (!search.trim()) return true
      const q = search.toLowerCase()
      return (
        (f.filename || '').toLowerCase().includes(q) ||
        (f.trackId || '').toLowerCase().includes(q)
      )
    })

  // ── Bulk selection helpers ──

  const toggleSelect = (id) => setSelected(s => {
    const n = new Set(s)
    n.has(id) ? n.delete(id) : n.add(id)
    return n
  })
  const toggleSelectAll = () => {
    if (selected.size === filtered.length) setSelected(new Set())
    else setSelected(new Set(filtered.map(f => f.id)))
  }

  const handleBulkAction = async (action) => {
    const ids = [...selected]
    let results
    if (action === 'release') {
      results = await Promise.allSettled(
        ids.map(id => quarantineApi.releaseFromQuarantine(id))
      )
    } else {
      results = await Promise.allSettled(
        ids.map(id => quarantineApi.deleteFromQuarantine(id))
      )
    }
    const succeeded = results.filter(r => r.status === 'fulfilled').length
    const failed = results.filter(r => r.status === 'rejected').length
    const label = action === 'release' ? 'released' : 'deleted'
    if (failed === 0) toast.success(`${succeeded} file${succeeded !== 1 ? 's' : ''} ${label}`)
    else toast.error(`${succeeded} of ${ids.length} files ${label}, ${failed} failed`)
    setSelected(new Set())
    setShowBulkConfirm(null)
    setBulkReleaseReason('')
    queryClient.invalidateQueries({ queryKey: ['quarantine-files'] })
    queryClient.invalidateQueries({ queryKey: ['quarantine-stats'] })
  }

  // ── Stats cards ──

  const statCards = [
    {
      label: 'Total Quarantined',
      value: stats?.total ?? totalElements,
      icon: ShieldExclamationIcon,
      color: 'text-accent',
      bgColor: 'bg-accent/10',
    },
    {
      label: 'Malware Detected',
      value: stats?.malware ?? 0,
      icon: BugAntIcon,
      color: 'text-red-400',
      bgColor: 'bg-red-500/10',
    },
    {
      label: 'DLP Violations',
      value: stats?.dlp ?? 0,
      icon: EyeSlashIcon,
      color: 'text-orange-400',
      bgColor: 'bg-orange-500/10',
    },
    {
      label: 'Sanctions Hits',
      value: stats?.sanctions ?? 0,
      icon: NoSymbolIcon,
      color: 'text-yellow-400',
      bgColor: 'bg-yellow-500/10',
    },
  ]

  // ── Render ────────────────────────────────────────────────────────────

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <ShieldExclamationIcon className="w-6 h-6 text-accent" />
        <div>
          <h1 className="page-title">Quarantine Manager</h1>
          <p className="text-sm text-secondary mt-0.5">Files blocked by screening rules awaiting review</p>
        </div>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {statCards.map(({ label, value, icon: Icon, color, bgColor }) => (
          <div key={label} className="card flex items-center gap-4">
            <div className={`p-2.5 rounded-xl ${bgColor}`}>
              <Icon className={`w-5 h-5 ${color}`} />
            </div>
            <div>
              <p className="text-xs font-medium uppercase tracking-wider text-muted">{label}</p>
              <p className={`text-2xl font-bold font-mono ${color}`}>{value}</p>
            </div>
          </div>
        ))}
      </div>

      {/* Filter bar */}
      <div className="flex items-center gap-4 flex-wrap">
        {/* Reason filter tabs */}
        <div className="flex items-center gap-1 bg-surface border border-border rounded-lg p-1">
          {REASON_FILTERS.map((reason) => {
            const Icon = REASON_ICONS[reason]
            return (
              <button
                key={reason}
                onClick={() => { setReasonFilter(reason); setPage(0) }}
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all ${
                  reasonFilter === reason
                    ? 'bg-accent/15 text-accent'
                    : 'text-muted hover:text-primary hover:bg-hover'
                }`}
              >
                {Icon && <Icon className="w-3.5 h-3.5" />}
                {reason}
              </button>
            )
          })}
        </div>

        {/* Search */}
        <div className="relative flex-1 min-w-[250px]">
          <MagnifyingGlassIcon className="w-4 h-4 text-muted absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by filename or track ID..."
            className="w-full bg-surface border border-border rounded-lg pl-10 pr-4 py-2.5 text-sm text-primary placeholder-muted focus:outline-none focus:border-accent transition-colors"
          />
        </div>
      </div>

      {/* Loading state */}
      {isLoading && (
        <div className="flex flex-col items-center justify-center py-12 gap-3">
          <div className="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-secondary">Loading quarantined files...</p>
        </div>
      )}

      {/* Empty state */}
      {!isLoading && filtered.length === 0 && (
        <div className="card flex flex-col items-center justify-center py-16 text-center">
          <ShieldCheckIcon className="w-12 h-12 text-green-400 mb-4" />
          <h3 className="text-base font-semibold text-primary mb-1">No files in quarantine</h3>
          <p className="text-sm text-secondary">
            {search || reasonFilter !== 'ALL'
              ? 'No files match your current filters'
              : 'All files have passed screening checks'}
          </p>
        </div>
      )}

      {/* Bulk Action Bar */}
      {selected.size > 0 && (
        <div className="flex items-center gap-3 bg-[rgba(100,140,255,0.1)] border border-[rgba(100,140,255,0.2)] rounded-xl px-4 py-2">
          <span className="text-sm font-medium">{selected.size} selected</span>
          <button className="btn-secondary text-sm" onClick={() => setShowBulkConfirm('release')}>Release All</button>
          <button className="text-sm text-red-400 hover:text-red-300 font-medium" onClick={() => setShowBulkConfirm('delete')}>Delete All</button>
          <button className="text-sm text-red-400 hover:text-red-300" onClick={() => setSelected(new Set())}>Clear</button>
        </div>
      )}

      {/* Table */}
      {!isLoading && filtered.length > 0 && (
        <div className="card !p-0 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted text-xs uppercase tracking-wider">
                <th className="px-4 py-3 text-left w-8"><input type="checkbox" checked={selected.size === filtered.length && filtered.length > 0} onChange={toggleSelectAll} /></th>
                <th className="px-4 py-3 text-left w-8"></th>
                <th className="px-4 py-3 text-left">Filename</th>
                <th className="px-4 py-3 text-left">Size</th>
                <th className="px-4 py-3 text-left">Reason</th>
                <th className="px-4 py-3 text-left">Detected By</th>
                <th className="px-4 py-3 text-left">Quarantined At</th>
                <th className="px-4 py-3 text-left">Account</th>
                <th className="px-4 py-3 text-left">Track ID</th>
                <th className="px-4 py-3 text-left">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {filtered.map((file) => (
                <FileRow
                  key={file.id}
                  file={file}
                  isSelected={selected.has(file.id)}
                  onSelectToggle={() => toggleSelect(file.id)}
                  expanded={expandedRow === file.id}
                  onToggle={() => setExpandedRow(expandedRow === file.id ? null : file.id)}
                  confirmAction={confirmAction}
                  onConfirmAction={setConfirmAction}
                  releaseReason={releaseReason}
                  onReleaseReasonChange={setReleaseReason}
                  onRelease={(id) => release.mutate(id)}
                  onDelete={(id) => deleteFile.mutate(id)}
                  releasePending={release.isPending}
                  deletePending={deleteFile.isPending}
                />
              ))}
            </tbody>
          </table>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-3 py-3 border-t border-border">
              <button
                disabled={page === 0}
                onClick={() => setPage(p => p - 1)}
                className="btn-secondary text-xs"
              >
                Previous
              </button>
              <span className="text-muted text-xs">
                Page {page + 1} of {totalPages}
              </span>
              <button
                disabled={page >= totalPages - 1}
                onClick={() => setPage(p => p + 1)}
                className="btn-secondary text-xs"
              >
                Next
              </button>
            </div>
          )}
        </div>
      )}

      {/* Bulk Release Modal */}
      {showBulkConfirm === 'release' && (
        <Modal title="Bulk Release from Quarantine" onClose={() => { setShowBulkConfirm(null); setBulkReleaseReason('') }}>
          <div className="space-y-4">
            <p className="text-secondary">
              Release <strong>{selected.size}</strong> file{selected.size !== 1 ? 's' : ''} from quarantine.
            </p>
            <div>
              <label className="text-xs font-medium text-secondary">Release reason (required)</label>
              <input
                type="text"
                value={bulkReleaseReason}
                onChange={e => setBulkReleaseReason(e.target.value)}
                placeholder="e.g. False positive, manually verified"
                className="w-full bg-canvas border border-border rounded px-3 py-2 text-sm text-primary placeholder-muted focus:outline-none focus:border-accent mt-1"
                autoFocus
              />
            </div>
            <div className="flex gap-3 justify-end">
              <button className="btn-secondary" onClick={() => { setShowBulkConfirm(null); setBulkReleaseReason('') }}>Cancel</button>
              <button className="btn-primary" disabled={!bulkReleaseReason.trim()} onClick={() => handleBulkAction('release')}>
                Release {selected.size} File{selected.size !== 1 ? 's' : ''}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* Bulk Delete Modal */}
      {showBulkConfirm === 'delete' && (
        <Modal title="Bulk Delete from Quarantine" onClose={() => setShowBulkConfirm(null)}>
          <p className="text-secondary mb-4">
            Permanently delete <strong>{selected.size}</strong> file{selected.size !== 1 ? 's' : ''} from quarantine? This action cannot be undone.
          </p>
          <div className="flex gap-3 justify-end">
            <button className="btn-secondary" onClick={() => setShowBulkConfirm(null)}>Cancel</button>
            <button className="btn-primary bg-red-600 hover:bg-red-700" onClick={() => handleBulkAction('delete')}>
              Delete {selected.size} File{selected.size !== 1 ? 's' : ''}
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

// ── File Row ────────────────────────────────────────────────────────────

function FileRow({
  file, isSelected, onSelectToggle, expanded, onToggle, confirmAction, onConfirmAction,
  releaseReason, onReleaseReasonChange,
  onRelease, onDelete, releasePending, deletePending,
}) {
  const isConfirming = confirmAction?.id === file.id

  return (
    <>
      <tr
        className="hover:bg-hover cursor-pointer transition-colors"
        onClick={onToggle}
      >
        <td className="px-4 py-3" onClick={e => e.stopPropagation()}>
          <input type="checkbox" checked={isSelected} onChange={onSelectToggle} />
        </td>
        <td className="px-4 py-3">
          {expanded
            ? <ChevronUpIcon className="w-4 h-4 text-muted" />
            : <ChevronDownIcon className="w-4 h-4 text-muted" />
          }
        </td>
        <td className="px-4 py-3">
          <div className="flex items-center gap-2">
            <DocumentIcon className="w-4 h-4 text-muted flex-shrink-0" />
            <span className="text-primary truncate max-w-[200px]" title={file.filename}>
              {file.filename || '-'}
            </span>
          </div>
        </td>
        <td className="px-4 py-3 font-mono text-xs text-secondary">
          {formatBytes(file.size)}
        </td>
        <td className="px-4 py-3">
          <span className={`badge ${reasonBadge(file.reason)}`}>
            {file.reason || 'UNKNOWN'}
          </span>
        </td>
        <td className="px-4 py-3">
          <span className={`badge ${detectorBadge(file.detectedBy)}`}>
            {file.detectedBy || '-'}
          </span>
        </td>
        <td className="px-4 py-3 text-secondary text-xs">
          {file.quarantinedAt ? format(new Date(file.quarantinedAt), 'MMM dd, HH:mm:ss') : '-'}
        </td>
        <td className="px-4 py-3 text-secondary text-xs">
          {file.account || file.accountName || '-'}
        </td>
        <td className="px-4 py-3 font-mono text-xs text-secondary">
          {file.trackId ? file.trackId.substring(0, 12) + '...' : '-'}
        </td>
        <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
          {isConfirming ? (
            <ConfirmPanel
              type={confirmAction.type}
              releaseReason={releaseReason}
              onReleaseReasonChange={onReleaseReasonChange}
              onConfirm={() => confirmAction.type === 'release' ? onRelease(file.id) : onDelete(file.id)}
              onCancel={() => { onConfirmAction(null); onReleaseReasonChange('') }}
              pending={releasePending || deletePending}
            />
          ) : (
            <div className="flex items-center gap-2">
              <button
                onClick={() => onConfirmAction({ id: file.id, type: 'release' })}
                className="btn-secondary text-xs !px-2 !py-1 hover:!border-green-500/50 hover:!text-green-400"
                title="Release from quarantine"
                aria-label="Release from quarantine"
              >
                <LockOpenIcon className="w-3.5 h-3.5" />
                Release
              </button>
              <button
                onClick={() => onConfirmAction({ id: file.id, type: 'delete' })}
                className="btn-secondary text-xs !px-2 !py-1 hover:!border-red-500/50 hover:!text-red-400"
                title="Delete permanently"
                aria-label="Delete permanently"
              >
                <TrashIcon className="w-3.5 h-3.5" />
                Delete
              </button>
            </div>
          )}
        </td>
      </tr>

      {/* Expanded screening details */}
      {expanded && (
        <tr>
          <td colSpan={10} className="px-4 py-4 bg-canvas/50">
            <ScreeningDetails file={file} />
          </td>
        </tr>
      )}
    </>
  )
}

// ── Confirm Panel ───────────────────────────────────────────────────────

function ConfirmPanel({ type, releaseReason, onReleaseReasonChange, onConfirm, onCancel, pending }) {
  if (type === 'release') {
    return (
      <div className="space-y-2 min-w-[220px]">
        <p className="text-xs text-secondary">Release reason:</p>
        <input
          type="text"
          value={releaseReason}
          onChange={(e) => onReleaseReasonChange(e.target.value)}
          placeholder="e.g. False positive, manually verified"
          className="w-full bg-canvas border border-border rounded px-2 py-1.5 text-xs text-primary placeholder-muted focus:outline-none focus:border-accent"
          autoFocus
        />
        <div className="flex items-center gap-2">
          <button
            onClick={onConfirm}
            disabled={pending || !releaseReason.trim()}
            className="text-xs px-2.5 py-1 bg-green-600/20 text-green-400 rounded hover:bg-green-600/30 transition-colors disabled:opacity-50"
          >
            {pending ? 'Releasing...' : 'Confirm Release'}
          </button>
          <button onClick={onCancel} className="text-xs px-2 py-1 text-muted hover:text-primary transition-colors">
            Cancel
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex items-center gap-2">
      <span className="text-xs text-red-400">Delete permanently?</span>
      <button
        onClick={onConfirm}
        disabled={pending}
        className="text-xs px-2 py-0.5 bg-red-600/20 text-red-400 rounded hover:bg-red-600/30 transition-colors disabled:opacity-50"
      >
        {pending ? 'Deleting...' : 'Yes'}
      </button>
      <button onClick={onCancel} className="text-xs px-2 py-0.5 text-muted hover:text-primary transition-colors">
        No
      </button>
    </div>
  )
}

// ── Screening Details (expanded row) ────────────────────────────────────

function ScreeningDetails({ file }) {
  const details = file.screeningDetails || file.details

  return (
    <div className="space-y-4">
      {/* Summary row */}
      <div className="flex items-center gap-6 text-xs">
        <div>
          <span className="text-muted">Full Track ID: </span>
          <span className="font-mono text-secondary">{file.trackId || '-'}</span>
        </div>
        <div>
          <span className="text-muted">Account: </span>
          <span className="text-secondary">{file.account || file.accountName || '-'}</span>
        </div>
        <div>
          <span className="text-muted">Original Path: </span>
          <span className="font-mono text-secondary">{file.originalPath || file.path || '-'}</span>
        </div>
      </div>

      {/* Rule details */}
      {details && (
        <div className="bg-surface rounded-lg border border-border p-4 space-y-3">
          <h4 className="text-xs font-semibold text-muted uppercase tracking-wider">Screening Evidence</h4>

          {details.ruleName && (
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted">Triggered Rule:</span>
              <span className="badge badge-red">{details.ruleName}</span>
            </div>
          )}

          {details.matchedPatterns && details.matchedPatterns.length > 0 && (
            <div>
              <p className="text-xs text-muted mb-1">Matched Patterns:</p>
              <div className="flex flex-wrap gap-1">
                {details.matchedPatterns.map((p, i) => (
                  <span key={i} className="badge badge-orange">{p}</span>
                ))}
              </div>
            </div>
          )}

          {details.threatName && (
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted">Threat:</span>
              <span className="text-sm text-red-400">{details.threatName}</span>
            </div>
          )}

          {details.sanctionsEntity && (
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted">Sanctions Entity:</span>
              <span className="text-sm text-yellow-400">{details.sanctionsEntity}</span>
            </div>
          )}

          {details.confidence !== undefined && (
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted">Confidence:</span>
              <div className="flex items-center gap-2">
                <div className="w-24 h-1.5 bg-border rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      details.confidence >= 0.8 ? 'bg-red-500' :
                      details.confidence >= 0.5 ? 'bg-yellow-500' : 'bg-green-500'
                    }`}
                    style={{ width: `${(details.confidence * 100)}%` }}
                  />
                </div>
                <span className="text-xs font-mono text-secondary">
                  {(details.confidence * 100).toFixed(0)}%
                </span>
              </div>
            </div>
          )}

          {details.evidence && (
            <div>
              <p className="text-xs text-muted mb-1">Evidence:</p>
              <pre className="bg-canvas rounded-lg p-3 text-xs font-mono text-secondary overflow-auto max-h-48 border border-border">
                {typeof details.evidence === 'string' ? details.evidence : JSON.stringify(details.evidence, null, 2)}
              </pre>
            </div>
          )}
        </div>
      )}

      {/* Fallback if no structured details */}
      {!details && (
        <div className="bg-surface rounded-lg border border-border p-4">
          <p className="text-xs text-muted">No detailed screening evidence available for this file.</p>
        </div>
      )}
    </div>
  )
}
