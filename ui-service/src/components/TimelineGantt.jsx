import { useMemo } from 'react'
import {
  CheckCircleIcon,
  XCircleIcon,
  ClockIcon,
  ExclamationTriangleIcon,
} from '@heroicons/react/24/outline'

/**
 * Gantt chart visualization of flow steps from the Dynamic Flow Fabric.
 *
 * Props:
 * - steps: array from /api/fabric/track/{trackId}/timeline response
 * - totalDurationMs: total flow duration (for scaling)
 */
export default function TimelineGantt({ steps, totalDurationMs }) {
  const bars = useMemo(() => {
    if (!steps || steps.length === 0) return []

    // Find start time (first step's startedAt)
    const firstStarted = steps[0]?.startedAt ? new Date(steps[0].startedAt).getTime() : Date.now()
    const now = Date.now()
    const totalMs = Math.max(totalDurationMs || 1, 1000, now - firstStarted)

    return steps.map(step => {
      const started = step.startedAt ? new Date(step.startedAt).getTime() : firstStarted
      const completed = step.completedAt ? new Date(step.completedAt).getTime() : now
      const offsetMs = started - firstStarted
      const widthMs = Math.max(completed - started, 50) // min 50ms for visibility

      return {
        ...step,
        offsetPct: (offsetMs / totalMs) * 100,
        widthPct: (widthMs / totalMs) * 100,
        durationMs: completed - started,
      }
    })
  }, [steps, totalDurationMs])

  const colorFor = (status) => {
    switch (status) {
      case 'COMPLETED':
        return { bg: 'bg-green-500/60', border: 'border-green-500', text: 'text-green-400' }
      case 'IN_PROGRESS':
        return { bg: 'bg-yellow-500/60 animate-pulse', border: 'border-yellow-500', text: 'text-yellow-400' }
      case 'FAILED':
        return { bg: 'bg-red-500/60', border: 'border-red-500', text: 'text-red-400' }
      case 'ABANDONED':
        return { bg: 'bg-gray-500/40', border: 'border-gray-500', text: 'text-[rgb(var(--tx-muted))]' }
      default:
        return { bg: 'bg-gray-500/40', border: 'border-gray-500', text: 'text-[rgb(var(--tx-muted))]' }
    }
  }

  const iconFor = (status) => {
    switch (status) {
      case 'COMPLETED':
        return <CheckCircleIcon className="w-3.5 h-3.5 inline" />
      case 'IN_PROGRESS':
        return <ClockIcon className="w-3.5 h-3.5 inline" />
      case 'FAILED':
        return <XCircleIcon className="w-3.5 h-3.5 inline" />
      default:
        return <ExclamationTriangleIcon className="w-3.5 h-3.5 inline" />
    }
  }

  if (!steps || steps.length === 0) {
    return (
      <div className="text-[rgb(var(--tx-secondary))] text-sm italic p-4">
        No fabric checkpoint data available for this file.
        {' '}(File processed before fabric was enabled, or fabric is disabled.)
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {bars.map(step => {
        const colors = colorFor(step.status)
        const icon = iconFor(step.status)
        return (
          <div key={`${step.index}-${step.type}`} className="group">
            <div className="flex items-center gap-3 mb-1">
              <span className="text-xs text-[rgb(var(--tx-secondary))] w-8 font-mono">#{step.index}</span>
              <span className={`text-sm font-medium ${colors.text} flex-shrink-0`}>
                {icon} {step.type}
              </span>
              <span className="text-xs text-[rgb(var(--tx-muted))] ml-auto">
                {step.durationMs ? `${step.durationMs}ms` : 'pending'}
                {step.instance && ` \u00B7 ${step.instance}`}
              </span>
            </div>
            {/* The bar */}
            <div className="relative h-6 bg-[rgb(var(--surface))] rounded overflow-hidden">
              <div
                className={`absolute top-0 h-full ${colors.bg} border-l-2 ${colors.border}`}
                style={{ left: `${step.offsetPct}%`, width: `${step.widthPct}%` }}
                title={`${step.type} \u00B7 ${step.durationMs}ms \u00B7 ${step.instance || ''}`}
              />
            </div>
            {step.errorMessage && (
              <div className="mt-1 text-xs text-red-400 bg-red-500/10 px-2 py-1 rounded">
                <span className="font-semibold">{step.errorCategory}:</span> {step.errorMessage}
              </div>
            )}
            {step.leaseRemainingMs !== undefined && step.status === 'IN_PROGRESS' && (
              <div className="mt-1 text-xs text-yellow-400">
                Lease expires in {Math.max(0, Math.floor(step.leaseRemainingMs / 1000))}s
                {step.isStuck && <span className="ml-2 text-red-400 font-semibold">\u26A0 STUCK</span>}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
