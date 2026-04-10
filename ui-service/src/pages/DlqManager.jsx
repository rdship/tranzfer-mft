import { useState, useEffect, useCallback, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import * as dlqApi from '../api/dlq'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  InboxIcon, ArrowPathIcon, TrashIcon, CheckCircleIcon,
  MagnifyingGlassIcon, ChevronDownIcon, ChevronUpIcon,
  ExclamationTriangleIcon, PlayIcon
} from '@heroicons/react/24/outline'

// ── Helpers ─────────────────────────────────────────────────────────────

const statusBadge = (status) => {
  switch (status?.toUpperCase()) {
    case 'FAILED':    return 'badge-red'
    case 'RETRYING':  return 'badge-yellow'
    case 'DISCARDED': return 'badge-gray'
    case 'RESOLVED':  return 'badge-green'
    default:          return 'badge-blue'
  }
}

// ── JSON Viewer ─────────────────────────────────────────────────────────

function JsonViewer({ data }) {
  const formatted = typeof data === 'string'
    ? (() => { try { return JSON.stringify(JSON.parse(data), null, 2) } catch { return data } })()
    : JSON.stringify(data, null, 2)

  return (
    <pre className="bg-canvas rounded-lg p-4 text-xs font-mono text-secondary overflow-auto max-h-64 border border-border">
      {formatted}
    </pre>
  )
}

// ── Main Component ──────────────────────────────────────────────────────

