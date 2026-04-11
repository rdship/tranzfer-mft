import { useEffect, useState } from 'react'

/**
 * useTimeOfDayBackdrop — return a dynamic backdrop style for modal overlays
 * that gently shifts based on the user's local time of day.
 *
 * Why: a modal's purpose is to pull focus. A one-size-fits-all `rgba(0,0,0,0.6)`
 * overlay works, but it feels lifeless — and at night, 60% black behind a bright
 * card is harsh on the eyes. At dawn it feels too cold. We want the backdrop to
 * match the ambient environment of the user the way a good desk lamp does —
 * brighter and cooler during work hours, deeper and warmer in the evening,
 * darker and more enveloping after midnight.
 *
 * Design principles (locked):
 *   • Attractiveness — subtle temperature shift, never jarring
 *   • Minimalism    — single hook, pure math, no ceremony
 *   • Guidance      — a small label ('morning' / 'afternoon' / ...) the UI
 *                     can display as a tooltip for the curious user
 *   • Accessibility — the underlying OPACITY stays >= 0.55 at all times so
 *                     modals never have an invisible backdrop
 *
 * Time windows (user's local clock):
 *
 *   05:00 – 08:00   dawn       — soft warm peach tint, light opacity
 *   08:00 – 12:00   morning    — cool clean blue tint, medium-light opacity
 *   12:00 – 17:00   afternoon  — neutral warm grey, medium opacity
 *   17:00 – 20:00   evening    — amber sunset tint, medium-dark opacity
 *   20:00 – 00:00   night      — deep indigo, darker
 *   00:00 – 05:00   late night — near-black with blue undertone, darkest
 *
 * Refreshes every 5 minutes so the backdrop drifts smoothly across the day
 * for users who leave the tab open all day.
 *
 * Usage:
 *   const backdrop = useTimeOfDayBackdrop()
 *
 *   <div style={{ background: backdrop.color }} onClick={onClose}>
 *     <div className="modal-card">...</div>
 *   </div>
 *
 *   // Or spread the full style object for convenience:
 *   <div style={backdrop.style} onClick={onClose}>
 */

// ── Backdrop presets — tuned to feel calm, not gimmicky ────────────────
// Each entry: { label, rgb (base color triplet), alpha (0..1) }
// The base colors here are deliberately dark because they're *overlays*,
// not wallpapers — they multiply against the underlying page.
const PRESETS = [
  // Late night — 00:00 through 04:59
  { start: 0,  label: 'late night', rgb: [8,  10, 24],  alpha: 0.82 },
  // Dawn — 05:00 through 07:59
  { start: 5,  label: 'dawn',       rgb: [30, 20, 30],  alpha: 0.62 },
  // Morning — 08:00 through 11:59
  { start: 8,  label: 'morning',    rgb: [12, 18, 32],  alpha: 0.58 },
  // Afternoon — 12:00 through 16:59
  { start: 12, label: 'afternoon',  rgb: [18, 18, 22],  alpha: 0.62 },
  // Evening — 17:00 through 19:59
  { start: 17, label: 'evening',    rgb: [34, 20, 12],  alpha: 0.68 },
  // Night — 20:00 through 23:59
  { start: 20, label: 'night',      rgb: [14, 12, 28],  alpha: 0.78 },
]

function presetForHour(hour) {
  // Pick the highest `start` that's <= hour
  let chosen = PRESETS[0]
  for (const p of PRESETS) {
    if (p.start <= hour) chosen = p
  }
  return chosen
}

/**
 * Build a CSS rgba() string from a preset.
 * Kept as a pure function so tests and server rendering can reuse it.
 */
export function backdropColorFor(hour) {
  const p = presetForHour(hour)
  const [r, g, b] = p.rgb
  return {
    color: `rgba(${r}, ${g}, ${b}, ${p.alpha})`,
    label: p.label,
    hour,
  }
}

export default function useTimeOfDayBackdrop() {
  const [hour, setHour] = useState(() => new Date().getHours())

  useEffect(() => {
    // Re-check every 5 minutes so the backdrop drifts across time boundaries
    // without forcing a re-render storm. Cleans up on unmount.
    const tick = () => setHour(new Date().getHours())
    const id = setInterval(tick, 5 * 60 * 1000)
    return () => clearInterval(id)
  }, [])

  const { color, label } = backdropColorFor(hour)

  return {
    color,
    label,
    hour,
    // Convenience: a ready-to-spread style object for fixed-overlay backdrops
    style: {
      background: color,
      transition: 'background 800ms ease',
    },
  }
}
