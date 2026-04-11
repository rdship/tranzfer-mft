import { useCallback, useRef, useState } from 'react'
import toast from 'react-hot-toast'
import { validateForm } from '../components/FormField'

/**
 * useGentleValidation — the "VIP form" validation hook.
 *
 * Design principle: treat users as gods. When a user clicks Save and
 * misses a required field, the response must feel like a calm assistant
 * walking them back to the field, not like a form throwing a tantrum.
 *
 * Behaviors on validation failure:
 *   1. Compute all field errors from the rules array
 *   2. Smooth-scroll the page to the first missing field
 *   3. Focus that field's input (so the cursor is already blinking)
 *   4. Show ONE calm toast summarizing what's needed
 *      ("Just one more detail" / "We need 3 things to save this partner")
 *   5. Set the errors state so FormField components render amber hints
 *   6. As the user types in a field, clear ONLY that field's error
 *      (don't wait until blur; instant-clear feels responsive)
 *
 * Usage:
 *   const { errors, handleSubmit, clearFieldError, fieldProps } =
 *     useGentleValidation({
 *       rules: [
 *         { field: 'name',  label: 'Name',  required: true },
 *         { field: 'email', label: 'Email', required: true, validate: validators.email },
 *       ],
 *       onValid: (form) => mutation.mutate(form),
 *       // Optional: page-wide toast prefix
 *       recordKind: 'partner',
 *     })
 *
 *   <form onSubmit={handleSubmit(form)}>
 *     <FormField label="Name" required error={errors.name} name="name">
 *       <input
 *         value={form.name}
 *         onChange={e => { setForm({...form, name: e.target.value}); clearFieldError('name') }}
 *       />
 *     </FormField>
 *     ...
 *     <button type="submit">Save</button>
 *   </form>
 */
export default function useGentleValidation({ rules, onValid, recordKind = 'record' }) {
  const [errors, setErrors] = useState({})
  const formContainerRef = useRef(null)

  /**
   * Given an errors map, smooth-scroll to the first failing field and
   * focus its input. Uses the `data-field-wrapper` + `data-field-name`
   * attributes that FormField injects.
   */
  const focusFirstError = useCallback((errorMap) => {
    const firstField = rules.find(r => errorMap[r.field])?.field
    if (!firstField) return

    // Find the field wrapper (set by FormField via data-field-wrapper)
    const wrapper = document.querySelector(`[data-field-wrapper="${firstField}"]`)
    if (wrapper) {
      wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }

    // Focus the actual input (set by FormField via data-field-name)
    // Delay slightly so the scroll animation doesn't fight the focus ring
    setTimeout(() => {
      const input = document.querySelector(`[data-field-name="${firstField}"]`)
      if (input && typeof input.focus === 'function') {
        input.focus({ preventScroll: true })
      }
    }, 320)
  }, [rules])

  /**
   * Main submit handler. Pass your current form state in.
   * Returns an event handler suitable for `<form onSubmit={handleSubmit(form)}>`.
   */
  const handleSubmit = useCallback((form) => (e) => {
    if (e && typeof e.preventDefault === 'function') e.preventDefault()

    const validationErrors = validateForm(form, rules)
    const missingCount = Object.keys(validationErrors).length

    if (missingCount === 0) {
      setErrors({})
      onValid?.(form)
      return
    }

    setErrors(validationErrors)

    // Count missing requireds vs. format issues for a smarter toast
    const missingRequired = rules.filter(r =>
      r.required && validationErrors[r.field] && (
        form[r.field] == null ||
        (typeof form[r.field] === 'string' && !form[r.field].trim())
      )
    ).length

    let message
    if (missingCount === 1) {
      const only = rules.find(r => validationErrors[r.field])
      message = `Just one more thing — ${(only?.label || 'a field').toLowerCase()} is needed`
    } else if (missingRequired === missingCount) {
      message = `${missingCount} more details to save this ${recordKind}`
    } else {
      message = `${missingCount} fields need your attention`
    }

    toast(message, {
      icon: '👋',
      duration: 3500,
      style: {
        background: 'rgb(28, 22, 12)',
        color: 'rgb(245, 158, 11)',
        border: '1px solid rgba(245, 158, 11, 0.4)',
      },
    })

    focusFirstError(validationErrors)
  }, [rules, onValid, recordKind, focusFirstError])

  /**
   * Clear a single field's error. Call from onChange so errors vanish the
   * moment the user starts addressing them.
   */
  const clearFieldError = useCallback((fieldName) => {
    setErrors(prev => {
      if (!prev[fieldName]) return prev
      const next = { ...prev }
      delete next[fieldName]
      return next
    })
  }, [])

  /**
   * Clear all errors at once (e.g., when the form is reset).
   */
  const clearAllErrors = useCallback(() => setErrors({}), [])

  /**
   * Spread helper so pages don't have to wire name/error/required by hand.
   *
   *   const partnerRules = [{ field: 'name', label: 'Name', required: true }]
   *   const v = useGentleValidation({ rules: partnerRules, onValid })
   *
   *   <FormField {...v.fieldProps('name')} helper="…">
   *     <input {...v.inputProps('name', form, setForm)} />
   *   </FormField>
   */
  const fieldProps = useCallback((fieldName) => {
    const rule = rules.find(r => r.field === fieldName)
    return {
      name: fieldName,
      label: rule?.label,
      required: rule?.required,
      error: errors[fieldName],
    }
  }, [rules, errors])

  const inputProps = useCallback((fieldName, form, setForm) => ({
    value: form[fieldName] ?? '',
    onChange: (e) => {
      const val = e?.target?.value ?? e
      setForm(prev => ({ ...prev, [fieldName]: val }))
      clearFieldError(fieldName)
    },
  }), [clearFieldError])

  return {
    errors,
    setErrors,
    handleSubmit,
    clearFieldError,
    clearAllErrors,
    focusFirstError,
    fieldProps,
    inputProps,
    formContainerRef,
    isValid: Object.keys(errors).length === 0,
  }
}
