import React, { useEffect, useMemo, useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import {
  XMarkIcon, SparklesIcon, BoltIcon, ChatBubbleLeftRightIcon,
  ExclamationTriangleIcon, CheckCircleIcon, ClockIcon,
  PlayIcon, StopIcon, PauseIcon, ArrowPathIcon, ArrowRightCircleIcon,
} from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

import {
  analyzeActivity, diagnoseActivity, suggestActivityActions, chatActivity,
} from '../api/ai'
import {
  restartExecution, restartFromStep, skipStep, terminateExecution,
  pauseExecution, resumeExecution,
} from '../api/executions'
import { onboardingApi } from '../api/client'

/**
 * R108 Activity Copilot Drawer — the "admins absolutely love it" surface.
 *
 * A slide-in right-edge panel with three tabs:
 *   • Summary   — plain-English narrative + milestones + highlights + metrics
 *   • Diagnose  — root cause + interpretation + one-click recommended actions
 *   • Chat      — scoped Q&A ("why is this stuck?", "can I retry step 3?")
 *
 * All data comes from /api/v1/ai/activity/* (R107). Action buttons call
 * /api/flow-executions/* directly (R106 + prior controls) so the UI stays
 * functional even if the copilot is disabled.
 */
export default function AICopilotDrawer({ trackId, open, onClose, onActionCompleted }) {
  const [tab, setTab] = useState('summary')
  const [chatInput, setChatInput] = useState('')
  const [chatLog, setChatLog] = useState([])

  // Reset state when the targeted transfer changes
  useEffect(() => {
    if (trackId) {
      setTab('summary')
      setChatInput('')
      setChatLog([])
    }
  }, [trackId])

  const analysis = useQuery({
    queryKey: ['ai-activity-analyze', trackId],
    queryFn: () => analyzeActivity(trackId),
    enabled: !!trackId && open,
    staleTime: 30_000,
  })

  const diagnosis = useQuery({
    queryKey: ['ai-activity-diagnose', trackId],
    queryFn: () => diagnoseActivity(trackId),
    enabled: !!trackId && open && (tab === 'diagnose' || tab === 'summary'),
    staleTime: 30_000,
  })

  const askMut = useMutation({
    mutationFn: (message) => chatActivity(trackId, message),
    onSuccess: (data, message) => {
      setChatLog(prev => [...prev,
        { role: 'user', text: message },
        { role: 'assistant', text: data.answer, context: data.context }])
      setChatInput('')
    },
    onError: (err) => {
      toast.error('Copilot unavailable: ' + (err.response?.data?.message || err.message))
    },
  })

  const actionMut = useMutation({
    mutationFn: async ({ action, apiPath }) => {
      if (!apiPath) return null
      // apiPath is a relative URL; onboardingApi.baseURL handles the prefix
      return await onboardingApi.post(apiPath).then(r => r.data)
    },
    onSuccess: (_data, { action }) => {
      toast.success(`${action.replace(/_/g, ' ')} queued`)
      analysis.refetch()
      diagnosis.refetch()
      onActionCompleted?.()
    },
    onError: (err) => {
      toast.error('Action failed: ' + (err.response?.data?.message || err.message))
    },
  })

  if (!open || !trackId) return null

  return (
    <div className="fixed inset-0 z-50 flex">
      <div className="flex-1 bg-black/30" onClick={onClose} />
      <div className="w-full max-w-xl bg-white dark:bg-slate-900 shadow-2xl flex flex-col">
        <Header trackId={trackId} onClose={onClose} />
        <TabBar tab={tab} setTab={setTab} />
        <div className="flex-1 overflow-y-auto p-4">
          {tab === 'summary' && (
            <SummaryView
              analysis={analysis}
              diagnosis={diagnosis}
              onAction={(a) => actionMut.mutate(a)}
            />
          )}
          {tab === 'diagnose' && (
            <DiagnoseView
              diagnosis={diagnosis}
              onAction={(a) => actionMut.mutate(a)}
              actionLoading={actionMut.isPending}
            />
          )}
          {tab === 'chat' && (
            <ChatView
              trackId={trackId}
              chatLog={chatLog}
              chatInput={chatInput}
              setChatInput={setChatInput}
              onSend={(msg) => askMut.mutate(msg)}
              sending={askMut.isPending}
            />
          )}
        </div>
      </div>
    </div>
  )
}

function Header({ trackId, onClose }) {
  return (
    <div className="flex items-center justify-between px-4 py-3 border-b dark:border-slate-700">
      <div className="flex items-center gap-2">
        <SparklesIcon className="w-5 h-5 text-indigo-500" />
        <div>
          <div className="text-sm font-semibold text-primary">AI Activity Copilot</div>
          <div className="text-xs text-secondary font-mono">{trackId}</div>
        </div>
      </div>
      <button onClick={onClose} className="text-muted hover:text-primary">
        <XMarkIcon className="w-5 h-5" />
      </button>
    </div>
  )
}

function TabBar({ tab, setTab }) {
  const tabs = [
    { id: 'summary', label: 'Summary', icon: SparklesIcon },
    { id: 'diagnose', label: 'Diagnose', icon: BoltIcon },
    { id: 'chat', label: 'Chat', icon: ChatBubbleLeftRightIcon },
  ]
  return (
    <div className="flex border-b dark:border-slate-700">
      {tabs.map(t => {
        const Icon = t.icon
        const active = tab === t.id
        return (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`flex-1 flex items-center justify-center gap-1.5 py-2.5 text-sm font-medium transition-colors ${
              active
                ? 'text-indigo-600 border-b-2 border-indigo-500 bg-indigo-50/60 dark:bg-indigo-900/20'
                : 'text-secondary hover:text-primary'
            }`}
          >
            <Icon className="w-4 h-4" />
            {t.label}
          </button>
        )
      })}
    </div>
  )
}

