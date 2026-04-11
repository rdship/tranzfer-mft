import { useState, useRef, useEffect } from 'react'
import { Cog6ToothIcon, ArrowPathIcon } from '@heroicons/react/24/outline'

/**
 * Compact gear button + popover used across big table pages to control which
 * columns are visible. Pairs with the useColumnPrefs hook — the parent page
 * owns the preference state and passes it in as props so this component stays
 * stateless beyond its own open/close flag.
 *
 * Design principles:
 *   6. Flexibility    — user toggles any column on/off.
 *   8. Minimalism     — single gear icon, popover only on demand.
 *   9. Guidance       — "Showing N of M columns" footer + Reset link.
 *   - Dark theme      — reuses existing surface / border / hover utility classes.
 *
 * Props:
 *   tableKey         string  — for aria-label only; state lives in parent hook
 *   columns          Array<{ key: string, label: string }>  full column universe
 *   visibleKeys      Set<string>                           from useColumnPrefs
 *   toggle(key)      function                              from useColumnPrefs
 *   resetToDefaults  function                              from useColumnPrefs
 */
export default function ColumnSettingsButton({
  tableKey,
  columns,
  visibleKeys,
  toggle,
  resetToDefaults,
}) {
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef(null)
  const buttonRef = useRef(null)

  // Close on outside click.
  useEffect(() => {
    if (!open) return
    const handler = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  // Close on Escape; restore focus to the gear button for a11y.
  useEffect(() => {
    if (!open) return
    const handler = (e) => {
      if (e.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open])

  const visibleCount = columns.filter(c => visibleKeys.has(c.key)).length
  const totalCount = columns.length

  return (
    <div className="relative inline-block" ref={wrapperRef}>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen(o => !o)}
        title="Column settings"
        aria-label={`Column settings for ${tableKey}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        className="p-1.5 rounded-lg border border-border bg-surface text-secondary hover:bg-hover hover:text-primary transition-colors"
      >
        <Cog6ToothIcon className="w-4 h-4" />
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Column settings"
          className="absolute right-0 top-full mt-2 w-64 bg-surface border border-border rounded-lg shadow-xl z-50 flex flex-col"
        >
          <div className="px-4 py-2.5 border-b border-border">
            <h3 className="text-sm font-semibold text-primary">Column Settings</h3>
          </div>
          <div className="max-h-80 overflow-y-auto p-2">
            {columns.map(col => {
              const checked = visibleKeys.has(col.key)
              return (
                <label
                  key={col.key}
                  className="flex items-center gap-2.5 px-2 py-2 rounded-md hover:bg-hover cursor-pointer transition-colors"
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => toggle(col.key)}
                    className="w-4 h-4 rounded border-border text-accent focus:ring-accent/40 focus:ring-offset-0 cursor-pointer"
                  />
                  <span className="text-sm text-primary flex-1 min-w-0 truncate">{col.label}</span>
                </label>
              )
            })}
          </div>
          <div className="flex items-center justify-between px-4 py-2.5 border-t border-border">
            <span className="text-xs text-secondary">
              Showing {visibleCount} of {totalCount} columns
            </span>
            <button
              type="button"
              onClick={resetToDefaults}
              className="inline-flex items-center gap-1 text-xs font-medium text-accent hover:text-primary transition-colors"
              title="Reset to default columns"
            >
              <ArrowPathIcon className="w-3 h-3" />
              Reset
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
