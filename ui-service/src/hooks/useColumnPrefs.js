import { useState, useCallback, useMemo } from 'react'

/**
 * Generic column-preferences hook.
 *
 * Reads and writes a column-visibility map from localStorage under a per-table key,
 * so each big table remembers which columns the user chose to show. The hook is
 * resilient to missing or corrupted storage entries — in either case it falls back
 * to the supplied `defaultVisibleKeys`.
 *
 * Design principles honored:
 *   1. Speed — pure state + memoized Set lookups, no refetches.
 *   4. Resilience — try/catch around JSON.parse, graceful fallback to defaults.
 *   6. Flexibility — the user decides which columns matter for their workflow.
 *   9. Guidance — returns visibleCount / columnCount so the popover can show "N of M".
 *
 * Usage:
 *   const { isVisible, toggle, resetToDefaults, columnCount, visibleCount, visibleKeys }
 *     = useColumnPrefs('partners-table', DEFAULT_VISIBLE_KEYS, ALL_KEYS)
 *
 * @param {string}   tableKey           unique per-table id, e.g. 'partners-table'
 * @param {string[]} defaultVisibleKeys keys visible when no user preference exists
 * @param {string[]} allKeys            the full universe of possible column keys
 */
export default function useColumnPrefs(tableKey, defaultVisibleKeys, allKeys) {
  const STORAGE_KEY = `tranzfer.columns.${tableKey}`

  const [visibleList, setVisibleList] = useState(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        const parsed = JSON.parse(stored)
        if (Array.isArray(parsed)) {
          // Filter to keys that still exist in the current allKeys universe —
          // protects against stale entries after a column rename/removal.
          const filtered = parsed.filter(k => allKeys.includes(k))
          if (filtered.length > 0) return filtered
        }
      }
    } catch {
      /* corrupted storage → fall through to defaults */
    }
    return defaultVisibleKeys
  })

  // Set for O(1) isVisible() lookups inside tight table render loops.
  const visibleKeys = useMemo(() => new Set(visibleList), [visibleList])

  const persist = useCallback((keys) => {
    setVisibleList(keys)
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(keys))
    } catch {
      /* quota/privacy mode — keep in-memory state, ignore persistence error */
    }
  }, [STORAGE_KEY])

  const isVisible = useCallback((key) => visibleKeys.has(key), [visibleKeys])

  const toggle = useCallback((key) => {
    const next = visibleKeys.has(key)
      ? visibleList.filter(k => k !== key)
      : [...visibleList, key]
    persist(next)
  }, [visibleKeys, visibleList, persist])

  const resetToDefaults = useCallback(() => {
    persist(defaultVisibleKeys)
  }, [persist, defaultVisibleKeys])

  return {
    visibleKeys,
    isVisible,
    toggle,
    resetToDefaults,
    columnCount: allKeys.length,
    visibleCount: visibleList.length,
  }
}
