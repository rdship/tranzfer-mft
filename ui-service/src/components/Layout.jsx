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

  // Global keyboard shortcuts + custom DOM events:
  //   Cmd/Ctrl+K              → open GlobalSearch
  //   ?                       → open KeyboardShortcuts cheat sheet (only when not typing in an input)
  //   'global-search-open'    → programmatic GlobalSearch open (Header search button, etc.)
  //   'keyboard-shortcuts-open' → programmatic KeyboardShortcuts open (Header user menu, etc.)
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
    const openSearch    = () => setSearchOpen(true)
    const openShortcuts = () => setShortcutsOpen(true)
    window.addEventListener('keydown', handler)
    window.addEventListener('global-search-open', openSearch)
    window.addEventListener('keyboard-shortcuts-open', openShortcuts)
    return () => {
      window.removeEventListener('keydown', handler)
      window.removeEventListener('global-search-open', openSearch)
      window.removeEventListener('keyboard-shortcuts-open', openShortcuts)
    }
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

      {/*
        R127: bottom-right "?" + "Search ⌘K" floating pills removed per
        UX review. Both are duplicates of discoverability already in the
        top bar ("Search everywhere" trigger) and the keyboard shortcuts
        modal still opens on "?" anywhere. The pills read as chrome, not
        utility.
      */}
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
