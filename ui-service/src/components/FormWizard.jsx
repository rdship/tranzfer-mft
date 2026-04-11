import { useState, useCallback } from 'react'
import {
  CheckCircleIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline'
import { validateForm } from './FormField'
import toast from 'react-hot-toast'

/**
 * FormWizard — a multi-step form primitive in the style of Netflix onboarding,
 * Stripe checkout, Airbnb booking, Linear's new-project wizard.
 *
 * Design principles:
 *
 *   • **Progressive disclosure** — show only the fields for the current
 *     step, never a 40-field wall of labels.
 *
 *   • **Reassurance at every step** — a stepper at the top shows where the
 *     user is, what's done, and what's still ahead. Completed steps show
 *     a green check. Current step is highlighted. Future steps are muted
 *     but visible (never hidden).
 *
 *   • **One-click back, one-click forward** — users can revisit any
 *     completed step to edit. No "are you sure" popups between steps.
 *
 *   • **Per-step validation** — clicking Next runs validation for ONLY
 *     the fields in the current step. The user can't miss a required
 *     field and discover it at the end.
 *
 *   • **Encouraging copy** — each step has a short headline + hint that
 *     orients the user ("Who are you onboarding?" not "Step 2 of 5").
 *
 *   • **Final-step Save button is distinct** — the last step's primary
 *     button says "Create partner" (or whatever the noun is), not "Next".
 *
 * Usage:
 *   const steps = [
 *     {
 *       id: 'identity',
 *       title: "Who are you onboarding?",
 *       hint: "Start with the basics — you can refine these later.",
 *       fields: ['companyName', 'displayName', 'partnerType'],
 *       render: ({ form, setForm, errors }) => (
 *         <div className="space-y-4">
 *           <FormField label="Company name" required name="companyName" error={errors.companyName}>
 *             <input value={form.companyName} onChange={e => setForm({...form, companyName: e.target.value})} />
 *           </FormField>
 *           ...
 *         </div>
 *       ),
 *       rules: [
 *         { field: 'companyName', label: 'Company name', required: true },
 *         { field: 'partnerType', label: 'Partner type', required: true },
 *       ],
 *     },
 *     ...
 *   ]
 *
 *   <FormWizard
 *     steps={steps}
 *     form={form}
 *     setForm={setForm}
 *     onComplete={(finalForm) => mutation.mutate(finalForm)}
 *     onCancel={() => navigate(-1)}
 *     submitLabel="Create partner"
 *     loading={mutation.isPending}
 *     recordKind="partner"
 *   />
 */
export default function FormWizard({
  steps,
  form,
  setForm,
  onComplete,
  onCancel,
  submitLabel = 'Save',
  loading = false,
  recordKind = 'record',
}) {
  const [currentIndex, setCurrentIndex] = useState(0)
  const [errors, setErrors] = useState({})
  const [completedSet, setCompletedSet] = useState(new Set())

  const currentStep = steps[currentIndex]
  const isFirst = currentIndex === 0
  const isLast = currentIndex === steps.length - 1

  const validateCurrentStep = useCallback(() => {
    if (!currentStep?.rules) return {}
    return validateForm(form, currentStep.rules)
  }, [currentStep, form])

  const handleNext = useCallback(() => {
    const stepErrors = validateCurrentStep()
    if (Object.keys(stepErrors).length > 0) {
      setErrors(stepErrors)
      // Friendly toast in amber
      const count = Object.keys(stepErrors).length
      toast(count === 1 ? 'Just one more detail on this step' : `${count} fields need your attention on this step`, {
        icon: '👋',
        duration: 3000,
        style: {
          background: 'rgb(28, 22, 12)',
          color: 'rgb(245, 158, 11)',
          border: '1px solid rgba(245, 158, 11, 0.4)',
        },
      })
      // Scroll to first error
      setTimeout(() => {
        const firstField = currentStep.rules.find(r => stepErrors[r.field])?.field
        if (!firstField) return
        const wrapper = document.querySelector(`[data-field-wrapper="${firstField}"]`)
        if (wrapper) wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' })
        const input = document.querySelector(`[data-field-name="${firstField}"]`)
        if (input && typeof input.focus === 'function') {
          setTimeout(() => input.focus({ preventScroll: true }), 320)
        }
      }, 50)
      return
    }

    setErrors({})
    setCompletedSet(prev => new Set([...prev, currentIndex]))

    if (isLast) {
      onComplete?.(form)
    } else {
      setCurrentIndex(i => Math.min(i + 1, steps.length - 1))
    }
  }, [validateCurrentStep, currentStep, currentIndex, isLast, form, onComplete, steps.length])

  const handleBack = useCallback(() => {
    setErrors({})
    setCurrentIndex(i => Math.max(0, i - 1))
  }, [])

  const jumpTo = useCallback((idx) => {
    if (idx > currentIndex) return // only allow backward jumps to completed steps
    if (!completedSet.has(idx) && idx !== 0) return
    setErrors({})
    setCurrentIndex(idx)
  }, [currentIndex, completedSet])

  return (
    <div className="flex flex-col h-full">
      {/* ── Stepper header ─────────────────────────────────────────── */}
      <div
        className="px-6 py-4 flex-shrink-0"
        style={{ borderBottom: '1px solid rgb(30, 30, 36)' }}
      >
        <div className="flex items-center justify-between mb-3">
          <div>
            <h2 className="text-base font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {currentStep.title}
            </h2>
            {currentStep.hint && (
              <p className="text-xs mt-0.5" style={{ color: 'rgb(148, 163, 184)' }}>
                {currentStep.hint}
              </p>
            )}
          </div>
          {onCancel && (
            <button
              type="button"
              onClick={onCancel}
              className="p-1 rounded hover:bg-[rgb(30,30,36)] transition-colors"
              style={{ color: 'rgb(148, 163, 184)' }}
              aria-label="Cancel"
              title="Cancel and close"
            >
              <XMarkIcon className="w-5 h-5" />
            </button>
          )}
        </div>

        {/* Stepper dots */}
        <div className="flex items-center gap-2">
          {steps.map((s, i) => {
            const isCurrent = i === currentIndex
            const isDone = completedSet.has(i) && !isCurrent
            const isClickable = isDone || i < currentIndex

            return (
              <div key={s.id} className="flex items-center flex-1 min-w-0">
                <button
                  type="button"
                  onClick={() => jumpTo(i)}
                  disabled={!isClickable}
                  className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide transition-colors flex-shrink-0"
                  style={{
                    color: isCurrent
                      ? 'rgb(var(--tx-primary))'
                      : isDone
                        ? 'rgb(74, 222, 128)'
                        : 'rgb(100, 116, 139)',
                    cursor: isClickable ? 'pointer' : 'default',
                  }}
                >
                  <span
                    className="inline-flex items-center justify-center rounded-full font-bold"
                    style={{
                      width: '20px',
                      height: '20px',
                      background: isCurrent
                        ? 'rgb(var(--accent, 79 70 229))'
                        : isDone
                          ? 'rgba(74, 222, 128, 0.15)'
                          : 'rgb(30, 30, 36)',
                      color: isCurrent
                        ? '#fff'
                        : isDone
                          ? 'rgb(74, 222, 128)'
                          : 'rgb(100, 116, 139)',
                      fontSize: '10px',
                      border: isCurrent ? '1px solid rgb(var(--accent, 79 70 229))' : '1px solid transparent',
                    }}
                  >
                    {isDone ? <CheckCircleIcon className="w-3 h-3" /> : i + 1}
                  </span>
                  <span className="hidden sm:inline truncate">{s.id.replace(/-/g, ' ')}</span>
                </button>
                {i < steps.length - 1 && (
                  <div
                    className="flex-1 h-px mx-2 min-w-[12px]"
                    style={{
                      background: isDone ? 'rgb(74, 222, 128)' : 'rgb(30, 30, 36)',
                      transition: 'background 200ms ease',
                    }}
                  />
                )}
              </div>
            )
          })}
        </div>
      </div>

      {/* ── Body — renders the current step only ──────────────────── */}
      <div className="flex-1 overflow-y-auto px-6 py-5">
        {currentStep.render?.({ form, setForm, errors, setErrors })}
      </div>

      {/* ── Footer ─────────────────────────────────────────────────── */}
      <div
        className="px-6 py-4 flex items-center justify-between flex-shrink-0"
        style={{
          borderTop: '1px solid rgb(30, 30, 36)',
          background: 'rgb(14, 14, 18)',
        }}
      >
        <button
          type="button"
          onClick={handleBack}
          disabled={isFirst || loading}
          className="flex items-center gap-1 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
          style={{
            background: 'transparent',
            border: '1px solid rgb(48, 48, 56)',
            color: isFirst ? 'rgb(60, 60, 68)' : 'rgb(var(--tx-primary))',
            opacity: isFirst ? 0.4 : 1,
            cursor: isFirst || loading ? 'default' : 'pointer',
          }}
        >
          <ChevronLeftIcon className="w-3.5 h-3.5" />
          Back
        </button>

        <div className="text-xs" style={{ color: 'rgb(100, 116, 139)' }}>
          Step {currentIndex + 1} of {steps.length}
        </div>

        <button
          type="button"
          onClick={handleNext}
          disabled={loading}
          className="flex items-center gap-1 px-4 py-1.5 text-xs font-semibold rounded-lg transition-colors"
          style={{
            background: loading ? 'rgb(30, 30, 36)' : 'rgb(var(--accent, 79 70 229))',
            color: '#fff',
            cursor: loading ? 'wait' : 'pointer',
          }}
        >
          {loading && isLast ? 'Working…' : isLast ? submitLabel : 'Next'}
          {!isLast && <ChevronRightIcon className="w-3.5 h-3.5" />}
        </button>
      </div>
    </div>
  )
}
