import { useEffect } from 'react'
import { XMarkIcon } from '@heroicons/react/24/outline'
import useTimeOfDayBackdrop from '../hooks/useTimeOfDayBackdrop'

/**
 * KeyboardShortcuts — modal cheat sheet triggered by "?" (no modifier),
 * Cmd+? or Ctrl+?. Shows every keyboard affordance the admin UI supports.
 *
 * Mounted once in Layout.jsx alongside GlobalSearch. Single instance, global
 * listener. Closes on Escape / click-outside / X button.
 *
 * Design principles (locked):
 *   • Speed         — pure client, no fetches, trivial render
 *   • Transparency  — every shortcut documented with a plain-English action
 *   • Minimalism    — single modal, two columns, no decoration
 *   • Guidance      — categorized (Navigation / Search / Operations / Editing)
 *   • Attractiveness— kbd-styled keys with the dark-theme palette
 */
const SHORTCUTS = [
  {
    category: 'Search & Navigation',
    items: [
      { keys: ['⌘', 'K'],                action: 'Open global search (partners / accounts / flows / trackIds)' },
      { keys: ['Ctrl', 'K'],             action: 'Same as above, on non-Mac' },
      { keys: ['?'],                      action: 'Open this cheat sheet' },
      { keys: ['Esc'],                    action: 'Close any modal / dismiss search' },
    ],
  },
  {
    category: 'Global Search',
    items: [
      { keys: ['↑'],                      action: 'Previous result' },
      { keys: ['↓'],                      action: 'Next result' },
      { keys: ['Enter'],                  action: 'Open selected result' },
    ],
  },
  {
    category: 'Operations',
    items: [
      { keys: ['G', 'D'],                 action: 'Go to Dashboard (planned)' },
      { keys: ['G', 'A'],                 action: 'Go to Activity Monitor (planned)' },
      { keys: ['G', 'F'],                 action: 'Go to Flow Fabric (planned)' },
      { keys: ['G', 'J'],                 action: 'Go to Transfer Journey (planned)' },
    ],
  },
  {
    category: 'Tables & Lists',
    items: [
      { keys: ['R'],                      action: 'Refresh current view (planned)' },
      { keys: ['Shift', 'click'],         action: 'Select a range of rows' },
      { keys: ['/'],                      action: 'Focus the page search/filter box (planned)' },
    ],
  },
]

export default function KeyboardShortcuts({ open, onClose }) {
  const backdrop = useTimeOfDayBackdrop()
  // Close on Escape
  useEffect(() => {
    if (!open) return
    const handler = (e) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center px-4"
      style={backdrop.style}
      onClick={onClose}
      data-tod={backdrop.label}
    >
      <div
        className="w-full max-w-2xl rounded-xl overflow-hidden shadow-2xl max-h-[80vh] flex flex-col"
        style={{
          background: 'rgb(var(--canvas))',
          border: '1px solid rgb(var(--border))',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-5 py-4 flex-shrink-0"
          style={{ borderBottom: '1px solid rgb(var(--border))' }}
        >
          <h2 className="text-base font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
            Keyboard Shortcuts
          </h2>
          <button
            onClick={onClose}
            className="p-1 rounded transition-colors hover:bg-[rgb(var(--border))]"
            style={{ color: 'rgb(148, 163, 184)' }}
            title="Close (Esc)"
          >
            <XMarkIcon className="w-4 h-4" />
          </button>
        </div>

        {/* Body — two-column grid of categories */}
        <div className="flex-1 overflow-y-auto px-5 py-4 grid grid-cols-1 md:grid-cols-2 gap-6">
          {SHORTCUTS.map(cat => (
            <div key={cat.category}>
              <h3
                className="text-[10px] font-bold uppercase tracking-wider mb-2"
                style={{ color: 'rgb(100, 116, 139)' }}
              >
                {cat.category}
              </h3>
              <ul className="space-y-1.5">
                {cat.items.map((s, i) => {
                  const planned = s.action.includes('(planned)')
                  return (
                    <li
                      key={i}
                      className="flex items-start justify-between gap-3 text-xs"
                      style={{ opacity: planned ? 0.45 : 1 }}
                    >
                      <span style={{ color: 'rgb(var(--tx-primary))' }}>
                        {s.action.replace(' (planned)', '')}
                      </span>
                      <span className="flex items-center gap-1 flex-shrink-0">
                        {s.keys.map((k, j) => (
                          <kbd
                            key={j}
                            className="font-mono text-[10px] px-1.5 py-0.5 rounded border"
                            style={{
                              background: 'rgb(var(--border))',
                              borderColor: 'rgb(48, 48, 56)',
                              color: 'rgb(var(--tx-primary))',
                            }}
                          >
                            {k}
                          </kbd>
                        ))}
                      </span>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        </div>

        {/* Footer */}
        <div
          className="px-5 py-3 text-[11px] flex-shrink-0"
          style={{
            borderTop: '1px solid rgb(var(--border))',
            color: 'rgb(107, 114, 128)',
          }}
        >
          Press <kbd className="font-mono px-1 rounded" style={{ background: 'rgb(var(--border))' }}>?</kbd> anytime to reopen this sheet.
          Items marked <em>planned</em> are coming soon.
        </div>
      </div>
    </div>
  )
}
