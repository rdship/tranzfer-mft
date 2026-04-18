import { useState, useEffect, useRef } from 'react'
import { EllipsisHorizontalIcon } from '@heroicons/react/24/outline'

/**
 * R127 — lightweight overflow-menu for row actions. Drop-in replacement for
 * the "3 tiny icons in the Actions column" pattern the UX review called
 * dangerous (delete next to view, no tooltips, delete-by-accident waiting
 * to happen). Destructive items are segregated with a divider and styled
 * in --danger.
 *
 * Usage:
 *   <RowActionMenu items={[
 *     { label: 'View details', icon: EyeIcon, onClick: () => setDetail(u) },
 *     { label: 'Reset password', icon: KeyIcon, onClick: () => setResetPw(u) },
 *     { divider: true },
 *     { label: 'Delete', icon: TrashIcon, onClick: () => setDelete(u), destructive: true },
 *   ]} />
 */
export default function RowActionMenu({ items, align = 'right' }) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef(null)

  useEffect(() => {
    if (!open) return
    const onDown = e => { if (!wrapRef.current?.contains(e.target)) setOpen(false) }
    const onEsc  = e => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onEsc)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onEsc)
    }
  }, [open])

  return (
    <div className="relative inline-block" ref={wrapRef}>
      <button
        type="button"
        onClick={e => { e.stopPropagation(); setOpen(o => !o) }}
        className="p-1.5 rounded transition-colors"
        style={{ color: 'rgb(var(--tx-secondary))' }}
        onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--bg-elevated))' }}
        onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
        title="More actions"
        aria-label="More actions"
        aria-expanded={open}
      >
        <EllipsisHorizontalIcon className="w-4 h-4" />
      </button>
      {open && (
        <div
          className={`absolute z-30 mt-1 min-w-[170px] ${align === 'right' ? 'right-0' : 'left-0'} animate-scale-in`}
          style={{
            background: 'rgb(var(--bg-overlay))',
            border: '1px solid rgb(var(--border-strong) / 0.14)',
            borderRadius: '0.625rem',
            boxShadow: '0 8px 24px rgb(0 0 0 / 0.45)',
            padding: '0.25rem',
          }}
          onClick={e => e.stopPropagation()}
        >
          {items.map((item, idx) => {
            if (item.divider) {
              return <div key={`d${idx}`} style={{ height: 1, background: 'rgb(var(--border-subtle) / 0.12)', margin: '0.25rem 0' }} />
            }
            const Icon = item.icon
            return (
              <button
                key={idx}
                type="button"
                onClick={e => { e.stopPropagation(); setOpen(false); item.onClick?.(e) }}
                disabled={item.disabled}
                className="w-full flex items-center gap-2 px-3 py-1.5 rounded text-xs font-medium transition-colors"
                style={{
                  color: item.destructive ? 'rgb(var(--danger))' : 'rgb(var(--tx-primary))',
                  background: 'transparent',
                  opacity: item.disabled ? 0.4 : 1,
                  cursor: item.disabled ? 'not-allowed' : 'pointer',
                  textAlign: 'left',
                }}
                onMouseEnter={e => {
                  if (!item.disabled) e.currentTarget.style.background = 'rgb(var(--bg-elevated))'
                }}
                onMouseLeave={e => { e.currentTarget.style.background = 'transparent' }}
              >
                {Icon && <Icon className="w-3.5 h-3.5 flex-shrink-0" />}
                <span>{item.label}</span>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
