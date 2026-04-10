import { useState, Fragment } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { getPartnerTransfers, trackTransfer, downloadReceipt } from '../api/partnerPortal'
import LoadingSpinner from '../components/LoadingSpinner'
import { format } from 'date-fns'
import {
  MagnifyingGlassIcon,
  FunnelIcon,
  ArrowDownTrayIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  ArrowUpTrayIcon,
  CheckCircleIcon,
  XCircleIcon,
  ArrowPathIcon,
  ClockIcon,
} from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

const STATUS_OPTIONS = ['ALL', 'COMPLETED', 'FAILED', 'IN_PROGRESS', 'PENDING']

const STATUS_BADGE = {
  COMPLETED:   'badge-green',
  FAILED:      'badge-red',
  IN_PROGRESS: 'badge-yellow',
  PENDING:     'badge-blue',
  QUEUED:      'badge-gray',
}

/* Stage icon for the transfer journey */
function StageIcon({ stage }) {
  const iconMap = {
    RECEIVED:    ArrowUpTrayIcon,
    PROCESSING:  ArrowPathIcon,
    COMPLETED:   CheckCircleIcon,
    FAILED:      XCircleIcon,
    QUEUED:      ClockIcon,
    DELIVERING:  ArrowPathIcon,
  }
  const Icon = iconMap[stage?.status || stage?.stage] || ClockIcon
  return <Icon className="w-4 h-4 flex-shrink-0" />
}

