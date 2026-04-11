import { useEffect, useState } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'
import Header from './Header'
import Breadcrumbs from './Breadcrumbs'
import GlobalSearch from './GlobalSearch'
import KeyboardShortcuts from './KeyboardShortcuts'
import { useRecentlyViewed } from '../hooks/useRecentlyViewed'

/**
 * Layout — the authenticated-app shell.
 *
 * Wraps every protected route and provides:
 *   • Sidebar (with recently-viewed tracking via useRecentlyViewed)
 *   • Header (logo, user menu, search trigger)
 *   • Breadcrumbs (hidden on Operations shell which has its own tabs)
 *   • GlobalSearch (Cmd+K / Ctrl+K modal overlay, single instance)
 *
 * Design principles (locked):
 *   • Speed        — one render tree, no per-route layout switching
 *   • Attractiveness— consistent dark theme across every page
 *   • Minimalism   — Breadcrumbs + GlobalSearch are additive, not intrusive
 *   • Guidance     — breadcrumbs + search hints help users find their way
 */
export default function Layout() {
  const [searchOpen, setSearchOpen] = useState(false)
  const [shortcutsOpen, setShortcutsOpen] = useState(false)

  // Track recently-viewed pages (localStorage-backed). The hook is mounted
  // here so every navigation under the protected shell is recorded.
  useRecentlyViewed()

  // Global keyboard shortcuts:
  //   Cmd/Ctrl+K  → open GlobalSearch
  //   ?           → open KeyboardShortcuts cheat sheet (only when not typing in an input)
  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setSearchOpen(true)
        return
      }
      if (e.key === '?' && !isTypingInField(e.target)) {
        e.preventDefault()
        setShortcutsOpen(true)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar is always dark — uses hardcoded zinc palette, not theme vars */}
      <Sidebar />

      {/* Content area — driven by [data-theme] CSS variable tokens */}
      <div className="flex-1 flex flex-col overflow-hidden page-canvas">
        <Header />
        <main className="flex-1 overflow-y-auto p-6 animate-page">
          <Breadcrumbs />
          <Outlet />
        </main>
      </div>

      {/* Global search modal — triggered by Cmd+K / Ctrl+K anywhere */}
      <GlobalSearch open={searchOpen} onClose={() => setSearchOpen(false)} />

      {/* Keyboard shortcuts cheat sheet — triggered by "?" */}
      <KeyboardShortcuts open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />

      {/* Fixed-position discoverability pills — search + shortcuts hint */}
      <div className="fixed bottom-4 right-4 z-40 flex items-center gap-2">
        <button
          onClick={() => setShortcutsOpen(true)}
          className="flex items-center justify-center w-8 h-8 rounded-full text-xs font-semibold shadow-lg transition-all hover:scale-105"
          style={{
            background: 'rgb(18, 18, 22)',
            border: '1px solid rgb(48, 48, 56)',
            color: 'rgb(148, 163, 184)',
          }}
          title="Keyboard shortcuts (?)"
        >
          ?
        </button>
        <button
          onClick={() => setSearchOpen(true)}
          className="flex items-center gap-2 px-3 py-2 rounded-full text-xs font-semibold shadow-lg transition-all hover:scale-105"
          style={{
            background: 'rgb(18, 18, 22)',
            border: '1px solid rgb(48, 48, 56)',
            color: 'rgb(148, 163, 184)',
          }}
          title="Global search (Cmd+K / Ctrl+K)"
        >
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          Search
          <kbd className="font-mono px-1 rounded text-[9px]" style={{ background: 'rgb(30, 30, 36)' }}>⌘K</kbd>
        </button>
      </div>
    </div>
  )
}

/** Returns true if the event target is an editable input so we don't
 *  swallow single-character shortcuts while the user is typing. */
function isTypingInField(target) {
  if (!target) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable === true
}
