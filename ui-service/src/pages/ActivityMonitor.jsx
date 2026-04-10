import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react'
import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { onboardingApi, configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  MagnifyingGlassIcon, ArrowDownTrayIcon, ChevronLeftIcon, ChevronRightIcon,
  Cog6ToothIcon, XMarkIcon, ArrowPathIcon, ChevronUpIcon, ChevronDownIcon,
  FunnelIcon, TableCellsIcon, CheckCircleIcon, XCircleIcon, ClockIcon,
  DocumentTextIcon, ShieldCheckIcon, TruckIcon, ArrowRightIcon,
} from '@heroicons/react/24/outline'

// ── Column Definitions ──────────────────────────────────────────────────
const ALL_COLUMNS = [
  { key: 'trackId', label: 'Track ID', defaultVisible: true, width: 'w-32', render: (v) => <span className="font-mono text-xs font-bold text-blue-600">{v || '--'}</span> },
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
]

const DEFAULT_VISIBLE_KEYS = ALL_COLUMNS.filter(c => c.defaultVisible).map(c => c.key)
const STATUS_OPTIONS = ['ALL', 'PENDING', 'IN_OUTBOX', 'DOWNLOADED', 'MOVED_TO_SENT', 'FAILED']
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
    return DEFAULT_VISIBLE_KEYS
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
    setAndPersist(DEFAULT_VISIBLE_KEYS)
  }, [setAndPersist])

  const visibleColumns = useMemo(() =>
    ALL_COLUMNS.filter(c => visibleKeys.includes(c.key)),
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
function TransferDetailPanel({ row, flowExec, events, navigate }) {
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
            <div className="flex justify-between">
              <span className="text-secondary">Source</span>
              <span className="text-primary">{row.sourceUsername || '--'} {row.sourcePartnerName ? `(${row.sourcePartnerName})` : ''}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-secondary">Destination</span>
              <span className="text-primary">{row.destUsername || row.destPartnerName || row.externalDestName || '--'}</span>
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
              <div className="flex justify-between pt-1 border-t border-border">
                <span className="text-secondary">Flow</span>
                <span className="text-primary">{row.flowName}</span>
              </div>
            )}
            {row.flowStatus && (
              <div className="flex justify-between">
                <span className="text-secondary">Flow Status</span>
                <span className="text-primary">{row.flowStatus}</span>
              </div>
            )}
            {row.externalDestName && (
              <div className="flex justify-between">
                <span className="text-secondary">External Dest</span>
                <span className="text-primary">{row.externalDestName}</span>
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
  const { visibleKeys, visibleColumns, toggle, resetToDefaults } = useColumnPreferences()

  // Pagination & sort state
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [sortBy, setSortBy] = useState('uploadedAt')
  const [sortDir, setSortDir] = useState('DESC')

  // Filters
  const [filenameFilter, setFilenameFilter] = useState('')
  const [trackIdFilter, setTrackIdFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [sourceUserFilter, setSourceUserFilter] = useState('')
  const [protocolFilter, setProtocolFilter] = useState('ALL')

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
    queryFn: () => onboardingApi.get('/api/activity-monitor', { params: queryParams }).then(r => r.data).catch(() => ({ content: [], totalElements: 0, totalPages: 0 })),
    placeholderData: keepPreviousData,
    refetchInterval: autoRefresh ? 30000 : false,
  })

  const rows = data?.content || []
  const totalElements = data?.totalElements || 0
  const totalPages = data?.totalPages || 0

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
    queryFn: () => configApi.get(`/api/flow-executions/${expandedTrackId}`).then(r => r.data).catch(() => null),
    enabled: !!expandedTrackId,
    staleTime: 10000,
  })

  const { data: flowEvents = [] } = useQuery({
    queryKey: ['flow-events', expandedTrackId],
    queryFn: () => configApi.get(`/api/flow-executions/flow-events/${expandedTrackId}`).then(r => r.data).catch(() => []),
    enabled: !!expandedTrackId,
    staleTime: 10000,
  })

  // ── Scheduled Retries ─────────────────────────────────────────────────
  const { data: scheduledRetries = [], isLoading: loadingRetries } = useQuery({
    queryKey: ['activity-scheduled-retries'],
    queryFn: () => onboardingApi.get('/api/flow-executions/scheduled-retries').then(r => r.data).catch(() => []),
    refetchInterval: 60000
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
  }

  const hasFilters = filenameFilter || trackIdFilter || statusFilter !== 'ALL' || sourceUserFilter || protocolFilter !== 'ALL'

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
      {/* ── Header ───────────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-primary">Activity Monitor</h1>
            {totalElements > 0 && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-blue-50 text-blue-700 text-xs font-semibold rounded-full ring-1 ring-inset ring-blue-600/10">
                {totalElements.toLocaleString()} transfers
              </span>
            )}
            {isFetching && !isLoading && (
              <div className="w-4 h-4 border-2 border-blue-400 border-t-transparent rounded-full animate-spin" />
            )}
          </div>
          <p className="text-secondary text-sm mt-0.5">Monitor all file transfers across the platform</p>
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
                title="Refresh now">
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
                        <td className="table-cell text-sm text-secondary">{r.flowName || '--'}</td>
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
          <div className="flex items-center gap-1.5 text-muted">
            <FunnelIcon className="w-4 h-4" />
            <span className="text-xs font-medium uppercase tracking-wide">Filters</span>
          </div>

          {/* Filename */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted pointer-events-none" />
            <input
              className="!w-48 !py-1.5 !pl-8 !pr-3 !text-xs"
              placeholder="Filename..."
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
        {isLoading ? (
          <div className="p-12">
            <LoadingSpinner text="Loading transfers..." />
          </div>
        ) : rows.length === 0 ? (
          /* Empty state */
          <div className="flex flex-col items-center justify-center py-20 px-6">
            <div className="w-16 h-16 bg-hover rounded-2xl flex items-center justify-center mb-4">
              <TableCellsIcon className="w-8 h-8 text-muted" />
            </div>
            <h3 className="text-base font-semibold text-primary mb-1">No transfers found</h3>
            <p className="text-sm text-secondary text-center max-w-sm">
              {hasFilters
                ? 'No transfers match your current filters. Try adjusting or clearing them.'
                : 'File transfers will appear here once they are processed by the platform.'}
            </p>
            {hasFilters && (
              <button onClick={clearFilters} className="btn-secondary mt-4 text-xs">
                <XMarkIcon className="w-3.5 h-3.5" /> Clear Filters
              </button>
            )}
          </div>
        ) : (
          <>
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
                          className={`table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)] group ${isExpanded ? 'bg-blue-50/70' : ''} ${isSelected ? 'bg-red-50/50' : ''}`}
                          onClick={() => handleRowClick(row)}
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
                              {col.render(row[col.key], row)}
                            </td>
                          ))}
                          <td className="table-cell w-24" onClick={e => e.stopPropagation()}>
                            {row.status === 'FAILED' && (
                              <button
                                onClick={() => { setScheduleModal({ trackId: row.trackId, filename: row.filename }); setScheduleInput('') }}
                                className="text-[10px] text-muted hover:text-blue-600 px-1.5 py-0.5 rounded hover:bg-blue-50 transition-colors whitespace-nowrap"
                                title="Schedule retry at a specific time"
                              >
                                <ClockIcon className="w-3.5 h-3.5 inline mr-0.5" />
                                Schedule
                              </button>
                            )}
                          </td>
                        </tr>
                        {isExpanded && (
                          <tr>
                            <td colSpan={visibleColumns.length + 2} className="p-0 border-b border-blue-200">
                              <TransferDetailPanel row={row} flowExec={flowExecDetail} events={flowEvents} navigate={navigate} />
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

      {/* ── Bulk Restart Confirmation Modal ──────────────────────── */}
      {showBulkConfirm && (
        <Modal title="Confirm Bulk Restart" onClose={() => setShowBulkConfirm(false)}>
          <div className="space-y-4">
            <div className="flex items-start gap-3 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
              <ArrowPathIcon className="w-5 h-5 text-yellow-600 mt-0.5 flex-shrink-0" />
              <div>
                <p className="text-sm font-medium text-yellow-800">
                  Restart {selectedTrackIds.size} failed transfer{selectedTrackIds.size !== 1 ? 's' : ''}?
                </p>
                <p className="text-sm text-yellow-700 mt-1">
                  This will re-queue the selected transfers for processing. They will be retried from the beginning of their flow pipeline.
                </p>
              </div>
            </div>
            <div className="max-h-40 overflow-y-auto bg-canvas rounded-lg p-3">
              <p className="text-xs font-medium text-secondary mb-1">Selected Track IDs:</p>
              <div className="flex flex-wrap gap-1">
                {[...selectedTrackIds].map(id => (
                  <span key={id} className="font-mono text-xs bg-red-50 text-red-700 px-2 py-0.5 rounded border border-red-200">{id}</span>
                ))}
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setShowBulkConfirm(false)} className="btn-secondary">Cancel</button>
              <button
                onClick={() => bulkRestartMut.mutate([...selectedTrackIds])}
                disabled={bulkRestartMut.isPending}
                className="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-semibold rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-60 transition-colors"
              >
                <ArrowPathIcon className={`w-4 h-4 ${bulkRestartMut.isPending ? 'animate-spin' : ''}`} />
                {bulkRestartMut.isPending ? 'Restarting...' : `Restart ${selectedTrackIds.size} Transfer${selectedTrackIds.size !== 1 ? 's' : ''}`}
              </button>
            </div>
          </div>
        </Modal>
      )}

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
                <p className="text-xs text-secondary mt-0.5">{visibleKeys.length} of {ALL_COLUMNS.length} columns visible</p>
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
                {ALL_COLUMNS.map(col => (
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
