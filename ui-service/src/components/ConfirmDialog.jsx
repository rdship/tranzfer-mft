import { useEffect, useRef } from 'react'
import { ExclamationTriangleIcon, InformationCircleIcon, ShieldCheckIcon } from '@heroicons/react/24/outline'
import useTimeOfDayBackdrop from '../hooks/useTimeOfDayBackdrop'

/**
 * ConfirmDialog — unified confirmation modal used anywhere a destructive,
 * warning, or neutral decision needs user approval. Replaces the ad-hoc
 * confirm() calls and hand-rolled modals scattered across 15+ pages.
 *
 * Design principles (locked):
 *   • Speed         — single render, no external deps beyond heroicons
 *   • Stability     — always portal-style centered, never clipped
 *   • Transparency  — title + message + the exact record being acted on
 *   • Minimalism    — one icon, one accent color per variant
 *   • Accessibility — focus trap on confirm button, Esc closes, Enter confirms
 *   • Guidance      — label buttons with verbs ("Delete" not "OK")
 *
 * Usage:
 *   <ConfirmDialog
 *     open={open}
 *     variant="danger"
 *     title="Delete partner?"
 *     message="This permanently removes Partner ACME Corp and 14 accounts."
 *     confirmLabel="Delete partner"
 *     cancelLabel="Keep"
 *     onConfirm={() => deletePartner(id)}
 *     onCancel={() => setOpen(false)}
 *   />
 *
 * Variants:
 *   danger  — red accent, warning icon. For destructive/irreversible actions.
 *   warning — yellow accent, warning icon. For actions with side effects.
 *   info    — blue accent, info icon. For neutral confirmations.
 *   success — green accent, check icon. For positive confirmations.
 */
const VARIANT_STYLES = {
  danger:  { accent: 'rgb(239, 68, 68)',  Icon: ExclamationTriangleIcon, btnBg: 'rgb(220, 38, 38)', btnHover: 'rgb(185, 28, 28)' },
  warning: { accent: 'rgb(234, 179, 8)',  Icon: ExclamationTriangleIcon, btnBg: 'rgb(202, 138, 4)', btnHover: 'rgb(161, 98, 7)' },
  info:    { accent: 'rgb(96, 165, 250)', Icon: InformationCircleIcon,   btnBg: 'rgb(59, 130, 246)', btnHover: 'rgb(37, 99, 235)' },
  success: { accent: 'rgb(74, 222, 128)', Icon: ShieldCheckIcon,         btnBg: 'rgb(34, 197, 94)', btnHover: 'rgb(22, 163, 74)' },
}

export default function ConfirmDialog({
  open,
  variant = 'danger',
  title,
  message,
  evidence = null,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  loading = false,
}) {
  const confirmRef = useRef(null)
  const backdrop = useTimeOfDayBackdrop()
  const { accent, Icon, btnBg, btnHover } = VARIANT_STYLES[variant] || VARIANT_STYLES.info

  // Keyboard: Esc cancels, Enter confirms (unless already loading)
  useEffect(() => {
    if (!open) return
    const handler = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); onCancel?.() }
      else if (e.key === 'Enter' && !loading) { e.preventDefault(); onConfirm?.() }
    }
    window.addEventListener('keydown', handler)
    // Focus the confirm button
    setTimeout(() => confirmRef.current?.focus(), 50)
    return () => window.removeEventListener('keydown', handler)
  }, [open, loading, onCancel, onConfirm])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center px-4"
      style={backdrop.style}
      onClick={onCancel}
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      data-tod={backdrop.label}
    >
      <div
        className="w-full max-w-md rounded-xl overflow-hidden shadow-2xl"
        style={{ background: 'rgb(18, 18, 22)', border: '1px solid rgb(30, 30, 36)' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Body */}
        <div className="p-5">
          <div className="flex items-start gap-3">
            <div
              className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0"
              style={{ background: `${accent}15` }}
            >
              <Icon className="w-5 h-5" style={{ color: accent }} />
            </div>
            <div className="flex-1 min-w-0">
              {title && (
                <h3 id="confirm-dialog-title" className="text-base font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
                  {title}
                </h3>
              )}
              {message && (
                <p className="text-sm mt-1" style={{ color: 'rgb(148, 163, 184)' }}>
                  {message}
                </p>
              )}
              {evidence && (
                <pre
                  className="text-[11px] mt-2 p-2 rounded font-mono overflow-x-auto max-h-24"
                  style={{ background: 'rgb(var(--canvas))', color: 'rgb(148, 163, 184)' }}
                >
                  {typeof evidence === 'string' ? evidence : JSON.stringify(evidence, null, 2)}
                </pre>
              )}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div
          className="flex items-center justify-end gap-2 px-5 py-3"
          style={{ borderTop: '1px solid rgb(30, 30, 36)', background: 'rgb(14, 14, 18)' }}
        >
          <button
            onClick={onCancel}
            disabled={loading}
            className="px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
            style={{
              background: 'transparent',
              border: '1px solid rgb(48, 48, 56)',
              color: 'rgb(var(--tx-primary))',
              opacity: loading ? 0.4 : 1,
            }}
          >
            {cancelLabel}
          </button>
          <button
            ref={confirmRef}
            onClick={onConfirm}
            disabled={loading}
            className="px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
            style={{
              background: btnBg,
              color: '#fff',
              opacity: loading ? 0.6 : 1,
            }}
            onMouseEnter={(e) => { if (!loading) e.currentTarget.style.background = btnHover }}
            onMouseLeave={(e) => { if (!loading) e.currentTarget.style.background = btnBg }}
          >
            {loading ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
