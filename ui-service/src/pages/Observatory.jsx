import { useState, memo, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { format } from 'date-fns'
import { ArrowPathIcon, ChevronDownIcon } from '@heroicons/react/24/outline'
import { getObservatoryData, getStepLatency } from '../api/observatory'
import { useServices } from '../context/ServiceContext'
import LoadingSpinner from '../components/LoadingSpinner'

// ─── Service topology (static layout, live health from API) ──────────────────

const NODES = {
  dmz:        { x: 90,  y: 35,  label: 'DMZ',      tier: 'INGRESS',    scKey: 'dmz' },
  sftp:       { x: 90,  y: 90,  label: 'SFTP',     tier: 'INGRESS',    scKey: 'sftp' },
  ftp:        { x: 90,  y: 145, label: 'FTP',       tier: 'INGRESS',    scKey: 'ftp' },
  'ftp-web':  { x: 90,  y: 200, label: 'FTP Web',  tier: 'INGRESS',    scKey: 'ftpWeb' },
  gateway:    { x: 90,  y: 255, label: 'Gateway',  tier: 'INGRESS',    scKey: 'gateway' },
  encryption: { x: 300, y: 80,  label: 'Encrypt',  tier: 'PROCESSING', scKey: 'encryption' },
  screening:  { x: 300, y: 165, label: 'Screen',   tier: 'PROCESSING', scKey: 'screening' },
  edi:        { x: 300, y: 250, label: 'EDI',       tier: 'PROCESSING', scKey: null },
  forwarder:  { x: 500, y: 100, label: 'Forward',  tier: 'DELIVERY',   scKey: 'forwarder' },
  as2:        { x: 500, y: 200, label: 'AS2',       tier: 'DELIVERY',   scKey: null },
  analytics:  { x: 700, y: 80,  label: 'Analytics',tier: 'PLATFORM',   scKey: 'analytics' },
  'ai-engine':{ x: 700, y: 165, label: 'AI Engine',tier: 'PLATFORM',   scKey: 'aiEngine' },
  sentinel:   { x: 700, y: 250, label: 'Sentinel', tier: 'PLATFORM',   scKey: 'sentinel' },
}

const EDGES = [
  ['dmz',        'encryption'],
  ['sftp',       'encryption'],
  ['ftp',        'encryption'],
  ['ftp-web',    'screening'],
  ['gateway',    'encryption'],
  ['ftp',        'edi'],
  ['encryption', 'forwarder'],
  ['encryption', 'as2'],
  ['screening',  'forwarder'],
  ['edi',        'as2'],
  ['forwarder',  'analytics'],
  ['as2',        'analytics'],
  ['forwarder',  'ai-engine'],
  ['analytics',  'sentinel'],
]

function bezierPath(from, to) {
  const f = NODES[from], t = NODES[to]
  const mx = (f.x + t.x) / 2
  return `M ${f.x},${f.y} C ${mx},${f.y} ${mx},${t.y} ${t.x},${t.y}`
}

function healthColor(h) {
  return h === 'UP' ? '#10b981' : h === 'DEGRADED' ? '#f59e0b' : '#94a3b8'
}

// ─── Network Map ──────────────────────────────────────────────────────────────

const NetworkMap = memo(function NetworkMap({ serviceGraph, services }) {
  const dataMap = useMemo(
    () => Object.fromEntries((serviceGraph || []).map(n => [n.id, n])),
    [serviceGraph]
  )

  function nodeHealth(id) {
    const d = dataMap[id]
    if (d && d.health !== 'UNKNOWN') return d.health
    const scKey = NODES[id]?.scKey
    if (!scKey) return 'UNKNOWN'
    return services[scKey] !== false ? 'UP' : 'DOWN'
  }

  function dotCount(from, to) {
    const total = (dataMap[from]?.transfersLastHour || 0) + (dataMap[to]?.transfersLastHour || 0)
    if (total === 0) return 0
    if (total < 10) return 1
    if (total < 50) return 2
    return 3
  }

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-2">
        <h3 className="font-semibold text-gray-900">Service Network</h3>
        <div className="flex gap-4 text-xs text-gray-400">
          {[['#10b981','Active'],['#f59e0b','Degraded'],['#94a3b8','Unknown']].map(([c,l]) => (
            <span key={l} className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full inline-block" style={{background:c}} />{l}
            </span>
          ))}
        </div>
      </div>

      {/* Column labels */}
      <div className="grid grid-cols-4 text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1 px-4">
        {['Ingress','Processing','Delivery','Platform'].map(l => (
          <span key={l} className="text-center">{l}</span>
        ))}
      </div>

      <svg viewBox="0 0 790 290" className="w-full" style={{height: 270}}>
        {/* Edge paths + animated dots */}
        {EDGES.map(([from, to]) => {
          const path = bezierPath(from, to)
          const dots = dotCount(from, to)
          return (
            <g key={`e-${from}-${to}`}>
              <path d={path} fill="none" stroke="#e2e8f0" strokeWidth={1.5} />
              {Array.from({length: dots}, (_, i) => (
                <circle key={i} r={2.5} fill="#3b82f6" opacity={0.75}>
                  <animateMotion dur="2.8s" begin={`${i * 0.9}s`} repeatCount="indefinite" path={path} rotate="auto" />
                </circle>
              ))}
            </g>
          )
        })}

        {/* Service nodes */}
        {Object.entries(NODES).map(([id, node]) => {
          const health = nodeHealth(id)
          const color  = healthColor(health)
          const data   = dataMap[id]
          const active = (data?.transfersLastHour || 0) > 0
          return (
            <g key={id}>
              {/* Pulse ring on active nodes */}
              {active && (
                <circle cx={node.x} cy={node.y} r={13} fill="none" stroke={color} strokeWidth={1} opacity={0}>
                  <animate attributeName="r"       from="13" to="22" dur="2s" repeatCount="indefinite" />
                  <animate attributeName="opacity" from="0.5" to="0" dur="2s" repeatCount="indefinite" />
                </circle>
              )}
              <circle cx={node.x} cy={node.y} r={12} fill="white" stroke={color} strokeWidth={2} filter={active ? 'drop-shadow(0 0 3px ' + color + '33)' : undefined} />
              <text x={node.x} y={node.y - 16} textAnchor="middle" fontSize={9} fill="#64748b" fontWeight="500">
                {node.label}
              </text>
              {active && (
                <text x={node.x} y={node.y + 22} textAnchor="middle" fontSize={8} fill="#94a3b8">
                  {data.transfersLastHour}
                </text>
              )}
            </g>
          )
        })}
      </svg>
    </div>
  )
}, (prev, next) => {
  // Only re-render NetworkMap (resetting dot animations) when node health or traffic actually changes
  const prevMap = Object.fromEntries((prev.serviceGraph || []).map(n => [n.id, n]))
  const nextMap = Object.fromEntries((next.serviceGraph || []).map(n => [n.id, n]))
  return JSON.stringify(Object.values(prevMap).map(n => ({ id: n.id, health: n.health, t: n.transfersLastHour }))) ===
         JSON.stringify(Object.values(nextMap).map(n => ({ id: n.id, health: n.health, t: n.transfersLastHour }))) &&
         prev.services === next.services
})

