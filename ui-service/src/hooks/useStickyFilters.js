import { useEffect, useRef } from 'react'

/**
 * useStickyFilters — persist a page's filter state to localStorage so
 * users don't lose their filters when they refresh, close the tab, or
 * navigate away and come back.
 *
 * Works alongside URL-driven filters (useSearchParams):
 *   • URL params win on initial load (shareable links + deep-links take priority)
 *   • If the URL is empty, localStorage is restored
 *   • As filters change, the latest values are written to localStorage
 *
 * Design principles (locked):
 *   • Speed        — synchronous localStorage, no re-renders beyond what React already does
 *   • Stability    — resilient to JSON parse failures + missing/corrupt entries
 *   • Transparency — one storage key per table, human-readable namespace
 *   • Minimalism   — no ceremony, just two calls: restore + persist
 *   • Guidance     — the `reset` helper lets pages clear everything in one call
 *
 * Usage:
 *   const { restoreInitial, persist, reset } = useStickyFilters('activity-monitor', {
 *     statusFilter: 'ALL',
 *     sourceUserFilter: '',
 *     protocolFilter: 'ALL',
 *     stuckOnly: false,
 *   })
 *
 *   // On mount, restore from localStorage IF URL is empty
 *   useEffect(() => {
 *     if (!searchParams.toString()) {
 *       const saved = restoreInitial()
 *       if (saved) {
 *         setStatusFilter(saved.statusFilter)
 *         setSourceUser(saved.sourceUserFilter)
 *         ...
 *       }
 *     }
 *   }, [])
 *
 *   // On any filter change, persist
 *   useEffect(() => {
 *     persist({ statusFilter, sourceUserFilter, protocolFilter, stuckOnly })
 *   }, [statusFilter, sourceUserFilter, protocolFilter, stuckOnly])
 */

const STORAGE_PREFIX = 'tranzfer.filters.'

export default function useStickyFilters(tableKey, defaults = {}) {
  const storageKey = STORAGE_PREFIX + tableKey
  const defaultsRef = useRef(defaults)

  // Hydration guard — restoreInitial returns the persisted object the first
  // time it's called per mount, then returns null on subsequent calls. This
  // prevents surprising state overwrites if the caller forgets to only
  // restore on mount.
  const alreadyRestored = useRef(false)

  const restoreInitial = () => {
    if (alreadyRestored.current) return null
    alreadyRestored.current = true
    try {
      const raw = localStorage.getItem(storageKey)
      if (!raw) return null
      const parsed = JSON.parse(raw)
      // Validate shape — must be a plain object with at least one known default key
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
      // Merge with defaults so missing keys fall back cleanly
      return { ...defaultsRef.current, ...parsed }
    } catch {
      // Corrupt JSON — drop it silently
      try { localStorage.removeItem(storageKey) } catch { /* noop */ }
      return null
    }
  }

  const persist = (filters) => {
    if (!filters || typeof filters !== 'object') return
    try {
      // Only serialize keys that are in the defaults (prevents accidental
      // blob growth from transient state leaking in)
      const filtered = {}
      for (const k of Object.keys(defaultsRef.current)) {
        if (k in filters) filtered[k] = filters[k]
      }
      localStorage.setItem(storageKey, JSON.stringify(filtered))
    } catch {
      /* quota exceeded or storage unavailable — silently drop */
    }
  }

  const reset = () => {
    try { localStorage.removeItem(storageKey) } catch { /* noop */ }
    alreadyRestored.current = false
  }

  return { restoreInitial, persist, reset }
}
