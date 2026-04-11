import { useCallback } from 'react'

/**
 * useEnterAdvances — keyboard helper for "Netflix-grade" form flow.
 *
 * When attached to a form via onKeyDown, pressing Enter in any input
 * advances focus to the next input in tab order — EXCEPT on the last
 * input, where Enter submits the form (same as clicking the primary
 * button). Shift+Enter always submits regardless of field position.
 *
 * This mirrors the behavior users expect from top-tier product forms:
 *   • Netflix onboarding: Enter → next step
 *   • Stripe checkout: Enter → next field → finally submits
 *   • Linear new-project: Enter → next → last field Enter = create
 *
 * textareas are excluded — Enter in a textarea is a newline, not advance.
 *
 * Usage:
 *   const onKeyDown = useEnterAdvances({ onSubmit: () => mutation.mutate(form) })
 *   <form onKeyDown={onKeyDown}>
 *     <input ... />
 *     <input ... />
 *     <input ... />   // Enter here submits, because no more inputs follow
 *   </form>
 */
export default function useEnterAdvances({ onSubmit }) {
  return useCallback((e) => {
    if (e.key !== 'Enter') return
    const target = e.target
    if (!target) return

    // Enter in textarea = newline, not advance
    if (target.tagName === 'TEXTAREA') return

    // Shift+Enter always submits
    if (e.shiftKey) {
      e.preventDefault()
      onSubmit?.()
      return
    }

    // Find the next tabbable input in the same form
    const form = target.closest('form')
    if (!form) return

    const focusable = Array.from(
      form.querySelectorAll(
        'input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])'
      )
    )

    const currentIdx = focusable.indexOf(target)
    if (currentIdx === -1) return

    // Find next input/select (not button)
    for (let i = currentIdx + 1; i < focusable.length; i++) {
      const el = focusable[i]
      if (el.tagName !== 'BUTTON') {
        e.preventDefault()
        el.focus()
        if (typeof el.select === 'function') el.select()
        return
      }
    }

    // No more inputs — submit
    e.preventDefault()
    onSubmit?.()
  }, [onSubmit])
}
