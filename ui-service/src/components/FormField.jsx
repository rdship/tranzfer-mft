import {
  useId,
  cloneElement,
  isValidElement,
  Children,
  useState,
  useRef,
  useEffect,
} from 'react'
import { CheckCircleIcon, QuestionMarkCircleIcon } from '@heroicons/react/24/outline'

/**
 * FormField — the "VIP form" primitive.
 *
 * Design principle: treat users as gods. Every form should feel like a
 * calm personal assistant guiding you through a task — never a nagging
 * inspector flagging your mistakes in angry red.
 *
 * What that looks like in practice (cribbed from Netflix / Stripe / Linear):
 *
 *   • Required fields are marked by a tiny muted "required" pill next to
 *     the label — no red asterisk, no shouting.
 *
 *   • Helper text is ALWAYS visible below the field, so users know WHY
 *     a field exists, not just THAT it's required.
 *
 *   • Inline help tooltip (<InfoHint>) can be dropped next to the label
 *     to reveal a longer paragraph on hover / click for complex fields.
 *
 *   • Live success cue: as the user types, the field shows a small green
 *     check the moment it becomes valid. No need to blur or submit.
 *
 *   • Errors render in warm amber (not alarm red) with a soft left-border
 *     stripe and a message that starts with what the user CAN DO, not
 *     what they did wrong.
 *
 *   • When validation fails on submit, useGentleValidation smooth-scrolls
 *     to the first failing field and focuses it — no harsh popups.
 *
 * Usage:
 *   <FormField
 *     label="Partner name"
 *     required
 *     name="name"                       // needed by useGentleValidation scroll/focus
 *     helper="How you'll recognize this partner across reports"
 *     tooltip="Must be unique across your tenant. Usually the legal entity name."
 *     error={errors.name}
 *     valid={!errors.name && !!form.name}
 *   >
 *     <input value={form.name} onChange={...} />
 *   </FormField>
 */
