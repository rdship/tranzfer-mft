import { useEffect, useState, useCallback } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * useRecentlyViewed — tracks the last N pages the user visited and
 * exposes them for rendering in the sidebar / topbar.
 *
 * Stored in localStorage so the list survives reloads. Capped at 10 entries.
 *
 * Design principles (locked):
 *   • Speed        — pure client state, no network
 *   • Transparency — the list shows pathname + human label
 *   • Minimalism   — one hook, no ceremony
 *   • Guidance     — the sidebar will show this so users can jump back
 *
 * Pathnames that should never be tracked (noise):
 *   /              — redirect
 *   /login         — auth
 *   /portal*       — separate app
 *   /operations*   — has its own tab bar, tracking adds noise
 */
const STORAGE_KEY = 'tranzfer.recentlyViewed'
const MAX_ENTRIES = 10

const EXCLUDE_PATTERNS = [
  /^\/$/,
  /^\/login/,
  /^\/portal/,
  /^\/operations$/,
  /^\/operations\//,
]

function isTrackable(pathname) {
  return !EXCLUDE_PATTERNS.some(p => p.test(pathname))
}

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function saveToStorage(list) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
  } catch {
    /* quota exceeded — silently drop */
  }
}

export function useRecentlyViewed() {
  const { pathname } = useLocation()
  const [entries, setEntries] = useState(loadFromStorage)

  // Record visits
  useEffect(() => {
    if (!isTrackable(pathname)) return
    setEntries(prev => {
      const next = [
        { path: pathname, visitedAt: Date.now() },
        ...prev.filter(e => e.path !== pathname),
      ].slice(0, MAX_ENTRIES)
      saveToStorage(next)
      return next
    })
  }, [pathname])

  const clear = useCallback(() => {
    setEntries([])
    saveToStorage([])
  }, [])

  return { entries, clear }
}
