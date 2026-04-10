/**
 * Reusable form field wrapper with required indicators, validation errors, and helper text.
 *
 * Usage:
 *   <FormField label="Email" required error={errors.email} helper="We'll send login credentials here">
 *     <input value={form.email} onChange={...} />
 *   </FormField>
 */
export default function FormField({ label, required, error, helper, children, className = '' }) {
  return (
    <div className={className}>
      {label && (
        <label className="block text-sm font-medium text-primary mb-1">
          {label}
          {required && <span className="text-red-500 ml-0.5">*</span>}
        </label>
      )}
      <div className={error ? '[&>input]:border-red-300 [&>input]:focus:ring-red-500 [&>select]:border-red-300 [&>select]:focus:ring-red-500 [&>textarea]:border-red-300 [&>textarea]:focus:ring-red-500' : ''}>
        {children}
      </div>
      {error && (
        <p className="mt-1 text-xs text-red-600">{error}</p>
      )}
      {!error && helper && (
        <p className="mt-1 text-xs text-muted">{helper}</p>
      )}
    </div>
  )
}

/**
 * Validates form fields and returns error messages.
 * @param {Object} form - Form data
 * @param {Array} rules - Array of { field, label, required, validate?, message? }
 * @returns {Object} errors map (empty = valid)
 */
export function validateForm(form, rules) {
  const errors = {}
  for (const rule of rules) {
    const value = form[rule.field]
    if (rule.required && (!value || (typeof value === 'string' && !value.trim()))) {
      errors[rule.field] = rule.message || `${rule.label || rule.field} is required`
    } else if (rule.validate && value) {
      const err = rule.validate(value, form)
      if (err) errors[rule.field] = err
    }
  }
  return errors
}

/**
 * Common field validators
 */
export const validators = {
  email: (v) => {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) return 'Please enter a valid email address'
    return null
  },
  url: (v) => {
    try { new URL(v); return null } catch { return 'Please enter a valid URL (e.g., https://example.com)' }
  },
  minLength: (min) => (v) => {
    if (v.length < min) return `Must be at least ${min} characters`
    return null
  },
  maxLength: (max) => (v) => {
    if (v.length > max) return `Must be ${max} characters or fewer`
    return null
  },
  password: (v) => {
    if (v.length < 8) return 'Password must be at least 8 characters'
    return null
  },
}

/**
 * Maps HTTP error responses to user-friendly messages.
 */
export function friendlyError(err) {
  const status = err?.response?.status
  const serverMsg = err?.response?.data?.error || err?.response?.data?.message
  if (status === 409) return serverMsg || 'A record with this name already exists. Try a different name.'
  if (status === 400) return serverMsg || 'Invalid data. Please check your inputs and try again.'
  if (status === 403) return 'You do not have permission to perform this action.'
  if (status === 404) return 'The requested resource was not found.'
  if (status >= 500) return 'Something went wrong on the server. Please try again or contact support.'
  return serverMsg || err?.message || 'An unexpected error occurred.'
}
