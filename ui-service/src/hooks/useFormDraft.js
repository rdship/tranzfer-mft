import { useEffect, useState, useCallback, useRef } from 'react'
import toast from 'react-hot-toast'

/**
 * useFormDraft — keeps the user's form input safe across:
 *   1. Accidental page reload / navigate away (localStorage persistence)
 *   2. Save API failures (form state is never cleared on error)
 *   3. Backend rejections with field errors (caller re-maps to fields)
 *
 * The user's complaint that prompted R21:
 *   "If i try to save ANY object on the UI, UI should take it as its
 *    responsibility to guide user to achieve the task he wants. [...]
 *    the user puts effort to fill information and he will be annoyed
 *    if he has to refill in any scenario. [...] right now it feels
 *    loose the requests are getting lost"
 *
 * Usage (replacing `const [form, setForm] = useState(defaults)`):
 *
 *   const { form, setForm, updateField, clearDraft, hasDraft } =
 *     useFormDraft('partner-create', defaults)
 *
 *   const createMut = useMutation({
 *     mutationFn: createPartner,
 *     onSuccess: () => {
 *       clearDraft()                      // only clear AFTER success
 *       navigate('/partners')
 *     },
 *     onError: (err) => {
 *       // Form state stays intact — nothing to re-type
 *       toast.error(...)
 *     },
 *   })
 *
 * Features:
 *   - localStorage key = `tranzfer.draft.${formKey}`
 *   - Drafts expire after 24 hours so stale half-filled forms don't
 *     linger forever. Restored drafts announce themselves via a
 *     one-time toast ("Restored your unsaved changes") so the user
 *     knows why fields are pre-filled.
 *   - Debounced writes (300 ms) keep localStorage from thrashing on
 *     every keystroke.
 *   - clearDraft() is the ONLY thing that removes the persisted draft.
 *     Save errors never clear it — the whole point is to survive them.
 */

const DRAFT_PREFIX = 'tranzfer.draft.'
const DRAFT_TTL_MS = 24 * 60 * 60 * 1000 // 24 hours

function loadDraft(key) {
  try {
    const raw = localStorage.getItem(DRAFT_PREFIX + key)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    if (typeof parsed.savedAt !== 'number') return null
    if (Date.now() - parsed.savedAt > DRAFT_TTL_MS) {
      localStorage.removeItem(DRAFT_PREFIX + key)
      return null
    }
    return parsed.data
  } catch {
    return null
  }
}

function saveDraft(key, data) {
  try {
    localStorage.setItem(DRAFT_PREFIX + key, JSON.stringify({
      data,
      savedAt: Date.now(),
    }))
  } catch {
    // Quota errors etc — silent. Not a failure the user needs to see.
  }
}

export function useFormDraft(formKey, defaults, options = {}) {
  const { announceRestore = true, debounceMs = 300 } = options
  const initial = useRef(null)
  if (initial.current === null) {
    const restored = loadDraft(formKey)
    initial.current = restored ?? defaults
    if (restored && announceRestore && typeof window !== 'undefined') {
      // Fire-and-forget toast so the user knows fields are pre-filled.
      // Deferred to a microtask so the React warning about state updates
      // during render doesn't fire.
      Promise.resolve().then(() => {
        toast('Restored your unsaved changes', {
          icon: '♻️',
          duration: 4000,
          id: `draft-restored-${formKey}`,
        })
      })
    }
  }
  const [form, setFormState] = useState(initial.current)
  const [hasDraft, setHasDraft] = useState(!!loadDraft(formKey))
  const debounceRef = useRef(null)

  // Debounced persist to localStorage
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      // Only persist if the form differs from defaults in a meaningful
      // way — empty-string fields and null values are still "dirty" in
      // a field-filled sense, so rather than deep-comparing we persist
      // any form where at least one non-empty value exists.
      const hasAnyValue = Object.values(form || {}).some(
        v => v !== '' && v !== null && v !== undefined && !(Array.isArray(v) && v.length === 0)
      )
      if (hasAnyValue) {
        saveDraft(formKey, form)
        setHasDraft(true)
      }
    }, debounceMs)
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current) }
  }, [form, formKey, debounceMs])

  const setForm = useCallback((updater) => {
    if (typeof updater === 'function') {
      setFormState(prev => updater(prev))
    } else {
      setFormState(updater)
    }
  }, [])

  const updateField = useCallback((field, value) => {
    setFormState(prev => ({ ...prev, [field]: value }))
  }, [])

  const clearDraft = useCallback(() => {
    try { localStorage.removeItem(DRAFT_PREFIX + formKey) } catch {}
    setHasDraft(false)
    setFormState(defaults)
  }, [formKey, defaults])

  const discardDraft = useCallback(() => {
    try { localStorage.removeItem(DRAFT_PREFIX + formKey) } catch {}
    setHasDraft(false)
    setFormState(defaults)
    toast.success('Draft discarded', { id: `draft-discarded-${formKey}`, icon: '🗑️' })
  }, [formKey, defaults])

  return { form, setForm, updateField, clearDraft, discardDraft, hasDraft }
}

/**
 * extractFieldErrors — normalize backend error responses into a
 * `{ field: message }` map so the form can highlight specific inputs.
 * Handles three common shapes returned by the TranzFer backend:
 *   - `{ details: ["name is required", "email bad format"] }`  (from
 *     PlatformExceptionHandler handleValidation — field unknown, best
 *     effort: first word of each detail becomes the field)
 *   - `{ fieldErrors: { name: "required", email: "..." } }`
 *   - `{ message: "name: required" }` (single fallback)
 */
export function extractFieldErrors(err) {
  const data = err?.response?.data
  if (!data) return { _root: err?.message || 'Request failed' }

  // Already in the right shape
  if (data.fieldErrors && typeof data.fieldErrors === 'object') {
    return { ...data.fieldErrors, _root: data.message || null }
  }

  // Details array from MethodArgumentNotValidException
  if (Array.isArray(data.details)) {
    const out = { _root: data.message || 'Validation failed' }
    for (const d of data.details) {
      if (typeof d !== 'string') continue
      const m = d.match(/^([a-zA-Z0-9_.]+)[:\s]\s*(.+)/)
      if (m) out[m[1]] = m[2]
      else if (!out._details) out._details = [d]
      else out._details.push(d)
    }
    return out
  }

  // Message-only fallback
  return { _root: data.message || data.error || err.message || 'Save failed' }
}
