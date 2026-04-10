import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import * as autoOnboardingApi from '../api/autoOnboarding'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  CpuChipIcon, CheckCircleIcon,
  ClockIcon, AcademicCapIcon, MagnifyingGlassIcon,
  SignalIcon, ServerIcon, DocumentDuplicateIcon,
  ChevronRightIcon, GlobeAltIcon, ArrowPathIcon,
  SparklesIcon
} from '@heroicons/react/24/outline'

// ── Helpers ─────────────────────────────────────────────────────────────

const STATUS_TABS = ['ALL', 'PENDING', 'APPROVED', 'LEARNING', 'COMPLETED']

const statusBadge = (status) => {
  switch (status?.toUpperCase()) {
    case 'PENDING':   return 'badge-yellow'
    case 'APPROVED':  return 'badge-green'
    case 'REJECTED':  return 'badge-red'
    case 'LEARNING':  return 'badge-blue'
    case 'COMPLETED': return 'badge-teal'
    default:          return 'badge-gray'
  }
}

const protocolBadge = (protocol) => {
  switch (protocol?.toUpperCase()) {
    case 'SFTP': return 'badge-blue'
    case 'FTP':  return 'badge-purple'
    case 'AS2':  return 'badge-teal'
    default:     return 'badge-gray'
  }
}

const STATUS_ICONS = {
  ALL:       CpuChipIcon,
  PENDING:   ClockIcon,
  APPROVED:  CheckCircleIcon,
  LEARNING:  AcademicCapIcon,
  COMPLETED: SparklesIcon,
}

// ── Main Component ──────────────────────────────────────────────────────

