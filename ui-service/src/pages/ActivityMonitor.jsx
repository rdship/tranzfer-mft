import { useState, useEffect, useMemo, useCallback, useRef } from 'react'
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { format } from 'date-fns'
import {
  MagnifyingGlassIcon, ArrowDownTrayIcon, ChevronLeftIcon, ChevronRightIcon,
  Cog6ToothIcon, XMarkIcon, ArrowPathIcon, ChevronUpIcon, ChevronDownIcon,
  FunnelIcon, TableCellsIcon
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
  { key: 'integrityStatus', label: 'Integrity', defaultVisible: false, width: 'w-28', render: (v) => v === 'VERIFIED' ? <span className="badge badge-green">{v}</span> : v === 'MISMATCH' ? <span className="badge badge-red">{v}</span> : <span className="text-gray-400">{v || '--'}</span> },
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
  if (!status) return <span className="text-gray-400">--</span>
  return <span className={`badge ${map[status] || 'badge-gray'}`}>{status.replace(/_/g, ' ')}</span>
}

function formatTimestamp(ts) {
  if (!ts) return <span className="text-gray-400">--</span>
  try {
    return <span className="text-xs text-gray-500 font-mono">{format(new Date(ts), 'MMM dd, yyyy HH:mm:ss')}</span>
  } catch {
    return <span className="text-gray-400">--</span>
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

// ── Main Component ──────────────────────────────────────────────────────
export default function ActivityMonitor() {
  const navigate = useNavigate()
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

  // Row click → Journey
  const handleRowClick = (row) => {
    if (row.trackId) {
      navigate(`/journey?trackId=${encodeURIComponent(row.trackId)}`)
    }
  }

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
            <h1 className="text-2xl font-bold text-gray-900">Activity Monitor</h1>
            {totalElements > 0 && (
              <span className="inline-flex items-center gap-1 px-2.5 py-1 bg-blue-50 text-blue-700 text-xs font-semibold rounded-full ring-1 ring-inset ring-blue-600/10">
                {totalElements.toLocaleString()} transfers
              </span>
            )}
            {isFetching && !isLoading && (
              <div className="w-4 h-4 border-2 border-blue-400 border-t-transparent rounded-full animate-spin" />
            )}
          </div>
          <p className="text-gray-500 text-sm mt-0.5">Monitor all file transfers across the platform</p>
        </div>
        <div className="flex items-center gap-2">
          {/* Auto-refresh toggle */}
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-lg border transition-all ${
              autoRefresh
                ? 'bg-green-50 text-green-700 border-green-200 hover:bg-green-100'
                : 'bg-white text-gray-500 border-gray-200 hover:bg-gray-50'
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

      {/* ── Filter Bar ───────────────────────────────────────────── */}
      <div className="card !p-4">
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-1.5 text-gray-400">
            <FunnelIcon className="w-4 h-4" />
            <span className="text-xs font-medium uppercase tracking-wide">Filters</span>
          </div>

          {/* Filename */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-gray-400 pointer-events-none" />
            <input
              className="!w-48 !py-1.5 !pl-8 !pr-3 !text-xs"
              placeholder="Filename..."
              value={filenameFilter}
              onChange={e => setFilenameFilter(e.target.value)}
            />
          </div>

          {/* Track ID */}
          <div className="relative">
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-gray-400 pointer-events-none" />
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
            <MagnifyingGlassIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-gray-400 pointer-events-none" />
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
      </div>

      {/* ── Table ────────────────────────────────────────────────── */}
      <div className="card !p-0 overflow-hidden">
        {isLoading ? (
          <div className="p-12">
            <LoadingSpinner text="Loading transfers..." />
          </div>
        ) : rows.length === 0 ? (
          /* Empty state */
          <div className="flex flex-col items-center justify-center py-20 px-6">
            <div className="w-16 h-16 bg-gray-100 rounded-2xl flex items-center justify-center mb-4">
              <TableCellsIcon className="w-8 h-8 text-gray-400" />
            </div>
            <h3 className="text-base font-semibold text-gray-900 mb-1">No transfers found</h3>
            <p className="text-sm text-gray-500 text-center max-w-sm">
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
                  <tr className="border-b border-gray-100 bg-gray-50/80">
                    {visibleColumns.map(col => (
                      <th
                        key={col.key}
                        className={`table-header cursor-pointer select-none hover:text-gray-700 transition-colors whitespace-nowrap ${col.width}`}
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
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, i) => (
                    <tr
                      key={row.trackId || i}
                      className="table-row cursor-pointer group"
                      onClick={() => handleRowClick(row)}
                    >
                      {visibleColumns.map(col => (
                        <td
                          key={col.key}
                          className={`table-cell text-sm whitespace-nowrap ${col.width} ${
                            i % 2 === 1 ? 'bg-gray-50/40' : ''
                          } group-hover:bg-blue-50/50 transition-colors`}
                        >
                          {col.render(row[col.key], row)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* ── Pagination ─────────────────────────────────────── */}
            <div className="flex items-center justify-between px-6 py-4 border-t border-gray-100 bg-gray-50/40">
              <div className="flex items-center gap-3 text-sm text-gray-500">
                <span>
                  Showing <span className="font-medium text-gray-700">{startItem}</span>
                  {' '}&ndash;{' '}
                  <span className="font-medium text-gray-700">{endItem}</span>
                  {' '}of{' '}
                  <span className="font-medium text-gray-700">{totalElements.toLocaleString()}</span>
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
                  className="px-2 py-1 text-xs rounded-lg border border-gray-200 hover:bg-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  First
                </button>
                <button
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="p-1.5 rounded-lg border border-gray-200 hover:bg-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
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
                        : 'border-gray-200 hover:bg-white text-gray-700'
                    }`}
                  >
                    {pn + 1}
                  </button>
                ))}

                <button
                  onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                  className="p-1.5 rounded-lg border border-gray-200 hover:bg-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  <ChevronRightIcon className="w-4 h-4" />
                </button>
                <button
                  onClick={() => setPage(totalPages - 1)}
                  disabled={page >= totalPages - 1}
                  className="px-2 py-1 text-xs rounded-lg border border-gray-200 hover:bg-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  Last
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {/* ── Column Settings Panel (Slide-out) ────────────────────── */}
      {settingsOpen && (
        <>
          {/* Backdrop */}
          <div
            className="fixed inset-0 bg-black/20 backdrop-blur-sm z-40 transition-opacity"
            onClick={() => setSettingsOpen(false)}
          />
          {/* Panel */}
          <div className="fixed top-0 right-0 h-full w-80 bg-white shadow-2xl z-50 flex flex-col animate-slide-in-right">
            <div className="flex items-center justify-between p-5 border-b border-gray-100">
              <div>
                <h2 className="text-base font-semibold text-gray-900">Column Settings</h2>
                <p className="text-xs text-gray-500 mt-0.5">{visibleKeys.length} of {ALL_COLUMNS.length} columns visible</p>
              </div>
              <button
                onClick={() => setSettingsOpen(false)}
                className="p-1.5 hover:bg-gray-100 rounded-lg transition-colors"
              >
                <XMarkIcon className="w-5 h-5 text-gray-500" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-5">
              <div className="space-y-1">
                {ALL_COLUMNS.map(col => (
                  <label
                    key={col.key}
                    className="flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-gray-50 cursor-pointer transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={visibleKeys.includes(col.key)}
                      onChange={() => toggle(col.key)}
                      className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500/40 focus:ring-offset-0 cursor-pointer"
                    />
                    <div className="flex-1 min-w-0">
                      <span className="text-sm font-medium text-gray-700">{col.label}</span>
                      {col.defaultVisible && (
                        <span className="ml-1.5 text-xs text-gray-400">(default)</span>
                      )}
                    </div>
                  </label>
                ))}
              </div>
            </div>

            <div className="p-5 border-t border-gray-100">
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