export default function DlqManager() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [expandedRow, setExpandedRow] = useState(null)
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [confirmRetryAll, setConfirmRetryAll] = useState(false)
  const [confirmAction, setConfirmAction] = useState(null) // { id, type: 'retry'|'discard' }
  const [sortBy, setSortBy] = useState('failedAt')
  const [sortDir, setSortDir] = useState('desc')

  // ── Queries ──

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['dlq-messages', page],
    queryFn: () => dlqApi.getDlqMessages(page, 20),
    refetchInterval: autoRefresh ? 5000 : false,
  })

  const messages = data?.content || data || []
  const totalPages = data?.totalPages || 0
  const totalElements = data?.totalElements ?? messages.length

  // ── Mutations ──

  const retryOne = useMutation({
    mutationFn: dlqApi.retryDlqMessage,
    onSuccess: () => {
      toast.success('Message queued for retry')
      queryClient.invalidateQueries({ queryKey: ['dlq-messages'] })
      setConfirmAction(null)
    },
    onError: (err) => {
      toast.error(err?.response?.data?.message || 'Retry failed')
      setConfirmAction(null)
    },
  })

  const discardOne = useMutation({
    mutationFn: dlqApi.discardDlqMessage,
    onSuccess: () => {
      toast.success('Message discarded')
      queryClient.invalidateQueries({ queryKey: ['dlq-messages'] })
      setConfirmAction(null)
    },
    onError: (err) => {
      toast.error(err?.response?.data?.message || 'Discard failed')
      setConfirmAction(null)
    },
  })

  const retryAll = useMutation({
    mutationFn: dlqApi.retryAllDlq,
    onSuccess: (data) => {
      toast.success(`Retry initiated for ${data?.count ?? 'all'} messages`)
      queryClient.invalidateQueries({ queryKey: ['dlq-messages'] })
      setConfirmRetryAll(false)
    },
    onError: (err) => {
      toast.error(err?.response?.data?.message || 'Retry all failed')
      setConfirmRetryAll(false)
    },
  })

  // ── Filtered messages ──

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  const filtered = useMemo(() => {
    const list = search.trim()
      ? messages.filter(m =>
          (m.errorMessage || '').toLowerCase().includes(search.toLowerCase()) ||
          (m.originalQueue || '').toLowerCase().includes(search.toLowerCase())
        )
      : messages
    const arr = [...list]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'failedAt') {
        va = a.failedAt ? new Date(a.failedAt).getTime() : 0
        vb = b.failedAt ? new Date(b.failedAt).getTime() : 0
      } else if (sortBy === 'retryCount') {
        va = a.retryCount ?? 0; vb = b.retryCount ?? 0
      } else if (sortBy === 'queue') {
        va = a.originalQueue ?? ''; vb = b.originalQueue ?? ''
      } else {
        va = a[sortBy] ?? ''; vb = b[sortBy] ?? ''
      }
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [messages, search, sortBy, sortDir])

  // ── Render ────────────────────────────────────────────────────────────

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <InboxIcon className="w-6 h-6 text-accent" />
          <div>
            <div className="flex items-center gap-2">
              <h1 className="page-title">Dead Letter Queue</h1>
              <span className="badge badge-red">{totalElements}</span>
            </div>
            <p className="text-sm text-secondary mt-0.5">Messages that failed processing and require attention</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          {/* Auto-refresh toggle */}
          <button
            onClick={() => setAutoRefresh(v => !v)}
            className={`btn-secondary text-sm ${autoRefresh ? '!border-accent !text-accent' : ''}`}
          >
            <ArrowPathIcon className={`w-4 h-4 ${autoRefresh ? 'animate-spin' : ''}`} />
            {autoRefresh ? 'Auto-refresh ON' : 'Auto-refresh'}
          </button>
          {/* Retry All */}
          <button
            onClick={() => setConfirmRetryAll(true)}
            className="btn-primary"
            disabled={totalElements === 0 || retryAll.isPending}
          >
            <PlayIcon className="w-4 h-4" />
            Retry All
          </button>
        </div>
      </div>

      {/* Search bar */}
      <div className="relative">
        <MagnifyingGlassIcon className="w-4 h-4 text-muted absolute left-3 top-1/2 -translate-y-1/2" />
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Filter by error message or queue name..."
          className="w-full bg-surface border border-border rounded-lg pl-10 pr-4 py-2.5 text-sm text-primary placeholder-muted focus:outline-none focus:border-accent transition-colors"
        />
      </div>

      {/* Retry All confirmation */}
      {confirmRetryAll && (
        <div className="card border-accent/30 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-amber-400" />
            <span className="text-primary text-sm">
              Retry all {totalElements} dead-lettered messages? This will re-queue them for processing.
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => retryAll.mutate()}
              disabled={retryAll.isPending}
              className="btn-primary text-sm"
            >
              {retryAll.isPending ? 'Retrying...' : 'Confirm Retry All'}
            </button>
            <button onClick={() => setConfirmRetryAll(false)} className="btn-secondary text-sm">
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* Loading state */}
      {isLoading && (
        <div className="flex flex-col items-center justify-center py-12 gap-3">
          <div className="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-secondary">Loading messages...</p>
        </div>
      )}

      {/* Empty state */}
      {!isLoading && filtered.length === 0 && (
        <div className="card flex flex-col items-center justify-center py-16 text-center">
          <CheckCircleIcon className="w-12 h-12 text-green-400 mb-4" />
          <h3 className="text-base font-semibold text-primary mb-1">No dead-lettered messages</h3>
          <p className="text-sm text-secondary">
            {search ? 'No messages match your search criteria' : 'All messages are being processed normally'}
          </p>
        </div>
      )}

      {/* Table */}
      {!isLoading && filtered.length > 0 && (
        <div className="card !p-0 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted text-xs uppercase tracking-wider">
                <th className="px-4 py-3 text-left w-8"></th>
                <th className="px-4 py-3 text-left">ID</th>
                <th className="px-4 py-3 text-left cursor-pointer select-none" onClick={() => toggleSort('queue')} aria-sort={sortBy === 'queue' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Original Queue {sortBy === 'queue' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                <th className="px-4 py-3 text-left">Error Message</th>
                <th className="px-4 py-3 text-left cursor-pointer select-none" onClick={() => toggleSort('failedAt')} aria-sort={sortBy === 'failedAt' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Failed At {sortBy === 'failedAt' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                <th className="px-4 py-3 text-left cursor-pointer select-none" onClick={() => toggleSort('retryCount')} aria-sort={sortBy === 'retryCount' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Retries {sortBy === 'retryCount' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                <th className="px-4 py-3 text-left">Status</th>
                <th className="px-4 py-3 text-left">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {filtered.map((msg) => (
                <MessageRow
                  key={msg.id}
                  msg={msg}
                  expanded={expandedRow === msg.id}
                  onToggle={() => setExpandedRow(expandedRow === msg.id ? null : msg.id)}
                  confirmAction={confirmAction}
                  onConfirmAction={setConfirmAction}
                  onRetry={(id) => retryOne.mutate(id)}
                  onDiscard={(id) => discardOne.mutate(id)}
                  retryPending={retryOne.isPending}
                  discardPending={discardOne.isPending}
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
    </div>
  )
}

// ── Message Row ─────────────────────────────────────────────────────────

function MessageRow({
  msg, expanded, onToggle, confirmAction, onConfirmAction,
  onRetry, onDiscard, retryPending, discardPending,
}) {
  const isConfirming = confirmAction?.id === msg.id

  return (
    <>
      <tr
        className="cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
        onClick={onToggle}
      >
        <td className="px-4 py-3">
          {expanded
            ? <ChevronUpIcon className="w-4 h-4 text-muted" />
            : <ChevronDownIcon className="w-4 h-4 text-muted" />
          }
        </td>
        <td className="px-4 py-3 font-mono text-xs text-secondary">
          {msg.id?.toString().substring(0, 8)}...
        </td>
        <td className="px-4 py-3 text-primary font-mono text-xs">
          {msg.originalQueue || '-'}
        </td>
        <td className="px-4 py-3 text-secondary max-w-xs truncate" title={msg.errorMessage}>
          {msg.errorMessage || '-'}
        </td>
        <td className="px-4 py-3 text-secondary text-xs">
          {msg.failedAt ? format(new Date(msg.failedAt), 'MMM dd, HH:mm:ss') : '-'}
        </td>
        <td className="px-4 py-3">
          <span className="font-mono text-primary">{msg.retryCount ?? 0}</span>
        </td>
        <td className="px-4 py-3">
          <span className={`badge ${statusBadge(msg.status)}`}>{msg.status || 'FAILED'}</span>
        </td>
        <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
          {isConfirming ? (
            <div className="flex items-center gap-2">
              <span className="text-xs text-secondary">
                {confirmAction.type === 'retry' ? 'Retry?' : 'Discard?'}
              </span>
              <button
                onClick={() => confirmAction.type === 'retry' ? onRetry(msg.id) : onDiscard(msg.id)}
                disabled={retryPending || discardPending}
                className="text-xs px-2 py-0.5 bg-accent/20 text-accent rounded hover:bg-accent/30 transition-colors"
              >
                Yes
              </button>
              <button
                onClick={() => onConfirmAction(null)}
                className="text-xs px-2 py-0.5 bg-hover text-muted rounded hover:text-primary transition-colors"
              >
                No
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <button
                onClick={() => onConfirmAction({ id: msg.id, type: 'retry' })}
                className="btn-secondary text-xs !px-2 !py-1"
                title="Retry message"
              >
                <ArrowPathIcon className="w-3.5 h-3.5" />
                Retry
              </button>
              <button
                onClick={() => onConfirmAction({ id: msg.id, type: 'discard' })}
                className="btn-secondary text-xs !px-2 !py-1 hover:!border-red-500/50 hover:!text-red-400"
                title="Discard message"
              >
                <TrashIcon className="w-3.5 h-3.5" />
                Discard
              </button>
            </div>
          )}
        </td>
      </tr>

      {/* Expanded payload view */}
      {expanded && (
        <tr>
          <td colSpan={8} className="px-4 py-4 bg-canvas/50">
            <div className="space-y-3">
              <div className="flex items-center gap-4 text-xs">
                <span className="text-muted">Full ID:</span>
                <span className="font-mono text-secondary">{msg.id}</span>
              </div>
              {msg.errorMessage && (
                <div>
                  <p className="text-xs text-muted mb-1">Error Message</p>
                  <p className="text-sm text-red-400 bg-red-500/10 rounded-lg p-3 border border-red-500/20">
                    {msg.errorMessage}
                  </p>
                </div>
              )}
              <div>
                <p className="text-xs text-muted mb-1">Message Payload</p>
                <JsonViewer data={msg.payload || msg.body || msg.message || '(no payload)'} />
              </div>
              {msg.headers && (
                <div>
                  <p className="text-xs text-muted mb-1">Headers</p>
                  <JsonViewer data={msg.headers} />
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  )
}
