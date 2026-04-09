/**
 * StatCard — theme-aware KPI tile.
 *
 * Props:
 *   title    string          — metric label
 *   value    string|number   — main number / value
 *   subtitle string?         — secondary line below value
 *   icon     HeroIcon?       — optional icon component
 *   color    'blue'|'green'|'amber'|'red'|'purple'|'teal'|'accent'
 *   trend    number?         — % change vs reference (positive = up)
 *   mono     boolean?        — use monospace font for value (default true)
 */

const ICON_COLORS = {
  blue:   { bg: '#1e3a8a22', icon: '#60a5fa' },
  green:  { bg: '#14532d22', icon: '#4ade80' },
  amber:  { bg: '#78350f22', icon: '#fbbf24' },
  red:    { bg: '#7f1d1d22', icon: '#f87171' },
  purple: { bg: '#4c1d9522', icon: '#c084fc' },
  teal:   { bg: '#0f4c3922', icon: '#2dd4bf' },
  accent: { bg: 'rgb(var(--accent) / 0.12)', icon: 'rgb(var(--accent))' },
}

// Light theme overrides (for elevated / compliance)
const ICON_COLORS_LIGHT = {
  blue:   { bg: '#dbeafe', icon: '#2563eb' },
  green:  { bg: '#dcfce7', icon: '#16a34a' },
  amber:  { bg: '#fef3c7', icon: '#d97706' },
  red:    { bg: '#fee2e2', icon: '#dc2626' },
  purple: { bg: '#f3e8ff', icon: '#9333ea' },
  teal:   { bg: '#ccfbf1', icon: '#0d9488' },
  accent: { bg: 'rgb(var(--accent-soft))', icon: 'rgb(var(--accent))' },
}

export default function StatCard({ title, value, subtitle, icon: Icon, color = 'accent', trend, mono = true }) {
  // Detect dark themes via CSS variable sniffing at render time
  // We use accent-soft which is very dark in dark themes
  const isDark = typeof window !== 'undefined' &&
    getComputedStyle(document.documentElement).getPropertyValue('--canvas').trim().startsWith('9 9') ||
    typeof window !== 'undefined' &&
    getComputedStyle(document.querySelector('[data-theme]') || document.documentElement)
      .getPropertyValue('--canvas').trim() === '9 9 11' ||
    false

  const palette = ICON_COLORS[color] || ICON_COLORS.accent

  return (
    <div
      className="card flex items-start gap-4 group transition-all duration-200"
      style={{ cursor: 'default' }}
    >
      {Icon && (
        <div
          className="p-2.5 rounded-xl flex-shrink-0 mt-0.5 transition-transform duration-200 group-hover:scale-110"
          style={{ background: palette.bg }}
        >
          <Icon className="w-5 h-5" style={{ color: palette.icon }} />
        </div>
      )}

      <div className="flex-1 min-w-0">
        <p className="text-xs font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {title}
        </p>

        <p
          className={`mt-1 text-2xl font-bold leading-none ${mono ? 'font-mono' : ''}`}
          style={{ color: 'rgb(var(--tx-primary))', fontFamily: mono ? "'JetBrains Mono', 'Fira Code', monospace" : undefined }}
        >
          {value}
        </p>

        {subtitle && (
          <p className="mt-1 text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
            {subtitle}
          </p>
        )}

        {trend !== undefined && (
          <div className="mt-1.5 flex items-center gap-1">
            <span
              className="text-xs font-semibold"
              style={{ color: trend >= 0 ? '#22c55e' : '#ef4444' }}
            >
              {trend >= 0 ? '↑' : '↓'} {Math.abs(trend).toFixed(1)}%
            </span>
            <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>vs yesterday</span>
          </div>
        )}
      </div>
    </div>
  )
}