export default function AutoOnboarding() {
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [selectedSession, setSelectedSession] = useState(null)
  const [confirmAction, setConfirmAction] = useState(null) // { id, type: 'approve' }

  // ── Queries ──

  const { data: sessions = [], isLoading } = useQuery({
    queryKey: ['auto-onboard-sessions', statusFilter],
    queryFn: () => autoOnboardingApi.getOnboardingSessions(statusFilter === 'ALL' ? null : statusFilter),
    refetchInterval: 15000,
  })

  const { data: stats } = useQuery({
    queryKey: ['auto-onboard-stats'],
    queryFn: autoOnboardingApi.getOnboardingStats,
    refetchInterval: 30000,
  })

  const { data: sessionDetail } = useQuery({
    queryKey: ['auto-onboard-session', selectedSession],
    queryFn: () => autoOnboardingApi.getOnboardingSession(selectedSession),
    enabled: !!selectedSession,
  })

  // ── Mutations ──

  const approve = useMutation({
    mutationFn: autoOnboardingApi.approveSession,
    onSuccess: () => {
      toast.success('Session approved — partner account will be created')
      queryClient.invalidateQueries({ queryKey: ['auto-onboard-sessions'] })
      queryClient.invalidateQueries({ queryKey: ['auto-onboard-stats'] })
      setConfirmAction(null)
    },
    onError: (err) => {
      toast.error(err?.response?.data?.message || 'Approval failed')
      setConfirmAction(null)
    },
  })

  // Note: backend has no reject endpoint — reject UI action removed

  // ── Stats cards ──

  const statCards = [
    {
      label: 'Total Sessions',
      value: stats?.total ?? sessions.length,
      icon: CpuChipIcon,
      color: 'text-accent',
      bgColor: 'bg-accent/10',
    },
    {
      label: 'Pending Review',
      value: stats?.pending ?? 0,
      icon: ClockIcon,
      color: 'text-yellow-400',
      bgColor: 'bg-yellow-500/10',
    },
    {
      label: 'Approved',
      value: stats?.approved ?? 0,
      icon: CheckCircleIcon,
      color: 'text-green-400',
      bgColor: 'bg-green-500/10',
    },
    {
      label: 'Learning',
      value: stats?.learning ?? 0,
      icon: AcademicCapIcon,
      color: 'text-blue-400',
      bgColor: 'bg-blue-500/10',
    },
  ]

  // ── Render ────────────────────────────────────────────────────────────

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <CpuChipIcon className="w-6 h-6 text-accent" />
        <div>
          <h1 className="page-title">Auto-Onboarding Review</h1>
          <p className="text-sm text-secondary mt-0.5">AI-detected partner onboarding sessions</p>
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

      {/* Filter tabs */}
      <div className="flex items-center gap-1 bg-surface border border-border rounded-lg p-1 w-fit">
        {STATUS_TABS.map((tab) => {
          const Icon = STATUS_ICONS[tab]
          return (
            <button
              key={tab}
              onClick={() => setStatusFilter(tab)}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all ${
                statusFilter === tab
                  ? 'bg-accent/15 text-accent'
                  : 'text-muted hover:text-primary hover:bg-hover'
              }`}
            >
              {Icon && <Icon className="w-3.5 h-3.5" />}
              {tab}
            </button>
          )
        })}
      </div>

      {/* Loading state */}
      {isLoading && (
        <div className="flex flex-col items-center justify-center py-12 gap-3">
          <div className="w-8 h-8 border-2 border-accent border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-secondary">Loading sessions...</p>
        </div>
      )}

      {/* Empty state */}
      {!isLoading && sessions.length === 0 && (
        <div className="card flex flex-col items-center justify-center py-16 text-center">
          <CpuChipIcon className="w-12 h-12 text-muted mb-4" />
          <h3 className="text-base font-semibold text-primary mb-1">No onboarding sessions detected</h3>
          <p className="text-sm text-secondary">
            The AI engine has not detected any new partner connection patterns yet
          </p>
        </div>
      )}

      {/* Session Cards */}
      {!isLoading && sessions.length > 0 && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {sessions.map((session) => (
            <SessionCard
              key={session.id}
              session={session}
              confirmAction={confirmAction}
              onConfirmAction={setConfirmAction}
              onApprove={(id) => approve.mutate(id)}
              onViewDetails={(id) => setSelectedSession(id)}
              approvePending={approve.isPending}
            />
          ))}
        </div>
      )}

      {/* Session Detail Modal */}
      {selectedSession && sessionDetail && (
        <Modal title="Session Details" onClose={() => setSelectedSession(null)} size="lg">
          <SessionDetailView session={sessionDetail} />
        </Modal>
      )}
    </div>
  )
}

// ── Session Card ────────────────────────────────────────────────────────

function SessionCard({
  session, confirmAction, onConfirmAction,
  onApprove, onViewDetails,
  approvePending,
}) {
  const isConfirming = confirmAction?.id === session.id
  const confidence = session.confidence ?? session.confidenceScore ?? 0
  const confidencePercent = confidence > 1 ? confidence : Math.round(confidence * 100)

  return (
    <div className="card card-hover group">
      {/* Top row: partner info + status */}
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-lg bg-accent/10">
            <GlobeAltIcon className="w-5 h-5 text-accent" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-primary">
              {session.partnerName || session.partnerIp || 'Unknown Partner'}
            </h3>
            {session.partnerIp && session.partnerName && (
              <p className="text-xs text-muted font-mono">{session.partnerIp}</p>
            )}
            {session.detectionMethod && (
              <p className="text-xs text-secondary mt-0.5">
                Detected via: {session.detectionMethod}
              </p>
            )}
          </div>
        </div>
        <span className={`badge ${statusBadge(session.status)}`}>
          {session.status || 'PENDING'}
        </span>
      </div>

      {/* Protocol + file patterns */}
      <div className="grid grid-cols-2 gap-3 mb-4">
        <div className="bg-canvas/50 rounded-lg p-3 border border-border/50">
          <div className="flex items-center gap-1.5 mb-1">
            <ServerIcon className="w-3.5 h-3.5 text-muted" />
            <span className="text-xs text-muted">Protocol</span>
          </div>
          <span className={`badge ${protocolBadge(session.protocol)}`}>
            {session.protocol || '-'}
          </span>
        </div>
        <div className="bg-canvas/50 rounded-lg p-3 border border-border/50">
          <div className="flex items-center gap-1.5 mb-1">
            <DocumentDuplicateIcon className="w-3.5 h-3.5 text-muted" />
            <span className="text-xs text-muted">File Patterns</span>
          </div>
          <div className="flex flex-wrap gap-1">
            {(session.filePatterns || session.observedPatterns || []).length > 0 ? (
              (session.filePatterns || session.observedPatterns).slice(0, 3).map((p, i) => (
                <span key={i} className="text-xs font-mono text-secondary bg-hover px-1.5 py-0.5 rounded">
                  {p}
                </span>
              ))
            ) : (
              <span className="text-xs text-muted">None detected</span>
            )}
            {(session.filePatterns || session.observedPatterns || []).length > 3 && (
              <span className="text-xs text-muted">
                +{(session.filePatterns || session.observedPatterns).length - 3} more
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Confidence score */}
      <div className="mb-4">
        <div className="flex items-center justify-between mb-1.5">
          <span className="text-xs text-muted">Confidence Score</span>
          <span className={`text-xs font-mono font-semibold ${
            confidencePercent >= 80 ? 'text-green-400' :
            confidencePercent >= 50 ? 'text-yellow-400' : 'text-red-400'
          }`}>
            {confidencePercent}%
          </span>
        </div>
        <div className="w-full h-2 bg-border rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-500 ${
              confidencePercent >= 80 ? 'bg-green-500' :
              confidencePercent >= 50 ? 'bg-yellow-500' : 'bg-red-500'
            }`}
            style={{ width: `${confidencePercent}%` }}
          />
        </div>
      </div>

      {/* Suggested config */}
      {session.suggestedConfig && (
        <div className="bg-canvas/50 rounded-lg p-3 border border-border/50 mb-4">
          <p className="text-xs text-muted mb-1.5">Suggested Account Config</p>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
            {Object.entries(session.suggestedConfig).slice(0, 4).map(([key, value]) => (
              <div key={key} className="flex items-center gap-1">
                <span className="text-muted">{key}:</span>
                <span className="text-secondary font-mono truncate">{String(value)}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Timestamp */}
      <div className="flex items-center justify-between text-xs text-muted mb-4">
        <span>
          {session.createdAt
            ? format(new Date(session.createdAt), 'MMM dd, yyyy HH:mm')
            : session.detectedAt
            ? format(new Date(session.detectedAt), 'MMM dd, yyyy HH:mm')
            : '-'}
        </span>
        {session.connectionCount !== undefined && (
          <span className="flex items-center gap-1">
            <SignalIcon className="w-3.5 h-3.5" />
            {session.connectionCount} connections
          </span>
        )}
      </div>

      {/* Actions */}
      <div className="flex items-center gap-2 pt-3 border-t border-border">
        {isConfirming ? (
          <ConfirmActionBar
            onConfirm={() => onApprove(session.id)}
            onCancel={() => onConfirmAction(null)}
            pending={approvePending}
          />
        ) : (
          <>
            {(session.status === 'PENDING' || session.status === 'LEARNING') && (
              <>
                <button
                  onClick={() => onConfirmAction({ id: session.id, type: 'approve' })}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-green-600/15 text-green-400 text-xs font-medium rounded-lg hover:bg-green-600/25 transition-colors"
                >
                  <CheckCircleIcon className="w-4 h-4" />
                  Approve
                </button>
              </>
            )}
            <button
              onClick={() => onViewDetails(session.id)}
              className="btn-secondary text-xs ml-auto"
            >
              <ChevronRightIcon className="w-3.5 h-3.5" />
              Details
            </button>
          </>
        )}
      </div>
    </div>
  )
}

// ── Confirm Action Bar ──────────────────────────────────────────────────

function ConfirmActionBar({ onConfirm, onCancel, pending }) {
  return (
    <div className="flex items-center gap-2 w-full">
      <span className="text-xs text-green-400">
        Approve this session?
      </span>
      <button
        onClick={onConfirm}
        disabled={pending}
        className="text-xs px-3 py-1 rounded-lg font-medium transition-colors disabled:opacity-50 bg-green-600/20 text-green-400 hover:bg-green-600/30"
      >
        {pending ? 'Approving...' : 'Confirm'}
      </button>
      <button onClick={onCancel} className="text-xs px-2 py-1 text-muted hover:text-primary transition-colors">
        Cancel
      </button>
    </div>
  )
}

// ── Session Detail View (modal content) ─────────────────────────────────

function SessionDetailView({ session }) {
  const confidence = session.confidence ?? session.confidenceScore ?? 0
  const confidencePercent = confidence > 1 ? confidence : Math.round(confidence * 100)

  return (
    <div className="space-y-6">
      {/* Partner info */}
      <div className="flex items-center gap-4">
        <div className="p-3 rounded-xl bg-accent/10">
          <GlobeAltIcon className="w-6 h-6 text-accent" />
        </div>
        <div>
          <h3 className="text-lg font-semibold text-primary">
            {session.partnerName || session.partnerIp || 'Unknown Partner'}
          </h3>
          <div className="flex items-center gap-3 mt-1">
            <span className={`badge ${statusBadge(session.status)}`}>{session.status}</span>
            <span className={`badge ${protocolBadge(session.protocol)}`}>{session.protocol}</span>
            {session.partnerIp && (
              <span className="text-xs text-muted font-mono">{session.partnerIp}</span>
            )}
          </div>
        </div>
      </div>

      {/* Confidence */}
      <div className="bg-canvas rounded-lg p-4 border border-border">
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm text-primary font-medium">Confidence Score</span>
          <span className={`text-lg font-bold font-mono ${
            confidencePercent >= 80 ? 'text-green-400' :
            confidencePercent >= 50 ? 'text-yellow-400' : 'text-red-400'
          }`}>
            {confidencePercent}%
          </span>
        </div>
        <div className="w-full h-3 bg-border rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all ${
              confidencePercent >= 80 ? 'bg-green-500' :
              confidencePercent >= 50 ? 'bg-yellow-500' : 'bg-red-500'
            }`}
            style={{ width: `${confidencePercent}%` }}
          />
        </div>
      </div>

      {/* Detection method */}
      {session.detectionMethod && (
        <div>
          <h4 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">Detection Method</h4>
          <p className="text-sm text-secondary">{session.detectionMethod}</p>
        </div>
      )}

      {/* Learned patterns */}
      {(session.learnedPatterns || session.filePatterns || session.observedPatterns) && (
        <div>
          <h4 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">Learned Patterns</h4>
          <div className="flex flex-wrap gap-2">
            {(session.learnedPatterns || session.filePatterns || session.observedPatterns || []).map((p, i) => (
              <span key={i} className="badge badge-blue font-mono">{p}</span>
            ))}
          </div>
        </div>
      )}

      {/* Connection history */}
      {session.connectionHistory && session.connectionHistory.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">
            Connection History ({session.connectionHistory.length})
          </h4>
          <div className="bg-canvas rounded-lg border border-border overflow-hidden max-h-64 overflow-y-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-muted">
                  <th className="px-3 py-2 text-left">Timestamp</th>
                  <th className="px-3 py-2 text-left">Protocol</th>
                  <th className="px-3 py-2 text-left">Source IP</th>
                  <th className="px-3 py-2 text-left">Files</th>
                  <th className="px-3 py-2 text-left">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {session.connectionHistory.map((conn, i) => (
                  <tr key={i} className="hover:bg-hover">
                    <td className="px-3 py-2 text-secondary">
                      {conn.timestamp ? format(new Date(conn.timestamp), 'MMM dd, HH:mm:ss') : '-'}
                    </td>
                    <td className="px-3 py-2">
                      <span className={`badge ${protocolBadge(conn.protocol)}`}>{conn.protocol || '-'}</span>
                    </td>
                    <td className="px-3 py-2 font-mono text-secondary">{conn.sourceIp || '-'}</td>
                    <td className="px-3 py-2 text-secondary">{conn.fileCount ?? '-'}</td>
                    <td className="px-3 py-2">
                      <span className={`badge ${conn.status === 'SUCCESS' ? 'badge-green' : 'badge-red'}`}>
                        {conn.status || '-'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Suggested config */}
      {session.suggestedConfig && (
        <div>
          <h4 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">Suggested Account Configuration</h4>
          <pre className="bg-canvas rounded-lg p-4 text-xs font-mono text-secondary overflow-auto max-h-48 border border-border">
            {JSON.stringify(session.suggestedConfig, null, 2)}
          </pre>
        </div>
      )}

      {/* Timestamps */}
      <div className="grid grid-cols-2 gap-4 text-xs">
        <div>
          <span className="text-muted">First Seen</span>
          <p className="text-secondary mt-0.5">
            {session.createdAt
              ? format(new Date(session.createdAt), 'MMM dd, yyyy HH:mm:ss')
              : session.detectedAt
              ? format(new Date(session.detectedAt), 'MMM dd, yyyy HH:mm:ss')
              : '-'}
          </p>
        </div>
        <div>
          <span className="text-muted">Last Activity</span>
          <p className="text-secondary mt-0.5">
            {session.lastActivityAt
              ? format(new Date(session.lastActivityAt), 'MMM dd, yyyy HH:mm:ss')
              : session.updatedAt
              ? format(new Date(session.updatedAt), 'MMM dd, yyyy HH:mm:ss')
              : '-'}
          </p>
        </div>
      </div>
    </div>
  )
}