// ─── Activity Heatmap ─────────────────────────────────────────────────────────

const DAYS = 30
const HOURS = Array.from({length: 24}, (_, i) => i)

const ActivityHeatmap = memo(function ActivityHeatmap({ heatmapData }) {
  const grid = {}
  let maxCount = 1
  for (const cell of (heatmapData || [])) {
    grid[`${cell.dayOffset}-${cell.hour}`] = cell
    if (cell.count > maxCount) maxCount = cell.count
  }

  function cellBg(dayOffset, hour) {
    const c = grid[`${dayOffset}-${hour}`]
    if (!c || c.count === 0) return '#f1f5f9'
    const intensity = c.count / maxCount
    if (c.count > 0 && c.failedCount / c.count > 0.25)
      return `rgba(239,68,68,${0.2 + intensity * 0.65})`
    return `rgba(59,130,246,${0.12 + intensity * 0.82})`
  }

  function dayLabel(offset) {
    const d = new Date()
    d.setDate(d.getDate() - offset)
    return d.toLocaleDateString('en', {month:'short', day:'numeric'})
  }

  function cellTitle(dayOffset, hour) {
    const c = grid[`${dayOffset}-${hour}`]
    const count = c?.count || 0
    const failed = c?.failedCount || 0
    return `${dayLabel(dayOffset)} ${String(hour).padStart(2,'0')}:00 — ${count} transfers${failed > 0 ? ` (${failed} failed)` : ''}`
  }

  // Columns: oldest (dayOffset=29) on left, today (0) on right
  const colOffsets = Array.from({length: DAYS}, (_, i) => DAYS - 1 - i)

  return (
    <div className="card">
      <h3 className="font-semibold text-gray-900 mb-3">Transfer Activity — Last 30 Days</h3>
      <div className="overflow-x-auto">
        {/* Grid: row = hour, col = day */}
        <div style={{display:'grid', gridTemplateColumns:`28px repeat(${DAYS},1fr)`, gap:1, minWidth:380}}>

          {/* Header row: empty label col + day tick marks */}
          <div />
          {colOffsets.map(d => (
            <div key={d} style={{height:18, display:'flex', alignItems:'flex-end', justifyContent:'center'}}>
              {(d === 0 || d === 7 || d === 14 || d === 21 || d === 28) && (
                <span style={{fontSize:7, color:'#94a3b8', writingMode:'vertical-rl', transform:'rotate(180deg)', lineHeight:1}}>
                  {d === 0 ? 'today' : dayLabel(d)}
                </span>
              )}
            </div>
          ))}

          {/* Rows for each hour */}
          {HOURS.map(hour => (
            <div key={hour} style={{display:'contents'}}>
              <div style={{fontSize:8, color:'#94a3b8', textAlign:'right', paddingRight:3, display:'flex', alignItems:'center', justifyContent:'flex-end'}}>
                {hour % 6 === 0 ? `${hour}h` : ''}
              </div>
              {colOffsets.map(dayOffset => (
                <div key={dayOffset}
                  title={cellTitle(dayOffset, hour)}
                  style={{height:9, borderRadius:1, backgroundColor: cellBg(dayOffset, hour), cursor:'default', transition:'background-color 0.6s ease'}}
                />
              ))}
            </div>
          ))}
        </div>

        {/* Legend */}
        <div className="flex items-center gap-1.5 mt-3 text-xs text-gray-400">
          <span>Less</span>
          {[0,0.2,0.4,0.6,0.8,1].map(v => (
            <div key={v} style={{width:10,height:10,borderRadius:1,
              backgroundColor: v===0 ? '#f1f5f9' : `rgba(59,130,246,${0.12+v*0.82})`}} />
          ))}
          <span>More</span>
          <span className="ml-3 flex items-center gap-1">
            <div style={{width:10,height:10,borderRadius:1,backgroundColor:'rgba(239,68,68,0.55)'}} />
            <span>High failures</span>
          </span>
        </div>
      </div>
    </div>
  )
}

})

