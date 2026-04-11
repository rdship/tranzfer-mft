import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts'

/**
 * DashboardProtocolPie — protocol-breakdown donut on Dashboard. Extracted
 * into its own module alongside DashboardVolumeChart so React.lazy()
 * can keep `recharts` out of the main bundle.
 */

const NEON = ['#8b5cf6', '#22d3ee', '#34d399', '#f87171', '#fbbf24']

function DarkTooltip({ active, payload }) {
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
      {payload.map((p) => (
        <p
          key={p.name}
          style={{
            color: p.payload.fill || '#8b5cf6',
            fontWeight: 600,
            fontFamily: 'JetBrains Mono, monospace',
          }}
        >
          {p.name}: {p.value?.toLocaleString()}
        </p>
      ))}
    </div>
  )
}

export default function DashboardProtocolPie({ data }) {
  return (
    <ResponsiveContainer width="100%" height={150}>
      <PieChart>
        <Pie
          data={data}
          cx="50%"
          cy="50%"
          innerRadius={42}
          outerRadius={68}
          dataKey="value"
          strokeWidth={0}
        >
          {data.map((_, i) => (
            <Cell key={i} fill={NEON[i % NEON.length]} />
          ))}
        </Pie>
        <Tooltip content={<DarkTooltip />} />
      </PieChart>
    </ResponsiveContainer>
  )
}
