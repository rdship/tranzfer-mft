import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts'

/**
 * DashboardVolumeChart — the 24-hour transfer-volume area chart on Dashboard.
 * Extracted into its own module so React.lazy() can pull `recharts` out of
 * the main bundle. Result: vendor-charts (~87 kB gzip) no longer loads on
 * first paint.
 */

function DarkTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div
      style={{
        background: '#18181b',
        border: '1px solid #3f3f46',
        borderRadius: '8px',
        padding: '8px 12px',
        fontSize: '12px',
      }}
    >
      <p style={{ color: '#a1a1aa', marginBottom: 2 }}>{label}</p>
      {payload.map((p) => (
        <p
          key={p.dataKey}
          style={{
            color: p.color || '#8b5cf6',
            fontWeight: 600,
            fontFamily: 'JetBrains Mono, monospace',
          }}
        >
          {p.value?.toLocaleString()}
        </p>
      ))}
    </div>
  )
}

export default function DashboardVolumeChart({ data }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <AreaChart data={data} margin={{ top: 4, right: 4, left: -20, bottom: 0 }}>
        <defs>
          <linearGradient id="gradViolet" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%"   stopColor="#8b5cf6" stopOpacity={0.35} />
            <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="rgb(var(--border))" vertical={false} />
        <XAxis
          dataKey="hour"
          tick={{ fontSize: 10, fill: 'rgb(var(--tx-muted))' }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tick={{ fontSize: 10, fill: 'rgb(var(--tx-muted))' }}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip content={<DarkTooltip />} />
        <Area
          type="monotone"
          dataKey="transfers"
          stroke="#8b5cf6"
          strokeWidth={2.5}
          fill="url(#gradViolet)"
          dot={false}
          activeDot={{ r: 4, fill: '#8b5cf6', strokeWidth: 0 }}
        />
      </AreaChart>
    </ResponsiveContainer>
  )
}