// ─── Domain Groups ────────────────────────────────────────────────────────────

function timeAgo(instant) {
  if (!instant) return '—'
  const ms = Date.now() - new Date(instant).getTime()
  if (ms < 60000)    return 'just now'
  if (ms < 3600000)  return `${Math.round(ms / 60000)}m ago`
  if (ms < 86400000) return `${Math.round(ms / 3600000)}h ago`
  return `${Math.round(ms / 86400000)}d ago`
}

function healthBadge(rate) {
  if (rate >= 0.95) return 'text-emerald-700 bg-emerald-50'
  if (rate >= 0.80) return 'text-amber-700 bg-amber-50'
  return 'text-red-700 bg-red-50'
}

function healthDot(rate) {
  if (rate >= 0.95) return 'bg-emerald-500'
  if (rate >= 0.80) return 'bg-amber-500'
  return 'bg-red-500'
}

const DomainGroups = memo(function DomainGroups({ domainGroups }) {
  const [expanded, setExpanded] = useState(null)
  const groups = domainGroups || []

  if (groups.length === 0)
    return (
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Flow Domains</h3>
        <p className="text-sm text-gray-400">No flow activity in the last 7 days.</p>
      </div>
    )

  return (
    <div className="card">
      <h3 className="font-semibold text-gray-900 mb-3">Flow Domains <span className="text-xs text-gray-400 font-normal ml-1">last 7 days</span></h3>
      <div className="space-y-1.5">
        {groups.map(g => {
          const open = expanded === g.domainName
          return (
            <div key={g.domainName} className="rounded-lg border border-gray-100 overflow-hidden">
              <button
                onClick={() => setExpanded(open ? null : g.domainName)}
                className="w-full flex items-center gap-2 px-3 py-2.5 hover:bg-gray-50 transition-colors text-left"
              >
                <span className={`w-2 h-2 rounded-full flex-shrink-0 ${healthDot(g.successRate)}`} />
                <span className="font-medium text-gray-900 text-sm flex-1 truncate">{g.domainName}</span>
                <span className={`text-xs font-semibold px-1.5 py-0.5 rounded flex-shrink-0 ${healthBadge(g.successRate)}`}>
                  {Math.round(g.successRate * 100)}%
                </span>
                <span className="text-xs text-gray-400 flex-shrink-0 w-20 text-right">{g.totalCount} flows</span>
                <span className="text-xs text-gray-300 flex-shrink-0 w-16 text-right">{timeAgo(g.lastActivityAt)}</span>
                <ChevronDownIcon className={`w-3.5 h-3.5 text-gray-400 flex-shrink-0 transition-transform duration-150 ${open ? 'rotate-180' : ''}`} />
              </button>

              {open && (
                <div className="px-3 pb-3 pt-2 border-t border-gray-100 bg-gray-50">
                  <div className="grid grid-cols-3 gap-3 mb-2">
                    <div className="text-center">
                      <p className="text-xl font-bold text-emerald-600">{g.completedCount}</p>
                      <p className="text-xs text-gray-400">Completed</p>
                    </div>
                    <div className="text-center">
                      <p className="text-xl font-bold text-red-500">{g.failedCount}</p>
                      <p className="text-xs text-gray-400">Failed</p>
                    </div>
                    <div className="text-center">
                      <p className="text-xl font-bold text-blue-500">{g.processingCount}</p>
                      <p className="text-xs text-gray-400">In-flight</p>
                    </div>
                  </div>
                  {g.topError && (
                    <p className="text-xs text-red-500 bg-red-50 rounded px-2 py-1 mb-2 truncate" title={g.topError}>
                      {g.topError}
                    </p>
                  )}
                  <a
                    href={`/journey?flow=${encodeURIComponent(g.domainName)}`}
                    className="block text-center text-xs text-blue-600 hover:text-blue-800 mt-1"
                  >
                    View in Transfer Journey →
                  </a>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

})

// ─── Step Latency Heatmap ─────────────────────────────────────────────────────

const STEP_LABELS = {
  COMPRESS_GZIP:   'Compress (GZIP)',
  DECOMPRESS_GZIP: 'Decompress (GZIP)',
  COMPRESS_ZIP:    'Compress (ZIP)',
  DECOMPRESS_ZIP:  'Decompress (ZIP)',
  ENCRYPT_PGP:     'Encrypt (PGP)',
  DECRYPT_PGP:     'Decrypt (PGP)',
  ENCRYPT_AES:     'Encrypt (AES)',
  DECRYPT_AES:     'Decrypt (AES)',
  SCREEN:          'Sanctions Screen',
  MAILBOX:         'Mailbox Delivery',
  FILE_DELIVERY:   'File Delivery',
  ROUTE:           'Route',
  CONVERT_EDI:     'Convert EDI',
  EXECUTE_SCRIPT:  'Execute Script',
  RENAME:          'Rename',
  APPROVE:         'Approval Gate',
}

/** Map 0..max → 0..1 using a soft log scale so mid-range values aren't invisible. */
function latencyIntensity(ms, maxMs) {
  if (!ms || !maxMs || maxMs === 0) return 0
  return Math.log1p(ms) / Math.log1p(maxMs)
}

/** Intensity 0..1 → CSS color (white → amber → red). */
function intensityColor(t) {
  if (t <= 0) return '#18181b'          // empty cell
  if (t < 0.25) return '#1a2e1a'        // fast — dark green tint
  if (t < 0.50) return '#2e2a0a'        // medium — dark amber tint
  if (t < 0.75) return '#3b1a08'        // slow — dark orange tint
  return '#3b0a0a'                       // very slow — dark red
}

function fmtMs(ms) {
  if (!ms) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// HOURS already defined at module scope (line 158) — reused here for the latency heatmap

function StepLatencyHeatmap({ data, hours, onHoursChange }) {
  const summary  = data?.summary  || []
  const heatmap  = data?.heatmap  || []

  // Build lookup: stepType → hourOfDay → avgMs
  const cellMap = useMemo(() => {
    const m = {}
    heatmap.forEach(c => {
      if (!m[c.stepType]) m[c.stepType] = {}
      m[c.stepType][c.hourOfDay] = c
    })
    return m
  }, [heatmap])

  // Max avgMs across all cells (for color scaling)
  const maxMs = useMemo(() => Math.max(...heatmap.map(c => c.avgMs || 0), 1), [heatmap])

  if (summary.length === 0) {
    return (
      <div className="card">
        <p className="section-title mb-2">Step Latency Heatmap</p>
        <p className="text-sm text-center py-8" style={{ color: 'rgb(var(--tx-muted))' }}>
          No step snapshot data yet. Step snapshots are recorded when flows run in virtual-mode.
        </p>
      </div>
    )
  }

  return (
    <div className="card space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <p className="section-title">Step Latency Heatmap</p>
          <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
            Avg duration per step type × hour of day (UTC) · last {hours}h
          </p>
        </div>
        <select
          value={hours}
          onChange={e => onHoursChange(Number(e.target.value))}
          className="text-xs rounded-lg px-2 py-1"
          style={{ background: 'rgb(var(--hover))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-secondary))' }}
        >
          <option value={6}>6h</option>
          <option value={24}>24h</option>
          <option value={48}>48h</option>
          <option value={168}>7d</option>
        </select>
      </div>

      {/* 2D heatmap grid */}
      <div className="overflow-x-auto">
        <div style={{ minWidth: 560 }}>
          {/* Hour axis header */}
          <div className="flex mb-1">
            <div style={{ width: 140, flexShrink: 0 }} />
            {HOURS.map(h => (
              <div key={h} className="flex-1 text-center text-[9px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                {h % 6 === 0 ? `${h}:00` : ''}
              </div>
            ))}
          </div>

          {/* Step rows */}
          {summary.map(row => (
            <div key={row.stepType} className="flex items-center mb-0.5 group">
              {/* Step label */}
              <div
                className="text-[10px] font-medium truncate flex-shrink-0 text-right pr-2"
                style={{ width: 140, color: 'rgb(var(--tx-secondary))' }}
                title={row.stepType}
              >
                {STEP_LABELS[row.stepType] || row.stepType}
              </div>
              {/* Hour cells */}
              {HOURS.map(h => {
                const cell = cellMap[row.stepType]?.[h]
                const t    = latencyIntensity(cell?.avgMs, maxMs)
                const bg   = cell ? intensityColor(t) : '#18181b'
                return (
                  <div
                    key={h}
                    className="flex-1 rounded-sm transition-all cursor-default"
                    style={{ height: 18, background: bg, margin: '0 0.5px' }}
                    title={cell
                      ? `${STEP_LABELS[row.stepType] || row.stepType} @ ${h}:00 UTC\nAvg: ${fmtMs(cell.avgMs)} · ${cell.callCount} calls`
                      : `${STEP_LABELS[row.stepType] || row.stepType} @ ${h}:00 UTC — no data`}
                  />
                )
              })}
            </div>
          ))}
        </div>
      </div>

      {/* Color legend */}
      <div className="flex items-center gap-3 text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
        <span>Slower →</span>
        {['#1a2e1a', '#2e2a0a', '#3b1a08', '#3b0a0a'].map((c, i) => (
          <div key={i} className="w-4 h-3 rounded-sm" style={{ background: c }} />
        ))}
        <span>← Faster</span>
        <span className="ml-auto">Max: {fmtMs(maxMs)}</span>
      </div>

      {/* Summary table */}
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider mb-2" style={{ color: 'rgb(var(--tx-muted))' }}>
          Summary ({hours}h window)
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                {['Step Type', 'Avg', 'P95', 'Min', 'Max', 'Calls', 'Fail %'].map(h => (
                  <th key={h} className="text-left pb-1.5 pr-4 font-semibold uppercase tracking-wider text-[10px]"
                    style={{ color: 'rgb(var(--tx-muted))' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {summary.map(row => (
                <tr key={row.stepType} style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                  <td className="py-1.5 pr-4 font-medium" style={{ color: 'rgb(var(--tx-primary))' }}>
                    {STEP_LABELS[row.stepType] || row.stepType}
                  </td>
                  <td className="py-1.5 pr-4 font-mono" style={{ color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono',monospace" }}>
                    {fmtMs(row.avgMs)}
                  </td>
                  <td className="py-1.5 pr-4 font-mono" style={{ color: 'rgb(var(--tx-secondary))', fontFamily: "'JetBrains Mono',monospace" }}>
                    {fmtMs(row.p95Ms)}
                  </td>
                  <td className="py-1.5 pr-4 font-mono" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono',monospace" }}>
                    {fmtMs(row.minMs)}
                  </td>
                  <td className="py-1.5 pr-4 font-mono" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono',monospace" }}>
                    {fmtMs(row.maxMs)}
                  </td>
                  <td className="py-1.5 pr-4 font-mono" style={{ color: 'rgb(var(--tx-secondary))', fontFamily: "'JetBrains Mono',monospace" }}>
                    {row.totalCalls.toLocaleString()}
                  </td>
                  <td className="py-1.5 font-mono" style={{
                    color: row.failureRate > 0.1 ? '#ef4444' : row.failureRate > 0 ? '#f59e0b' : '#22c55e',
                    fontFamily: "'JetBrains Mono',monospace"
                  }}>
                    {(row.failureRate * 100).toFixed(1)}%
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function Observatory() {
  const { services } = useServices()
  const [latencyHours, setLatencyHours] = useState(24)

  const { data, isLoading, dataUpdatedAt, refetch, isFetching } = useQuery({
    queryKey: ['observatory'],
    queryFn: getObservatoryData,
    refetchInterval: 30_000,
    staleTime: 25_000,
    placeholderData: prev => prev,   // keep previous data visible during background refetch
  })

  const { data: latencyData } = useQuery({
    queryKey: ['step-latency', latencyHours],
    queryFn: () => getStepLatency(latencyHours),
    refetchInterval: 60_000,
    staleTime: 55_000,
    retry: false,
  })

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Observatory</h1>
          <p className="text-gray-500 text-sm">Platform health at a glance</p>
        </div>
        <div className="flex items-center gap-3">
          {dataUpdatedAt > 0 && (
            <span className="text-xs text-gray-400">
              Updated {format(new Date(dataUpdatedAt), 'HH:mm:ss')}
            </span>
          )}
          <button
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 disabled:opacity-50"
          >
            <ArrowPathIcon className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin' : ''}`} />
            Refresh
          </button>
        </div>
      </div>

      {isLoading ? (
        <LoadingSpinner />
      ) : (
        <>
          {/* Living network map — full width */}
          <NetworkMap serviceGraph={data?.serviceGraph || []} services={services} />

          {/* Heatmap + Domain Groups side by side */}
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            <ActivityHeatmap heatmapData={data?.heatmap || []} />
            <DomainGroups domainGroups={data?.domainGroups || []} />
          </div>

          {/* Step Latency Heatmap — full width */}
          <StepLatencyHeatmap
            data={latencyData}
            hours={latencyHours}
            onHoursChange={setLatencyHours}
          />
        </>
      )}
    </div>
  )
}
