import { ExclamationTriangleIcon, XMarkIcon, ArrowPathIcon } from '@heroicons/react/24/outline'

/**
 * SaveFailBanner — the standard "your save didn't go through, but your
 * work is safe" banner. Renders inline at the top of a form whenever a
 * mutation fails. Together with useFormDraft, it guarantees the user
 * never has to re-type what they already filled in.
 *
 * Behavior:
 *   - Warm amber tone (matching VIP-FORMS rule 4 — errors are amber,
 *     not red). Red is reserved for actually-broken things.
 *   - Shows the backend error message verbatim (trimmed) so operators
 *     can self-diagnose without digging into dev tools.
 *   - "Try again" button re-submits the last attempt (caller's mutation).
 *   - "Dismiss" button hides the banner but keeps the form state.
 *
 * Usage:
 *   {saveError && (
 *     <SaveFailBanner
 *       error={saveError}
 *       onRetry={() => mutation.mutate(form)}
 *       onDismiss={() => setSaveError(null)}
 *     />
 *   )}
 */
export default function SaveFailBanner({
  error,
  onRetry,
  onDismiss,
  title = 'Your save didn\'t go through',
}) {
  if (!error) return null
  const msg = error?.response?.data?.message
          || error?.response?.data?.error
          || error?.message
          || 'The backend rejected the request.'
  const status = error?.response?.status
  const hint = status === 401
    ? 'Your session expired — sign in again to retry.'
    : status === 403
    ? 'You don\'t have permission for this action. Ask an admin to grant access.'
    : status === 409
    ? 'Something already exists with these details. Change a unique field and try again.'
    : status >= 500
    ? 'The backend service may be down. Your form is preserved — retry when it\'s back.'
    : 'Review the error message below and adjust the form, then retry.'

  return (
    <div
      className="rounded-xl p-4 flex items-start gap-3 animate-page"
      style={{
        background: 'rgba(245, 158, 11, 0.08)',
        border: '1px solid rgba(245, 158, 11, 0.35)',
      }}
    >
      <ExclamationTriangleIcon
        className="w-5 h-5 flex-shrink-0 mt-0.5"
        style={{ color: 'rgb(245, 158, 11)' }}
      />
      <div className="flex-1 text-xs" style={{ color: 'rgb(var(--tx-primary))' }}>
        <div className="font-semibold mb-0.5">{title}</div>
        <div className="mb-1" style={{ color: 'rgb(148, 163, 184)' }}>
          {hint} Your form contents are still here — nothing was lost.
        </div>
        <pre
          className="text-[10px] font-mono rounded p-2 mb-2 overflow-x-auto max-h-24 overflow-y-auto"
          style={{ background: 'rgb(var(--canvas))', color: 'rgb(203, 213, 225)' }}
        >
          {status ? `[${status}] ` : ''}{msg}
        </pre>
        <div className="flex items-center gap-2">
          {onRetry && (
            <button
              onClick={onRetry}
              className="inline-flex items-center gap-1.5 px-3 py-1 text-[11px] font-semibold rounded-lg"
              style={{ background: 'rgb(245, 158, 11)', color: '#0b0b0e' }}
            >
              <ArrowPathIcon className="w-3 h-3" />
              Try save again
            </button>
          )}
          {onDismiss && (
            <button
              onClick={onDismiss}
              className="inline-flex items-center gap-1 px-2 py-1 text-[11px]"
              style={{ color: 'rgb(148, 163, 184)' }}
            >
              <XMarkIcon className="w-3 h-3" />
              Dismiss
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