function SummaryView({ analysis, diagnosis, onAction }) {
  if (analysis.isLoading) return <SkeletonBlock label="Analyzing transfer…" />
  if (analysis.isError) return <ErrorBlock message={analysis.error?.message} />
  const a = analysis.data
  if (!a || a.currentState === 'NOT_FOUND') {
    return <EmptyBlock message="No activity data available for this transfer." />
  }
  return (
    <div className="space-y-4 text-sm">
      <Section title="Summary">
        <p className="text-primary leading-relaxed">{a.summary}</p>
      </Section>
      <Section title="Current state">
        <StateBadge state={a.currentState} />
      </Section>
      {a.highlights?.length > 0 && (
        <Section title="Highlights">
          <ul className="space-y-1">
            {a.highlights.map((h, i) => (
              <li key={i} className="flex gap-2 text-secondary">
                <CheckCircleIcon className="w-4 h-4 text-green-500 shrink-0 mt-0.5" />
                <span>{h}</span>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {a.milestones?.length > 0 && (
        <Section title="Milestones">
          <ol className="space-y-1.5 text-xs">
            {a.milestones.map((m, i) => (
              <li key={i} className="flex gap-2">
                <span className="text-muted shrink-0 w-36 font-mono">
                  {m.at ? new Date(m.at).toLocaleTimeString() : '—'}
                </span>
                <span className="text-secondary">
                  <span className="font-medium text-primary">{m.event}</span> — {m.detail}
                </span>
              </li>
            ))}
          </ol>
        </Section>
      )}
      {a.metrics && Object.keys(a.metrics).length > 0 && (
        <Section title="Metrics">
          <dl className="grid grid-cols-2 gap-1 text-xs">
            {Object.entries(a.metrics).map(([k, v]) => (
              <React.Fragment key={k}>
                <dt className="text-muted">{k}</dt>
                <dd className="text-primary font-mono">{String(v)}</dd>
              </React.Fragment>
            ))}
          </dl>
        </Section>
      )}
      {diagnosis.data?.recommendedActions?.length > 0 && (
        <Section title="Suggested next step">
          <ActionButton
            action={diagnosis.data.recommendedActions[0]}
            onClick={() => onAction(diagnosis.data.recommendedActions[0])}
            primary
          />
        </Section>
      )}
    </div>
  )
}

function DiagnoseView({ diagnosis, onAction, actionLoading }) {
  if (diagnosis.isLoading) return <SkeletonBlock label="Diagnosing…" />
  if (diagnosis.isError) return <ErrorBlock message={diagnosis.error?.message} />
  const d = diagnosis.data
  if (!d) return <EmptyBlock message="No diagnosis available." />
  return (
    <div className="space-y-4 text-sm">
      <Section title="Root cause">
        <div className="flex items-start gap-2">
          <CategoryIcon category={d.category} />
          <div className="flex-1">
            <div className="font-medium text-primary">{d.rootCause}</div>
            {d.stepIndex != null && (
              <div className="text-xs text-muted mt-0.5">
                step {d.stepIndex} · {d.stepType} · category {d.category}
              </div>
            )}
          </div>
        </div>
      </Section>
      <Section title="Interpretation">
        <p className="text-secondary leading-relaxed">{d.interpretation}</p>
      </Section>
      {d.errorMessage && (
        <Section title="Raw error">
          <pre className="text-xs bg-red-50 dark:bg-red-950/30 text-red-800 dark:text-red-300 p-2 rounded border border-red-200 dark:border-red-900 overflow-x-auto whitespace-pre-wrap">
            {d.errorMessage}
          </pre>
        </Section>
      )}
      {d.recommendedActions?.length > 0 && (
        <Section title={`Recommended actions (${d.recommendedActions.length})`}>
          <div className="space-y-2">
            {d.recommendedActions.map((a, i) => (
              <ActionButton
                key={i}
                action={a}
                onClick={() => onAction(a)}
                primary={i === 0}
                disabled={actionLoading || !a.apiPath}
              />
            ))}
          </div>
        </Section>
      )}
    </div>
  )
}

function ChatView({ trackId, chatLog, chatInput, setChatInput, onSend, sending }) {
  const suggestedQuestions = [
    'What happened to this transfer?',
    'Why did it fail?',
    'Can I safely retry?',
    'How long is it taking?',
  ]
  const handleSubmit = (e) => {
    e.preventDefault()
    const t = chatInput.trim()
    if (t) onSend(t)
  }
  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 space-y-3 overflow-y-auto text-sm">
        {chatLog.length === 0 && (
          <div className="space-y-2">
            <p className="text-muted text-xs">
              Ask a question about <span className="font-mono">{trackId}</span>. The copilot uses the live
              flow events, step details, and audit trail.
            </p>
            <div className="flex flex-wrap gap-1.5">
              {suggestedQuestions.map(q => (
                <button
                  key={q}
                  onClick={() => onSend(q)}
                  disabled={sending}
                  className="text-xs px-2 py-1 rounded-full border border-indigo-200 dark:border-indigo-800 bg-indigo-50/60 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-300 hover:bg-indigo-100"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        )}
        {chatLog.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[85%] px-3 py-2 rounded-lg ${
              msg.role === 'user'
                ? 'bg-indigo-500 text-white'
                : 'bg-slate-100 dark:bg-slate-800 text-primary'
            }`}>
              <div className="whitespace-pre-wrap leading-relaxed">{msg.text}</div>
              {msg.context && (
                <div className="text-[10px] opacity-70 mt-1 font-mono">{msg.context}</div>
              )}
            </div>
          </div>
        ))}
        {sending && (
          <div className="text-xs text-muted italic">Copilot thinking…</div>
        )}
      </div>
      <form onSubmit={handleSubmit} className="mt-3 flex gap-2 border-t dark:border-slate-700 pt-3">
        <input
          type="text"
          value={chatInput}
          onChange={e => setChatInput(e.target.value)}
          placeholder="Ask about this transfer…"
          className="flex-1 px-3 py-2 text-sm rounded border dark:border-slate-600 dark:bg-slate-800"
          disabled={sending}
        />
        <button
          type="submit"
          disabled={sending || !chatInput.trim()}
          className="px-3 py-2 text-sm rounded bg-indigo-500 text-white disabled:opacity-50"
        >
          Send
        </button>
      </form>
    </div>
  )
}

// ── Small UI primitives ─────────────────────────────────────────────────────

function Section({ title, children }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-muted font-semibold mb-1.5">{title}</div>
      {children}
    </div>
  )
}

function ActionButton({ action, onClick, primary, disabled }) {
  const Icon = iconForAction(action.action)
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`w-full flex items-start gap-2 text-left px-3 py-2 rounded border transition-colors ${
        primary
          ? 'border-indigo-300 bg-indigo-50 dark:bg-indigo-900/20 hover:bg-indigo-100 text-indigo-700 dark:text-indigo-200'
          : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 text-primary'
      } disabled:opacity-50 disabled:cursor-not-allowed`}
    >
      <Icon className="w-4 h-4 shrink-0 mt-0.5" />
      <div className="flex-1">
        <div className="text-sm font-medium">{action.action.replace(/_/g, ' ')}</div>
        <div className="text-xs text-secondary mt-0.5">{action.description}</div>
        {action.rationale && (
          <div className="text-[10px] text-muted italic mt-0.5">{action.rationale}</div>
        )}
      </div>
      <div className="shrink-0 text-[10px] font-mono text-muted">
        {Math.round(action.confidence * 100)}%
      </div>
    </button>
  )
}

function iconForAction(action) {
  switch (action) {
    case 'RESTART_FROM_STEP':
    case 'RESTART_FROM_START':
      return ArrowPathIcon
    case 'SKIP_STEP':
      return ArrowRightCircleIcon
    case 'RESUME':
      return PlayIcon
    case 'TERMINATE':
      return StopIcon
    case 'WAIT':
      return ClockIcon
    default:
      return ExclamationTriangleIcon
  }
}

function CategoryIcon({ category }) {
  const spec = {
    NETWORK: { icon: BoltIcon, className: 'text-orange-500' },
    AUTH: { icon: ExclamationTriangleIcon, className: 'text-red-500' },
    CONFIG: { icon: ExclamationTriangleIcon, className: 'text-amber-500' },
    SCREENING_BLOCK: { icon: ExclamationTriangleIcon, className: 'text-red-600' },
    CONTENT: { icon: ExclamationTriangleIcon, className: 'text-amber-500' },
    STUCK: { icon: ClockIcon, className: 'text-yellow-500' },
    HEALTHY: { icon: CheckCircleIcon, className: 'text-green-500' },
  }[category] || { icon: ExclamationTriangleIcon, className: 'text-slate-400' }
  const Icon = spec.icon
  return <Icon className={`w-5 h-5 shrink-0 ${spec.className}`} />
}

function StateBadge({ state }) {
  const color = state === 'HEALTHY' || state?.startsWith('COMPLETED')
    ? 'bg-green-100 text-green-800'
    : state?.includes('FAILED') ? 'bg-red-100 text-red-800'
    : state?.includes('PAUSED') ? 'bg-yellow-100 text-yellow-800'
    : 'bg-blue-100 text-blue-800'
  return <span className={`inline-block px-2 py-0.5 rounded text-xs font-mono ${color}`}>{state}</span>
}

function SkeletonBlock({ label }) {
  return (
    <div className="flex items-center gap-2 text-muted text-sm">
      <div className="w-4 h-4 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
      {label}
    </div>
  )
}

function ErrorBlock({ message }) {
  return (
    <div className="text-sm text-red-600">
      <div className="font-medium">Copilot unavailable</div>
      <div className="text-xs">{message || 'Unknown error'}</div>
    </div>
  )
}

function EmptyBlock({ message }) {
  return <div className="text-sm text-muted italic">{message}</div>
}
