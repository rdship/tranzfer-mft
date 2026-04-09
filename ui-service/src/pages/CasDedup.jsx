import { useQuery } from '@tanstack/react-query'
import { getDedupStats } from '../api/analytics'
import {
  CircleStackIcon, ArrowPathIcon, ChartBarIcon,
  CheckCircleIcon, ExclamationTriangleIcon,
} from '@heroicons/react/24/outline'

// ── Helpers ────────────────────────────────────────────────────────────────────

function fmtBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 2 : 0)} ${units[Math.min(i, units.length - 1)]}`
}

function fmtRatio(r) {
  if (!r || r <= 1) return '1.00×'
  return `${r.toFixed(2)}×`
}

// ── Big stat tile ──────────────────────────────────────────────────────────────

function StatTile({ label, value, sub, color, icon: Icon }) {
  return (
    <div
      className="rounded-xl p-4 flex items-start gap-3"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
    >
      <div
        className="p-2 rounded-lg flex-shrink-0 mt-0.5"
        style={{ background: `${color}18` }}
      >
        <Icon className="w-5 h-5" style={{ color }} />
      </div>
      <div className="min-w-0">
        <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {label}
        </p>
        <p
          className="mt-1 text-2xl font-bold leading-none font-mono"
          style={{ color, fontFamily: "'JetBrains Mono', monospace" }}
        >
          {value}
        </p>
        {sub && (
          <p className="mt-1 text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>{sub}</p>
        )}
      </div>
    </div>
  )
}

// ── Tier bar ──────────────────────────────────────────────────────────────────

const TIER_COLOR = { HOT: '#ef4444', WARM: '#f59e0b', COLD: '#60a5fa' }

function TierBar({ tiers, totalBytes }) {
  if (!tiers?.length || !totalBytes) return null
  return (
    <div className="space-y-2.5">
      {tiers.map(t => {
        const pct = totalBytes > 0 ? (t.sizeBytes / totalBytes) * 100 : 0
        const color = TIER_COLOR[t.tier] || '#71717a'
        return (
          <div key={t.tier}>
            <div className="flex items-center justify-between text-xs mb-1">
              <span className="font-semibold" style={{ color }}>{t.tier}</span>
              <span className="font-mono" style={{ color: 'rgb(var(--tx-secondary))', fontFamily: "'JetBrains Mono',monospace" }}>
                {fmtBytes(t.sizeBytes)} · {t.count.toLocaleString()} objects
              </span>
            </div>
            <div className="h-2 rounded-full overflow-hidden" style={{ background: 'rgb(var(--hover))' }}>
              <div
                className="h-full rounded-full transition-all duration-700"
                style={{ width: `${Math.max(pct, 0.5)}%`, background: color }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ── Top deduped table ─────────────────────────────────────────────────────────

function TopDedupTable({ rows }) {
  if (!rows?.length) {
    return (
      <p className="text-sm text-center py-6" style={{ color: 'rgb(var(--tx-muted))' }}>
        No deduplicated files found yet. Files are deduplicated on second upload.
      </p>
    )
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr style={{ borderBottom: '1px solid rgb(var(--border))' }}>
            {['SHA-256 (prefix)', 'File size', 'References', 'Bytes saved', 'Tier'].map(h => (
              <th key={h} className="text-left py-2 pr-4 font-semibold uppercase tracking-wider text-[10px]"
                style={{ color: 'rgb(var(--tx-muted))' }}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => {
            const tier = (row.tier || 'UNKNOWN').toUpperCase()
            const tc = TIER_COLOR[tier] || '#71717a'
            return (
              <tr key={i} style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                <td className="py-2 pr-4 font-mono text-[11px]" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono',monospace" }}>
                  {row.sha256Short}
                </td>
                <td className="py-2 pr-4 font-mono" style={{ color: 'rgb(var(--tx-secondary))', fontFamily: "'JetBrains Mono',monospace" }}>
                  {fmtBytes(row.sizeBytes)}
                </td>
                <td className="py-2 pr-4 font-mono font-bold" style={{ color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono',monospace" }}>
                  {(row.refCount || 0).toLocaleString()}×
                </td>
                <td className="py-2 pr-4 font-mono font-bold" style={{ color: '#22c55e', fontFamily: "'JetBrains Mono',monospace" }}>
                  {fmtBytes(row.bytesSaved)}
                </td>
                <td className="py-2">
                  <span
                    className="px-2 py-0.5 rounded-full text-[9px] font-bold uppercase"
                    style={{ background: `${tc}20`, color: tc }}
                  >
                    {tier}
                  </span>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function CasDedup() {
  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['dedup-stats'],
    queryFn: getDedupStats,
    refetchInterval: 300_000,  // was 60s — dedup ratio changes on minutes/hours timescale
    staleTime: 55_000,
    retry: false,
  })

  const saved      = data?.bytesSaved           || 0
  const stored     = data?.uniqueBytesStored     || 0
  const referenced = data?.totalBytesReferenced  || 0
  const ratio      = data?.deduplicationRatio    || 1
  const pct        = data?.savingsPercent        || 0
  const uniqueObj  = data?.uniqueObjects         || 0
  const totalRefs  = data?.totalReferences       || 0
  const dedupedObj = data?.dedupedObjects        || 0
  const tiers      = data?.tierBreakdown         || []
  const top        = data?.topDeduplicated       || []

  return (
    <div className="space-y-6">

      {/* ── Header ── */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <CircleStackIcon className="w-5 h-5" style={{ color: 'rgb(var(--accent))' }} />
            <h1 className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              CAS Deduplication Savings
            </h1>
          </div>
          <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
            Storage saved by SHA-256 content-addressed deduplication · refreshes every 60 s
          </p>
        </div>
        <button
          onClick={() => refetch()}
          disabled={isFetching}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all"
          style={{ background: 'rgb(var(--hover))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-secondary))' }}
          onMouseEnter={e => { e.currentTarget.style.background = 'rgb(var(--accent))'; e.currentTarget.style.color = '#fff' }}
          onMouseLeave={e => { e.currentTarget.style.background = 'rgb(var(--hover))'; e.currentTarget.style.color = 'rgb(var(--tx-secondary))' }}
        >
          <ArrowPathIcon className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {/* ── Error / unavailable banner ── */}
      {data?.error && (
        <div
          className="flex items-center gap-3 p-3 rounded-xl"
          style={{ background: 'rgb(120 53 15 / 0.15)', border: '1px solid rgb(245 158 11 / 0.3)' }}
        >
          <ExclamationTriangleIcon className="w-5 h-5 flex-shrink-0" style={{ color: '#f59e0b' }} />
          <p className="text-sm" style={{ color: '#f59e0b' }}>{data.error}</p>
        </div>
      )}

      {isLoading ? (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="h-24 rounded-xl animate-pulse" style={{ background: 'rgb(var(--surface))' }} />
          ))}
        </div>
      ) : (
        <>
          {/* ── KPI row ── */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <StatTile
              label="Bytes Saved"
              value={fmtBytes(saved)}
              sub={`${pct.toFixed(1)}% reduction`}
              color="#22c55e"
              icon={CheckCircleIcon}
            />
            <StatTile
              label="Dedup Ratio"
              value={fmtRatio(ratio)}
              sub={`${fmtBytes(referenced)} → ${fmtBytes(stored)}`}
              color="#8b5cf6"
              icon={CircleStackIcon}
            />
            <StatTile
              label="Unique Objects"
              value={(uniqueObj).toLocaleString()}
              sub={`${(totalRefs).toLocaleString()} total references`}
              color="#22d3ee"
              icon={ChartBarIcon}
            />
            <StatTile
              label="Deduped Objects"
              value={(dedupedObj).toLocaleString()}
              sub={uniqueObj > 0 ? `${((dedupedObj / uniqueObj) * 100).toFixed(1)}% of unique objects` : undefined}
              color="#f59e0b"
              icon={ArrowPathIcon}
            />
          </div>

          {/* ── Savings visualisation bar ── */}
          {referenced > 0 && (
            <div
              className="rounded-xl p-4"
              style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
            >
              <div className="flex items-center justify-between mb-3">
                <p className="section-title">Storage Reduction</p>
                <span className="text-xs font-mono" style={{ color: '#22c55e', fontFamily: "'JetBrains Mono',monospace" }}>
                  {fmtBytes(saved)} saved
                </span>
              </div>
              <div className="relative h-8 rounded-lg overflow-hidden" style={{ background: 'rgb(var(--hover))' }}>
                {/* Actually stored */}
                <div
                  className="absolute top-0 left-0 h-full rounded-lg flex items-center justify-center"
                  style={{
                    width: `${Math.max((stored / referenced) * 100, 2)}%`,
                    background: 'rgb(var(--accent))',
                    transition: 'width 0.7s ease',
                  }}
                >
                  <span className="text-[10px] font-bold text-white px-1 truncate">{fmtBytes(stored)}</span>
                </div>
                {/* Saved portion label */}
                <div className="absolute right-2 top-0 h-full flex items-center">
                  <span className="text-[10px] font-bold" style={{ color: '#22c55e' }}>
                    {fmtBytes(saved)} saved
                  </span>
                </div>
              </div>
              <div className="flex items-center gap-4 mt-2 text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                <div className="flex items-center gap-1.5">
                  <div className="w-3 h-3 rounded-sm" style={{ background: 'rgb(var(--accent))' }} />
                  Stored ({fmtBytes(stored)})
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-3 h-3 rounded-sm" style={{ background: 'rgb(var(--hover))' }} />
                  Would-be without dedup ({fmtBytes(referenced)})
                </div>
              </div>
            </div>
          )}

          {/* ── Tier breakdown + Top deduped ── */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div
              className="rounded-xl p-4"
              style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
            >
              <p className="section-title mb-4">Storage Tiers</p>
              {tiers.length === 0 ? (
                <p className="text-sm text-center py-4" style={{ color: 'rgb(var(--tx-muted))' }}>
                  No tier data available
                </p>
              ) : (
                <TierBar tiers={tiers} totalBytes={stored} />
              )}
            </div>

            <div
              className="lg:col-span-2 rounded-xl p-4"
              style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
            >
              <p className="section-title mb-4">Top Deduplicated Files</p>
              <TopDedupTable rows={top} />
            </div>
          </div>
        </>
      )}
    </div>
  )
}