export default function FormField({
  label,
  required,
  error,
  helper,
  tooltip,
  valid,
  children,
  name,
  className = '',
  samples,
  onSampleClick,
}) {
  const autoId = useId()
  const fieldId = `ff-${autoId}`

  // Clone the first input/select/textarea child to inject:
  //   1. The generated id (so clicking the label focuses the input)
  //   2. data-field-name + data-field-required (so useGentleValidation
  //      can find this field via selectors without prop-drilling refs)
  //   3. ARIA attributes for screen readers
  const enhancedChildren = Children.map(children, (child, idx) => {
    if (idx === 0 && isValidElement(child) && !child.props.id) {
      const tag = typeof child.type === 'string' ? child.type : ''
      if (['input', 'select', 'textarea'].includes(tag)) {
        return cloneElement(child, {
          id: fieldId,
          'data-field-name': name,
          'data-field-required': required ? 'true' : undefined,
          'aria-invalid': error ? 'true' : undefined,
          'aria-describedby': error
            ? `${fieldId}-err`
            : helper
              ? `${fieldId}-help`
              : undefined,
        })
      }
    }
    return child
  })

  return (
    <div
      data-field-wrapper={name}
      className={className}
      style={{
        // Calm left-border cue when there's an error. 2px amber stripe,
        // subtle left padding so content doesn't jump.
        paddingLeft: error ? '0.5rem' : 0,
        borderLeft: error ? '2px solid rgb(245, 158, 11)' : '2px solid transparent',
        transition: 'border-color 200ms ease, padding-left 200ms ease',
      }}
    >
      {label && (
        <label
          htmlFor={fieldId}
          className="flex items-center gap-2 text-sm font-medium mb-1"
          style={{ color: 'rgb(var(--tx-primary))' }}
        >
          <span>{label}</span>

          {required && (
            <span
              className="text-[9px] uppercase tracking-wide font-bold px-1.5 py-0.5 rounded-full"
              style={{
                color: 'rgb(148, 163, 184)',
                background: 'rgb(30, 30, 36)',
                letterSpacing: '0.08em',
              }}
              aria-label="required"
              title="This field is required"
            >
              required
            </span>
          )}

          {tooltip && <InfoHint text={tooltip} />}

          {/* Live success cue — appears the moment a required field becomes valid */}
          {valid && !error && (
            <CheckCircleIcon
              className="w-3.5 h-3.5 ml-auto"
              style={{ color: 'rgb(74, 222, 128)' }}
              aria-label="Looks good"
            />
          )}
        </label>
      )}

      <div
        className={
          error
            ? '[&>input]:ring-1 [&>input]:ring-amber-400/40 [&>select]:ring-1 [&>select]:ring-amber-400/40 [&>textarea]:ring-1 [&>textarea]:ring-amber-400/40'
            : ''
        }
      >
        {enhancedChildren}
      </div>

      {/* Helper text always visible when no error. Users learn WHY the
          field exists, not just that it's required. */}
      {helper && !error && (
        <p
          id={`${fieldId}-help`}
          className="mt-1 text-xs"
          style={{ color: 'rgb(148, 163, 184)' }}
        >
          {helper}
        </p>
      )}

      {/* Error message in warm amber (not alarm red). Phrased actionably. */}
      {error && (
        <p
          id={`${fieldId}-err`}
          className="mt-1 text-xs flex items-start gap-1.5"
          style={{ color: 'rgb(245, 158, 11)' }}
          role="alert"
        >
          <span
            aria-hidden="true"
            className="inline-flex items-center justify-center w-3 h-3 rounded-full mt-0.5 flex-shrink-0"
            style={{
              background: 'rgb(245, 158, 11)',
              color: '#1a1510',
              fontSize: '8px',
              fontWeight: 'bold',
            }}
          >
            !
          </span>
          <span>{error}</span>
        </p>
      )}

      {/* Sample-value chips — clickable pills below the field that prefill
          the input with a realistic example. Helps users who stare at an
          empty field wondering "what should I even put here?". Drawn from
          the VIP-forms principle: never leave a user guessing. */}
      {samples && samples.length > 0 && typeof onSampleClick === 'function' && (
        <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
          <span
            className="text-[10px] uppercase tracking-wide font-semibold"
            style={{ color: 'rgb(100, 116, 139)', letterSpacing: '0.05em' }}
          >
            try:
          </span>
          {samples.map((sample) => (
            <button
              key={sample}
              type="button"
              onClick={() => onSampleClick(sample)}
              className="text-[10px] font-mono px-1.5 py-0.5 rounded transition-all"
              style={{
                background: 'rgba(100, 140, 255, 0.08)',
                color: 'rgb(150, 180, 255)',
                border: '1px solid rgba(100, 140, 255, 0.2)',
                cursor: 'pointer',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.background = 'rgba(100, 140, 255, 0.16)'
                e.currentTarget.style.borderColor = 'rgba(100, 140, 255, 0.4)'
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = 'rgba(100, 140, 255, 0.08)'
                e.currentTarget.style.borderColor = 'rgba(100, 140, 255, 0.2)'
              }}
              title={`Click to fill in "${sample}"`}
            >
              {sample}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * InfoHint — tiny "?" icon that reveals contextual help on hover/click.
 *
 * Used next to form labels for fields that need more explanation than a
 * one-liner helper text. Click to pin open, click again to close, or hover
 * on desktop. Appears inline with the label, never grabs layout space.
 */
export function InfoHint({ text }) {
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return
    const onClick = (e) => {
      if (!ref.current?.contains(e.target)) setOpen(false)
    }
    window.addEventListener('mousedown', onClick)
    return () => window.removeEventListener('mousedown', onClick)
  }, [open])

  return (
    <span ref={ref} className="relative inline-flex items-center">
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={(e) => {
          // Don't close if moving toward the popover
          if (!ref.current?.contains(e.relatedTarget)) {
            setTimeout(() => setOpen(false), 100)
          }
        }}
        className="p-0.5 rounded transition-colors"
        style={{ color: 'rgb(148, 163, 184)' }}
        aria-label="More information"
        title={text}
      >
        <QuestionMarkCircleIcon className="w-3.5 h-3.5" />
      </button>

      {open && (
        <span
          className="absolute left-0 top-full mt-1 z-20 w-64 p-2 rounded-lg text-[11px] shadow-lg"
          style={{
            background: 'rgb(18, 18, 22)',
            border: '1px solid rgb(48, 48, 56)',
            color: 'rgb(var(--tx-primary))',
          }}
          role="tooltip"
        >
          {text}
        </span>
      )}
    </span>
  )
}

/**
 * Validates form fields and returns error messages.
 *
 * Rule shape: { field, label, required, validate?, message? }
 *
 * Messages prefer actionable phrasing ("Add a name to continue") over
 * rule statements ("Name is required"). Callers can override via `message`.
 */
export function validateForm(form, rules) {
  const errors = {}
  for (const rule of rules) {
    const value = form[rule.field]
    const isEmpty =
      value == null ||
      (typeof value === 'string' && !value.trim()) ||
      (Array.isArray(value) && value.length === 0)

    if (rule.required && isEmpty) {
      errors[rule.field] =
        rule.message || `Add a ${(rule.label || rule.field).toLowerCase()} to continue`
    } else if (rule.validate && !isEmpty) {
      const err = rule.validate(value, form)
      if (err) errors[rule.field] = err
    }
  }
  return errors
}

/**
 * Common field validators. Every message is gentle + actionable.
 */
export const validators = {
  email: (v) => {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v))
      return 'Double-check this — looks like the email is missing a piece'
    return null
  },
  url: (v) => {
    try {
      new URL(v)
      return null
    } catch {
      return 'Use a full URL starting with https:// so we can reach it'
    }
  },
  minLength: (min) => (v) =>
    v.length < min ? `A few more characters please — we need at least ${min}` : null,
  maxLength: (max) => (v) =>
    v.length > max ? `Trim this down to ${max} characters or fewer` : null,
  password: (v) =>
    v.length < 8 ? 'Make the password at least 8 characters so it holds up' : null,
  nonNegative: (v) =>
    Number(v) < 0 ? "Use a number zero or higher" : null,
  port: (v) => {
    const n = Number(v)
    if (!Number.isInteger(n) || n < 1 || n > 65535)
      return 'Use a port number between 1 and 65535'
    return null
  },
}

/**
 * Maps HTTP error responses to user-friendly messages.
 * Same calm tone as the field validators: tell the user what to do.
 */
export function friendlyError(err) {
  const status = err?.response?.status
  const serverMsg = err?.response?.data?.error || err?.response?.data?.message
  if (status === 409)
    return serverMsg || 'A record with this name already exists — try a different name'
  if (status === 400)
    return serverMsg || "Something in the form isn't quite right — check the highlighted fields"
  if (status === 403)
    return "You'll need admin permissions for this action — ask your admin or switch accounts"
  if (status === 404) return "We couldn't find that record — it may have been deleted"
  if (status >= 500) return 'Our server hit a snag — please try again in a moment'
  return serverMsg || err?.message || 'Something unexpected happened — please try again'
}
