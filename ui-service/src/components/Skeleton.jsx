/**
 * Skeleton primitives for loading states.
 *
 * Exports:
 *   <SkeletonBlock width="100%" height="20px" />
 *   <SkeletonRow cols={[80, 120, 60, 40]} />            // pixel widths or "flex-1"
 *   <SkeletonTable rows={10} cols={[...]} />            // N rows x M cols w/ borders
 *   <SkeletonCard lines={3} />                          // card shell with title + lines
 *
 * Default export: Skeleton = { Block, Row, Table, Card }.
 *
 * Shimmer: linear-gradient + animated background-position-x.
 * Dark palette: base rgb(30,30,36) -> highlight rgb(60,60,68).
 * Keyframes are injected once into <head> on first render (module-level flag),
 * so we don't pollute index.css.
 *
 * Worker C — Round 4 (loading skeletons for the 3 biggest tables).
 */
import { useEffect, useState } from 'react'

/**
 * useDelayedFlag — returns `true` only after `flag` has been true for `delayMs`.
 * Prevents skeleton flash on sub-100ms fetches (Stability / Attractiveness).
 * Resets immediately when flag flips false.
 */
export function useDelayedFlag(flag, delayMs = 100) {
  const [shown, setShown] = useState(false)
  useEffect(() => {
    if (!flag) {
      setShown(false)
      return undefined
    }
    const id = setTimeout(() => setShown(true), delayMs)
    return () => clearTimeout(id)
  }, [flag, delayMs])
  return shown
}

// ── Global keyframes injection (runs once) ──────────────────────────────────
let __skeletonStylesInjected = false

function ensureSkeletonStyles() {
  if (__skeletonStylesInjected) return
  if (typeof document === 'undefined') return
  const style = document.createElement('style')
  style.setAttribute('data-skeleton-styles', 'true')
  style.textContent = `
    @keyframes skeleton-shimmer {
      from { background-position: -200% 0; }
      to   { background-position: 200% 0; }
    }
    .skeleton-base {
      background-color: rgb(30, 30, 36);
      background-image: linear-gradient(
        90deg,
        rgb(30, 30, 36) 0%,
        rgb(48, 48, 56) 40%,
        rgb(60, 60, 68) 50%,
        rgb(48, 48, 56) 60%,
        rgb(30, 30, 36) 100%
      );
      background-size: 200% 100%;
      background-repeat: no-repeat;
      animation: skeleton-shimmer 1.6s ease-in-out infinite;
      border-radius: 4px;
    }
    @media (prefers-reduced-motion: reduce) {
      .skeleton-base { animation: none; }
    }
  `
  document.head.appendChild(style)
  __skeletonStylesInjected = true
}

// ── Primitives ──────────────────────────────────────────────────────────────

/**
 * SkeletonBlock — single rectangular placeholder.
 * width/height accept any CSS length; width also accepts "flex-1".
 */
export function SkeletonBlock({ width = '100%', height = '14px', className = '', style = {} }) {
  useEffect(ensureSkeletonStyles, [])
  const isFlex = width === 'flex-1'
  const mergedStyle = {
    height,
    ...(isFlex ? { flex: 1 } : { width }),
    ...style,
  }
  return <div className={`skeleton-base ${className}`} style={mergedStyle} aria-hidden="true" />
}

/**
 * SkeletonRow — horizontal strip of SkeletonBlocks, sized to match a real row.
 * `cols` is an array of numbers (pixel widths) or the literal string "flex-1".
 */
export function SkeletonRow({ cols = [120, 120, 120, 120], height = '14px', gap = '16px' }) {
  useEffect(ensureSkeletonStyles, [])
  return (
    <div className="flex items-center" style={{ gap }}>
      {cols.map((c, i) => (
        <SkeletonBlock
          key={i}
          width={c === 'flex-1' ? 'flex-1' : `${c}px`}
          height={height}
        />
      ))}
    </div>
  )
}

/**
 * SkeletonTable — N shimmer rows x M cols, with thin row borders to match the
 * real table structure. Drops cleanly into the existing .card !p-0 table shell.
 */
export function SkeletonTable({ rows = 10, cols = [80, 160, 120, 80, 120], rowHeight = 48 }) {
  useEffect(ensureSkeletonStyles, [])
  return (
    <div className="w-full" aria-busy="true" aria-live="polite" aria-label="Loading data">
      {Array.from({ length: rows }).map((_, r) => (
        <div
          key={r}
          className="flex items-center px-6 border-b"
          style={{
            height: `${rowHeight}px`,
            gap: '16px',
            borderColor: 'rgb(var(--border))',
          }}
        >
          {cols.map((c, i) => (
            <SkeletonBlock
              key={i}
              width={c === 'flex-1' ? 'flex-1' : `${c}px`}
              height="12px"
            />
          ))}
        </div>
      ))}
    </div>
  )
}

/**
 * SkeletonCard — card shell with a title bar + N faint text lines.
 * Matches the `.card` utility visually (border, padding, background).
 */
export function SkeletonCard({ lines = 3, className = '' }) {
  useEffect(ensureSkeletonStyles, [])
  return (
    <div
      className={`card ${className}`}
      style={{ backgroundColor: 'rgb(var(--surface))' }}
      aria-busy="true"
      aria-hidden="true"
    >
      {/* Title row: icon + title + status pill */}
      <div className="flex items-start justify-between gap-4 mb-3">
        <div className="flex items-center gap-3 flex-1 min-w-0">
          <SkeletonBlock width="20px" height="20px" />
          <SkeletonBlock width="180px" height="16px" />
          <SkeletonBlock width="64px" height="18px" />
        </div>
        <SkeletonBlock width="80px" height="14px" />
      </div>
      {/* Body lines */}
      <div className="space-y-2">
        {Array.from({ length: lines }).map((_, i) => (
          <SkeletonBlock
            key={i}
            width={i === lines - 1 ? '60%' : '100%'}
            height="10px"
          />
        ))}
      </div>
      {/* Footer chips */}
      <div className="flex items-center gap-2 mt-4">
        <SkeletonBlock width="56px" height="18px" />
        <SkeletonBlock width="72px" height="18px" />
        <SkeletonBlock width="48px" height="18px" />
      </div>
    </div>
  )
}

// ── Default aggregate export ────────────────────────────────────────────────
const Skeleton = {
  Block: SkeletonBlock,
  Row: SkeletonRow,
  Table: SkeletonTable,
  Card: SkeletonCard,
}

export default Skeleton