/* Transfer journey detail (expanded row) */
function TransferDetail({ trackId }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['partner-track', trackId],
    queryFn: () => trackTransfer(trackId),
    enabled: !!trackId,
  })

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 py-4 px-6">
        <div className="w-4 h-4 border-2 rounded-full animate-spin" style={{ borderColor: 'rgb(var(--accent)) transparent rgb(var(--accent)) rgb(var(--accent))' }} />
        <span className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>Loading transfer journey...</span>
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="py-4 px-6">
        <p className="text-xs" style={{ color: '#f87171' }}>Failed to load transfer details</p>
      </div>
    )
  }

  const stages = data.stages || data.journey || []
  const meta = data.metadata || data

  return (
    <div className="py-4 px-6 space-y-4" style={{ background: 'rgb(var(--hover))' }}>
      {/* Metadata row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {meta.protocol && (
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>Protocol</p>
            <p className="text-sm font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>{meta.protocol}</p>
          </div>
        )}
        {meta.direction && (
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>Direction</p>
            <p className="text-sm font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>{meta.direction}</p>
          </div>
        )}
        {(meta.sourceFolder || meta.source) && (
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>Source</p>
            <p className="text-sm font-mono truncate" style={{ color: 'rgb(var(--tx-primary))' }}>{meta.sourceFolder || meta.source}</p>
          </div>
        )}
        {(meta.destinationFolder || meta.destination) && (
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>Destination</p>
            <p className="text-sm font-mono truncate" style={{ color: 'rgb(var(--tx-primary))' }}>{meta.destinationFolder || meta.destination}</p>
          </div>
        )}
      </div>

      {/* Journey stages */}
      {stages.length > 0 && (
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-wider mb-2" style={{ color: 'rgb(var(--tx-muted))' }}>
            Transfer Journey
          </p>
          <div className="flex items-start gap-0">
            {stages.map((stage, idx) => {
              const isLast = idx === stages.length - 1
              const stageName = stage.stage || stage.name || stage.status
              const stageStatus = stage.status || 'UNKNOWN'
              const colorMap = {
                COMPLETED:   '#4ade80',
                FAILED:      '#f87171',
                IN_PROGRESS: '#fbbf24',
                PENDING:     'rgb(var(--tx-muted))',
              }
              const color = colorMap[stageStatus] || 'rgb(var(--tx-muted))'

              return (
                <div key={idx} className="flex items-start">
                  <div className="flex flex-col items-center">
                    <div
                      className="w-8 h-8 rounded-full flex items-center justify-center"
                      style={{ background: `${color}20`, color }}
                    >
                      <StageIcon stage={stage} />
                    </div>
                    <p className="text-[10px] font-medium mt-1 text-center max-w-[80px]" style={{ color }}>
                      {stageName}
                    </p>
                    {stage.timestamp && (
                      <p className="text-[9px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
                        {format(new Date(stage.timestamp), 'HH:mm:ss')}
                      </p>
                    )}
                  </div>
                  {!isLast && (
                    <div
                      className="w-8 h-px mt-4 flex-shrink-0"
                      style={{ background: 'rgb(var(--border))' }}
                    />
                  )}
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

export default function PartnerPortalTransfers() {
  const [page, setPage]         = useState(0)
  const [search, setSearch]     = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [expandedRow, setExpandedRow]   = useState(null)
  const [sortField, setSortField]       = useState('startedAt')
  const [sortDir, setSortDir]           = useState('desc')
  const pageSize = 20

  const { data, isLoading } = useQuery({
    queryKey: ['partner-transfers', page, pageSize],
    queryFn: () => getPartnerTransfers(page, pageSize),
    keepPreviousData: true,
  })

  const receiptMutation = useMutation({
    mutationFn: (trackId) => downloadReceipt(trackId),
    onSuccess: (blob, trackId) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `receipt-${trackId}.pdf`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      toast.success('Receipt downloaded')
    },
    onError: (err) => {
      toast.error(err.response?.data?.error || 'Failed to download receipt')
    },
  })

  if (isLoading) return <LoadingSpinner text="Loading transfers..." />

  const allTransfers = data?.content || data?.transfers || data || []
  const totalPages   = data?.totalPages ?? 1
  const totalCount   = data?.totalElements ?? allTransfers.length

  /* Client-side search + filter */
  let filtered = allTransfers.filter(t => {
    if (statusFilter !== 'ALL' && t.status !== statusFilter) return false
    if (search) {
      const q = search.toLowerCase()
      const filename = (t.filename || t.fileName || '').toLowerCase()
      const trackId  = (t.trackId || '').toLowerCase()
      if (!filename.includes(q) && !trackId.includes(q)) return false
    }
    return true
  })

  /* Client-side sort */
  filtered = [...filtered].sort((a, b) => {
    let aVal = a[sortField]
    let bVal = b[sortField]
    if (sortField === 'startedAt' || sortField === 'completedAt') {
      aVal = aVal ? new Date(aVal).getTime() : 0
      bVal = bVal ? new Date(bVal).getTime() : 0
    }
    if (typeof aVal === 'string') aVal = aVal.toLowerCase()
    if (typeof bVal === 'string') bVal = bVal.toLowerCase()
    if (aVal < bVal) return sortDir === 'asc' ? -1 : 1
    if (aVal > bVal) return sortDir === 'asc' ? 1 : -1
    return 0
  })

  const toggleSort = (field) => {
    if (sortField === field) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    } else {
      setSortField(field)
      setSortDir('desc')
    }
  }

  const SortIndicator = ({ field }) => {
    if (sortField !== field) return null
    return sortDir === 'asc'
      ? <ChevronUpIcon className="w-3 h-3 inline ml-0.5" />
      : <ChevronDownIcon className="w-3 h-3 inline ml-0.5" />
  }

  return (
    <div className="space-y-5 animate-page">

      {/* Header */}
      <div>
        <h1 className="text-xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>My Transfers</h1>
        <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
          {totalCount.toLocaleString()} total transfers
        </p>
      </div>

      {/* Search + Filter bar */}
      <div className="flex flex-wrap items-center gap-3">
        {/* Search */}
        <div className="relative flex-1 min-w-[200px] max-w-md">
          <MagnifyingGlassIcon
            className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4"
            style={{ color: 'rgb(var(--tx-muted))' }}
          />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by filename or track ID..."
            className="pl-9"
          />
        </div>

        {/* Status filter */}
        <div className="flex items-center gap-1.5">
          <FunnelIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            className="text-sm"
            style={{ minWidth: '140px' }}
          >
            {STATUS_OPTIONS.map(s => (
              <option key={s} value={s}>{s === 'ALL' ? 'All Statuses' : s}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="card p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('filename')}>
                  Filename <SortIndicator field="filename" />
                </th>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('size')}>
                  Size <SortIndicator field="size" />
                </th>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('status')}>
                  Status <SortIndicator field="status" />
                </th>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('startedAt')}>
                  Started <SortIndicator field="startedAt" />
                </th>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('completedAt')}>
                  Completed <SortIndicator field="completedAt" />
                </th>
                <th className="table-header">Track ID</th>
                <th className="table-header">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length > 0 ? (
                filtered.map((t, i) => {
                  const trackId = t.trackId || `row-${i}`
                  const isExpanded = expandedRow === trackId

                  return (
                    <Fragment key={trackId}>
                      <tr
                        className="table-row cursor-pointer"
                        onClick={() => setExpandedRow(isExpanded ? null : trackId)}
                      >
                        <td className="table-cell">
                          <span className="font-medium text-sm">{t.filename || t.fileName || '—'}</span>
                        </td>
                        <td className="table-cell">
                          <span className="font-mono text-xs">{t.size || t.fileSize || '—'}</span>
                        </td>
                        <td className="table-cell">
                          <span className={`badge ${STATUS_BADGE[t.status] || 'badge-gray'}`}>{t.status || '—'}</span>
                        </td>
                        <td className="table-cell">
                          <span className="text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                            {t.startedAt ? format(new Date(t.startedAt), 'MMM d, HH:mm:ss') : '—'}
                          </span>
                        </td>
                        <td className="table-cell">
                          <span className="text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                            {t.completedAt ? format(new Date(t.completedAt), 'MMM d, HH:mm:ss') : '—'}
                          </span>
                        </td>
                        <td className="table-cell">
                          <span className="font-mono text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                            {t.trackId || '—'}
                          </span>
                        </td>
                        <td className="table-cell">
                          <div className="flex items-center gap-1">
                            {t.status === 'COMPLETED' && (
                              <button
                                className="btn-ghost text-xs px-2 py-1"
                                title="Download receipt"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  receiptMutation.mutate(t.trackId)
                                }}
                                disabled={receiptMutation.isPending}
                              >
                                <ArrowDownTrayIcon className="w-3.5 h-3.5" />
                              </button>
                            )}
                            {isExpanded ? (
                              <ChevronUpIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
                            ) : (
                              <ChevronDownIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
                            )}
                          </div>
                        </td>
                      </tr>
                      {isExpanded && (
                        <tr>
                          <td colSpan={7} style={{ padding: 0 }}>
                            <TransferDetail trackId={t.trackId} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })
              ) : (
                <tr>
                  <td colSpan={7} className="py-12 text-center">
                    <ArrowUpTrayIcon className="w-8 h-8 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
                    <p className="text-sm" style={{ color: 'rgb(var(--tx-muted))' }}>No transfers found</p>
                    <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {search || statusFilter !== 'ALL' ? 'Try adjusting your filters' : 'Transfers will appear here once initiated'}
                    </p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div
            className="flex items-center justify-between px-4 py-3"
            style={{ borderTop: '1px solid rgb(var(--border))' }}
          >
            <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
              Page {page + 1} of {totalPages}
            </p>
            <div className="flex items-center gap-2">
              <button
                className="btn-secondary text-xs px-3 py-1.5"
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0}
              >
                <ChevronLeftIcon className="w-3.5 h-3.5" />
                Previous
              </button>
              <button
                className="btn-secondary text-xs px-3 py-1.5"
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
              >
                Next
                <ChevronRightIcon className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
