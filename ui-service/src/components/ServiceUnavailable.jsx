import { ExclamationTriangleIcon, ArrowPathIcon } from '@heroicons/react/24/outline'

/**
 * ServiceUnavailable — standard empty/error state for any page whose
 * backend service is down or unreachable. Every page should use this
 * component instead of rolling its own error banner, so the UX is
 * consistent (and testers know what they're looking at).
 *
 * Design principles (locked):
 *   • Speed         — single paint, no animations beyond the retry spinner
 *   • Stability     — always renders, never crashes on undefined props
 *   • Resilience    — a retry callback unsticks the caller
 *   • Transparency  — service name + error message are displayed, not hidden
 *   • Minimalism    — single card, accent only on the retry button
 *   • Guidance      — tells the user exactly what to try next
 *
 * Usage:
 *   {isError && <ServiceUnavailable
 *     service="edi-converter"
 *     port={8095}
 *     error={error}
 *     onRetry={() => queryClient.invalidateQueries(['edi-maps'])}
 *   />}
 */
export default function ServiceUnavailable({
  service = 'Backend service',
  port = null,
  error = null,
  onRetry = null,
  title = null,
  hint = null,
}) {
  const errMsg = error?.message || error?.response?.data?.message || (typeof error === 'string' ? error : null)

  return (
    <div className="flex items-center justify-center py-16 px-4">
      <div
        className="max-w-md w-full rounded-xl p-6 text-center"
        style={{
          background: 'rgb(var(--surface))',
          border: '1px solid rgba(239, 68, 68, 0.25)',
        }}
      >
        <div
          className="inline-flex items-center justify-center w-12 h-12 rounded-full mb-3"
          style={{ background: 'rgba(239, 68, 68, 0.1)' }}
        >
          <ExclamationTriangleIcon className="w-6 h-6" style={{ color: 'rgb(248, 113, 113)' }} />
        </div>
        <h3 className="text-base font-bold mb-1" style={{ color: 'rgb(var(--tx-primary))' }}>
          {title || `${service} unavailable`}
        </h3>
        <p className="text-xs mb-3" style={{ color: 'rgb(148, 163, 184)' }}>
          {hint ||
            `Could not reach ${service}${port ? ` (:${port})` : ''}. ` +
              'Check that the service is running, then retry.'}
        </p>
        {errMsg && (
          <pre
            className="text-[10px] text-left rounded p-2 mb-3 overflow-x-auto font-mono"
            style={{ background: 'rgb(var(--canvas))', color: 'rgb(148, 163, 184)' }}
          >
            {errMsg}
          </pre>
        )}
        {typeof onRetry === 'function' && (
          <button
            onClick={onRetry}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
            style={{
              background: 'rgb(var(--accent, 79 70 229))',
              color: '#fff',
            }}
          >
            <ArrowPathIcon className="w-3.5 h-3.5" />
            Retry
          </button>
        )}
      </div>
    </div>
  )
}
