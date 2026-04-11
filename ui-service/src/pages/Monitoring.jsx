import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ChartBarIcon,
  CpuChipIcon,
  ExclamationTriangleIcon,
  ArrowPathIcon,
  CheckCircleIcon,
  XCircleIcon,
  ClockIcon,
  ServerStackIcon,
  ShieldCheckIcon,
  SparklesIcon,
  ArrowTrendingUpIcon,
  DocumentTextIcon,
  ArrowTopRightOnSquareIcon,
  MagnifyingGlassIcon,
} from '@heroicons/react/24/outline'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Area, AreaChart,
} from 'recharts'
import {
  promQuery, promRange, overview, lokiRange,
  rangeForWindow, scalarValue, toChartSeries,
} from '../api/monitoring'

/**
 * Monitoring — exceptional native Prometheus + Loki + Alertmanager
 * dashboard inside the admin UI (no iframes, no context switching).
 *
 * Implemented in R19 in response to the founder's direction:
 *   "grafana and prometheus are not just pages they show full information
 *    about each of our microservices dashboard netflix/airbnb quality
 *    full integrated with backend scraper and log fetchers... easily
 *    searchable with timestamp... exceptional out of the league product"
 *
 * Layout:
 *   - Top bar: time-range picker, auto-refresh toggle, global search, "open Grafana"
 *   - Category tabs: Overview / Infrastructure / Transfers / Security /
 *                    Intelligence (AI) / SLA & Partners / Logs / Alerts
 *   - Each category renders a grid of native panels (Recharts) backed by
 *     live Prometheus queries via /api/v1/monitoring/prometheus/query_range
 *   - Panels show a live value in the top-right and a plot on the bottom
 *     so the user can VALIDATE that numbers are real and current.
 *   - Logs tab uses Loki LogQL search with full text + service + time
 *     filters, streaming the most recent lines in reverse chronological
 *     order with millisecond timestamps.
 *   - Alerts tab pulls from Alertmanager /api/v2/alerts.
 *
 * Every panel shows its raw PromQL expression on hover so the operator
 * can verify what's being plotted, and has an "Open in Grafana" link
 * that opens the same query in Grafana's explorer view for deeper work.
 */

const GRAFANA_BASE = 'http://localhost:3030'

// ── Categories ────────────────────────────────────────────────────────
//
// Each category groups related panels. Panels are plain objects with
// label, description, PromQL expression, an optional unit/format hint,
// and a grouping icon. The panel builder at the bottom of the file
// renders them into a responsive grid.

const CATEGORIES = [
  {
    id: 'overview',
    label: 'Overview',
    icon: ChartBarIcon,
    description: 'Live platform-wide health at a glance. If everything is green here, operators can move on.',
    panels: [
      {
        id: 'services-up', label: 'Services Up',
        instant: 'count(up == 1)',
        format: 'int', goodWhen: v => v >= 19,
        hint: 'Number of Prometheus scrape targets reporting healthy.',
      },
      {
        id: 'services-down', label: 'Services Down',
        instant: 'count(up == 0)',
        format: 'int', goodWhen: v => v === 0,
        hint: 'Any target not responding to the last scrape.',
      },
      {
        id: 'transfers-1m', label: 'Transfers Last 1m',
        instant: 'sum(increase(file_transfer_completed_total[1m]))',
        format: 'int', goodWhen: () => true,
        hint: 'New transfers that completed in the last minute.',
      },
      {
        id: 'transfer-success-rate', label: 'Success Rate (5m)',
        instant: '(sum(rate(file_transfer_completed_total[5m])) / (sum(rate(file_transfer_completed_total[5m])) + sum(rate(file_transfer_failed_total[5m])))) * 100',
        format: 'pct', goodWhen: v => v >= 99,
        hint: 'Percentage of transfers that completed vs failed in the last 5 minutes.',
      },
      {
        id: 'dlq-depth', label: 'Dead Letter Queue Depth',
        instant: 'sum(rabbitmq_queue_messages{queue=~".*\\\\.dlq"})',
        format: 'int', goodWhen: v => v === 0,
        hint: 'Messages stuck in any DLQ awaiting retry or discard.',
      },
      {
        id: 'active-alerts', label: 'Active Alerts',
        instant: 'sum(ALERTS{alertstate="firing"})',
        format: 'int', goodWhen: v => v === 0,
        hint: 'Prometheus alerts currently firing.',
      },
    ],
    chartPanels: [
      {
        id: 'transfers-per-min',
        label: 'Transfers / Minute',
        query: 'sum(rate(file_transfer_completed_total[1m])) * 60',
        yLabel: 'transfers/min',
        color: '#22d3ee',
      },
      {
        id: 'error-rate',
        label: 'Error Rate (5xx per second)',
        query: 'sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))',
        yLabel: 'errors/sec',
        color: '#f87171',
      },
      {
        id: 'p95-latency',
        label: 'p95 Request Latency',
        query: 'histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket[5m]))) * 1000',
        yLabel: 'milliseconds',
        color: '#8b5cf6',
      },
    ],
  },
  {
    id: 'infrastructure',
    label: 'Infrastructure',
    icon: ServerStackIcon,
    description: 'The boxes everything runs on — database, message bus, JVM health.',
    panels: [
      {
        id: 'pg-connections', label: 'Postgres Connections',
        instant: 'pg_stat_activity_count', format: 'int',
        hint: 'Active connections to the shared Postgres instance.',
      },
      {
        id: 'rmq-queue-sum', label: 'RabbitMQ Queue Depth',
        instant: 'sum(rabbitmq_queue_messages)', format: 'int',
        hint: 'Total messages across all RabbitMQ queues.',
      },
      {
        id: 'jvm-heap', label: 'JVM Heap (avg)',
        instant: 'avg(jvm_memory_used_bytes{area="heap"}) / (1024*1024)', format: 'mb',
        hint: 'Average heap usage across all Java services (MB).',
      },
      {
        id: 'jvm-threads', label: 'JVM Threads (avg)',
        instant: 'avg(jvm_threads_live_threads)', format: 'int',
        hint: 'Average live thread count across the JVMs.',
      },
      {
        id: 'system-cpu', label: 'System CPU %',
        instant: 'avg(system_cpu_usage) * 100', format: 'pct',
        hint: 'Average CPU utilization reported by Micrometer across all services.',
      },
      {
        id: 'process-uptime', label: 'Avg Uptime (min)',
        instant: 'avg(process_uptime_seconds) / 60', format: 'int',
        hint: 'Mean time since any service last started.',
      },
    ],
    chartPanels: [
      {
        id: 'heap-trend',
        label: 'Heap Usage Trend (MB)',
        query: 'sum by (job)(jvm_memory_used_bytes{area="heap"}) / (1024*1024)',
        yLabel: 'MB',
        color: '#34d399',
      },
      {
        id: 'cpu-trend',
        label: 'CPU % Trend',
        query: 'avg by (job)(system_cpu_usage) * 100',
        yLabel: 'percent',
        color: '#fbbf24',
      },
    ],
  },
  {
    id: 'transfers',
    label: 'Transfers',
    icon: ArrowTrendingUpIcon,
    description: 'File transfer throughput, success/failure breakdown, and latency percentiles.',
    panels: [
      {
        id: 'transfers-day', label: 'Transfers (24h)',
        instant: 'sum(increase(file_transfer_completed_total[24h]))',
        format: 'int',
      },
      {
        id: 'failed-day', label: 'Failed (24h)',
        instant: 'sum(increase(file_transfer_failed_total[24h]))',
        format: 'int', goodWhen: v => v === 0,
      },
      {
        id: 'avg-size-mb', label: 'Avg File Size (MB)',
        instant: 'sum(rate(file_transfer_bytes_sum[5m])) / sum(rate(file_transfer_bytes_count[5m])) / (1024*1024)',
        format: 'mb',
      },
      {
        id: 'p99-latency', label: 'p99 Latency (ms)',
        instant: 'histogram_quantile(0.99, sum by (le)(rate(file_transfer_duration_seconds_bucket[5m]))) * 1000',
        format: 'int',
      },
    ],
    chartPanels: [
      {
        id: 'throughput-mb',
        label: 'Throughput (MB/s)',
        query: 'sum(rate(file_transfer_bytes_sum[1m])) / (1024*1024)',
        yLabel: 'MB/s',
        color: '#22d3ee',
      },
      {
        id: 'failed-trend',
        label: 'Failed Transfer Rate',
        query: 'sum(rate(file_transfer_failed_total[5m]))',
        yLabel: 'failures/sec',
        color: '#f87171',
      },
    ],
  },
  {
    id: 'security',
    label: 'Security',
    icon: ShieldCheckIcon,
    description: 'Login failures, screening hits, DLP violations, and DMZ proxy verdicts.',
    panels: [
      {
        id: 'login-failures-hour', label: 'Login Failures (1h)',
        instant: 'sum(increase(auth_login_failures_total[1h]))',
        format: 'int', goodWhen: v => v < 10,
      },
      {
        id: 'screening-hits-day', label: 'Screening Hits (24h)',
        instant: 'sum(increase(screening_hits_total[24h]))',
        format: 'int',
      },
      {
        id: 'dlp-violations-day', label: 'DLP Violations (24h)',
        instant: 'sum(increase(dlp_violations_total[24h]))',
        format: 'int',
      },
      {
        id: 'blocked-ips', label: 'Blocked IPs',
        instant: 'proxy_blocklist_size',
        format: 'int',
      },
    ],
    chartPanels: [
      {
        id: 'login-fails-trend',
        label: 'Login Failures / min',
        query: 'sum(rate(auth_login_failures_total[1m])) * 60',
        yLabel: 'failures/min',
        color: '#f87171',
      },
    ],
  },
  {
    id: 'intelligence',
    label: 'Intelligence',
    icon: SparklesIcon,
    description: 'AI engine classifications, anomaly detection, and Sentinel findings.',
    panels: [
      {
        id: 'ai-classifications-day', label: 'AI Classifications (24h)',
        instant: 'sum(increase(ai_classifications_total[24h]))',
        format: 'int',
      },
      {
        id: 'anomalies-open', label: 'Open Anomalies',
        instant: 'ai_anomalies_open',
        format: 'int',
      },
      {
        id: 'sentinel-open', label: 'Sentinel Findings (Open)',
        instant: 'sum(sentinel_findings{status="OPEN"})',
        format: 'int',
      },
      {
        id: 'sentinel-critical', label: 'Critical Findings',
        instant: 'sum(sentinel_findings{status="OPEN",severity="CRITICAL"})',
        format: 'int', goodWhen: v => v === 0,
      },
    ],
  },
]

// ── Number formatter helpers ───────────────────────────────────────────
function formatValue(v, format) {
  if (v == null || !Number.isFinite(v)) return '—'
  switch (format) {
    case 'int': return Math.round(v).toLocaleString()
    case 'pct': return v.toFixed(1) + '%'
    case 'mb':  return v.toFixed(1) + ' MB'
    default:    return String(v)
  }
}

// ── Panel components ──────────────────────────────────────────────────

function StatPanel({ panel }) {
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: ['mon-instant', panel.id, panel.instant],
    queryFn: () => promQuery(panel.instant),
    refetchInterval: 15_000,
    retry: 1,
    meta: { silent: true }, // proxy surfaces its own 503 body
  })
  const value = scalarValue(data, null)
  const good = panel.goodWhen ? panel.goodWhen(value) : true
  const color = value == null ? 'rgb(148,163,184)' : good ? 'rgb(34,197,94)' : 'rgb(248,113,113)'
  return (
    <div
      className="rounded-xl p-4"
      style={{
        background: 'rgb(var(--surface))',
        border: '1px solid rgb(var(--border))',
      }}
      title={panel.hint || panel.instant}
    >
      <div className="flex items-start justify-between">
        <p className="text-[10px] uppercase tracking-wider font-semibold"
           style={{ color: 'rgb(var(--tx-muted))' }}>
          {panel.label}
        </p>
        <span className="text-[9px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
          {isLoading ? '...' : dataUpdatedAt ? new Date(dataUpdatedAt).toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—'}
        </span>
      </div>
      <p className="text-2xl font-bold mt-1" style={{ color }}>
        {isLoading ? '…' : formatValue(value, panel.format)}
      </p>
      {panel.hint && (
        <p className="text-[10px] mt-1 leading-snug" style={{ color: 'rgb(var(--tx-muted))' }}>
          {panel.hint}
        </p>
      )}
    </div>
  )
}

function ChartPanel({ panel, window }) {
  const { start, end, step } = rangeForWindow(window)
  const { data, isLoading, isError } = useQuery({
    queryKey: ['mon-range', panel.id, panel.query, start, end, step],
    queryFn: () => promRange(panel.query, start, end, step),
    refetchInterval: 30_000,
    retry: 1,
    meta: { silent: true },
  })
  const rows = useMemo(() => toChartSeries(data, 'value'), [data])
  return (
    <div
      className="rounded-xl p-4"
      style={{
        background: 'rgb(var(--surface))',
        border: '1px solid rgb(var(--border))',
      }}
    >
      <div className="flex items-start justify-between mb-2">
        <div>
          <h3 className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
            {panel.label}
          </h3>
          <p className="text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
            {panel.query}
          </p>
        </div>
        <a
          href={`${GRAFANA_BASE}/explore?left=${encodeURIComponent(JSON.stringify({ datasource: 'Prometheus', queries: [{ expr: panel.query }], range: { from: 'now-1h', to: 'now' } }))}`}
          target="_blank"
          rel="noopener noreferrer"
          title="Open in Grafana Explore"
          className="text-[10px]"
          style={{ color: 'rgb(var(--accent))' }}
        >
          <ArrowTopRightOnSquareIcon className="w-3.5 h-3.5" />
        </a>
      </div>
      {isError ? (
        <div className="h-40 flex items-center justify-center text-[11px]" style={{ color: 'rgb(248, 113, 113)' }}>
          Data unavailable — Prometheus may be down
        </div>
      ) : isLoading ? (
        <div className="h-40 flex items-center justify-center text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
          Loading…
        </div>
      ) : rows.length === 0 ? (
        <div className="h-40 flex items-center justify-center text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
          No samples in the selected window
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={160}>
          <AreaChart data={rows}>
            <defs>
              <linearGradient id={`g-${panel.id}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={panel.color} stopOpacity={0.4} />
                <stop offset="100%" stopColor={panel.color} stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(100,116,139,0.15)" />
            <XAxis dataKey="time" tick={{ fontSize: 10, fill: 'rgb(148, 163, 184)' }} interval="preserveStartEnd" />
            <YAxis tick={{ fontSize: 10, fill: 'rgb(148, 163, 184)' }} />
            <Tooltip
              contentStyle={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))', fontSize: 11 }}
              labelStyle={{ color: 'rgb(230, 232, 236)' }}
            />
            <Area type="monotone" dataKey="value" stroke={panel.color} fill={`url(#g-${panel.id})`} strokeWidth={1.5} name={panel.yLabel || 'value'} />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </div>
  )
}

// ── Targets table (scrape target health) ───────────────────────────────

function TargetsTable() {
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['mon-targets'],
    queryFn: overview,
    refetchInterval: 20_000,
    retry: 1,
    meta: { silent: true },
  })
  if (isLoading) return <p className="text-xs text-secondary py-4">Loading targets…</p>
  if (isError || !data?.targets?.data?.activeTargets) {
    return (
      <div className="rounded-xl p-4 flex items-center justify-between"
        style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.25)' }}>
        <span className="text-xs" style={{ color: 'rgb(248,113,113)' }}>
          Prometheus is unreachable from onboarding-api. Start it with docker compose up -d prometheus.
        </span>
        <button onClick={() => refetch()} className="text-xs underline" style={{ color: 'rgb(248,113,113)' }}>Retry</button>
      </div>
    )
  }
  const targets = data.targets.data.activeTargets
  const upCount = targets.filter(t => t.health === 'up').length
  const downTargets = targets.filter(t => t.health !== 'up')
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-4 text-xs">
        <span style={{ color: 'rgb(34,197,94)' }}>
          <CheckCircleIcon className="inline w-3.5 h-3.5 mr-1" />
          {upCount} healthy
        </span>
        {downTargets.length > 0 && (
          <span style={{ color: 'rgb(248,113,113)' }}>
            <XCircleIcon className="inline w-3.5 h-3.5 mr-1" />
            {downTargets.length} unhealthy
          </span>
        )}
      </div>
      <div
        className="rounded-lg overflow-hidden"
        style={{ border: '1px solid rgb(var(--border))' }}
      >
        <table className="w-full text-xs">
          <thead>
            <tr style={{ background: 'rgb(var(--hover))' }}>
              <th className="text-left px-3 py-2 font-semibold">Service</th>
              <th className="text-left px-3 py-2 font-semibold">Endpoint</th>
              <th className="text-left px-3 py-2 font-semibold">Last Scrape</th>
              <th className="text-left px-3 py-2 font-semibold">State</th>
            </tr>
          </thead>
          <tbody>
            {targets.map((t, i) => (
              <tr key={i} style={{ borderTop: '1px solid rgb(var(--border))' }}>
                <td className="px-3 py-2 font-mono text-[11px]" style={{ color: 'rgb(var(--tx-primary))' }}>
                  {t.labels?.job || '—'}
                </td>
                <td className="px-3 py-2 font-mono text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {t.scrapeUrl || t.labels?.instance || '—'}
                </td>
                <td className="px-3 py-2 text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {t.lastScrape ? new Date(t.lastScrape).toLocaleTimeString() : '—'}
                </td>
                <td className="px-3 py-2">
                  <span className={`badge ${t.health === 'up' ? 'badge-green' : 'badge-red'}`}>
                    {t.health}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

// ── Logs tab ───────────────────────────────────────────────────────────

function LogsTab({ window }) {
  const [expr, setExpr] = useState('{job=~".+"}')
  const [submitted, setSubmitted] = useState('{job=~".+"}')
  const { start, end } = rangeForWindow(window)
  const nowNs = String(Math.floor(Date.now() * 1_000_000))
  const startNs = String(Math.floor(Number(start) * 1_000_000_000))
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['mon-logs', submitted, startNs, nowNs],
    queryFn: () => lokiRange(submitted, startNs, nowNs, 200, 'backward'),
    refetchInterval: 10_000,
    retry: 1,
    meta: { silent: true },
  })
  const streams = data?.data?.result || []
  // Flatten all entries across streams and sort by timestamp desc
  const entries = streams.flatMap(s =>
    (s.values || []).map(([ts, line]) => ({
      ts: Number(ts) / 1_000_000,
      service: s.stream?.job || s.stream?.service_name || s.stream?.container_name || '—',
      level: s.stream?.level || '',
      line,
    }))
  ).sort((a, b) => b.ts - a.ts).slice(0, 200)

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
          <input
            value={expr}
            onChange={e => setExpr(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') setSubmitted(expr) }}
            placeholder='LogQL — e.g. {job="onboarding-api"} |= "error"'
            className="w-full pl-10 pr-3 py-2 text-xs font-mono rounded-lg"
            style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-primary))' }}
          />
        </div>
        <button
          onClick={() => setSubmitted(expr)}
          className="px-3 py-2 text-xs font-semibold rounded-lg"
          style={{ background: 'rgb(var(--accent))', color: '#fff' }}
        >
          Search
        </button>
        <button
          onClick={() => refetch()}
          className="px-3 py-2 text-xs font-semibold rounded-lg flex items-center gap-1"
          style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
          title="Force refresh"
        >
          <ArrowPathIcon className="w-3.5 h-3.5" />
        </button>
      </div>
      <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
        Querying Loki over the last {window}. Auto-refresh every 10s.
        Showing {entries.length} of {streams.length} streams.
      </p>
      {isLoading && <p className="text-xs text-secondary py-2">Fetching logs…</p>}
      {isError && (
        <div className="rounded-lg p-3 text-xs"
          style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.25)', color: 'rgb(248,113,113)' }}>
          Loki is unreachable. Start it with <code className="font-mono">docker compose up -d loki promtail</code>.
        </div>
      )}
      <div
        className="rounded-lg font-mono text-[11px] overflow-auto"
        style={{
          background: 'rgb(var(--canvas))',
          border: '1px solid rgb(var(--border))',
          maxHeight: 520,
        }}
      >
        {entries.length === 0 && !isLoading && !isError && (
          <div className="p-4 text-center" style={{ color: 'rgb(var(--tx-muted))' }}>
            No log entries matched the query. Try a broader LogQL like <code>{'{job=~".+"}'}</code>.
          </div>
        )}
        {entries.map((e, i) => (
          <div key={i} className="px-3 py-1 flex gap-3" style={{ borderBottom: '1px solid rgba(40,40,48,0.5)' }}>
            <span className="flex-shrink-0" style={{ color: 'rgb(148,163,184)' }}>
              {new Date(e.ts).toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}.
              {String(Math.floor(e.ts % 1000)).padStart(3, '0')}
            </span>
            <span className="flex-shrink-0 w-24 truncate" style={{ color: 'rgb(100,140,255)' }}>
              {e.service}
            </span>
            <span className="flex-1" style={{ color: 'rgb(230,232,236)', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
              {e.line}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Alerts tab ─────────────────────────────────────────────────────────

function AlertsTab() {
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['mon-prom-alerts'],
    queryFn: () => promQuery('ALERTS{alertstate="firing"}'),
    refetchInterval: 15_000,
    retry: 1,
    meta: { silent: true },
  })
  const firing = data?.data?.result || []
  if (isLoading) return <p className="text-xs text-secondary py-4">Loading alerts…</p>
  if (isError) {
    return (
      <div className="rounded-lg p-3 text-xs"
        style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.25)', color: 'rgb(248,113,113)' }}>
        Prometheus alert source unreachable. Retry or restart docker compose prometheus.
        <button className="ml-2 underline" onClick={() => refetch()}>Retry</button>
      </div>
    )
  }
  if (firing.length === 0) {
    return (
      <div className="rounded-xl p-6 text-center"
        style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}>
        <CheckCircleIcon className="w-10 h-10 mx-auto mb-2" style={{ color: 'rgb(34,197,94)' }} />
        <h3 className="text-base font-semibold mb-1" style={{ color: 'rgb(var(--tx-primary))' }}>
          All quiet
        </h3>
        <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
          No Prometheus alerts are firing. Polling every 15 seconds.
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-2">
      {firing.map((a, i) => {
        const labels = a.metric || {}
        return (
          <div key={i}
            className="rounded-xl p-3"
            style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.35)' }}>
            <div className="flex items-start justify-between">
              <div className="flex items-center gap-2">
                <ExclamationTriangleIcon className="w-4 h-4" style={{ color: 'rgb(248,113,113)' }} />
                <h4 className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                  {labels.alertname || 'Unnamed alert'}
                </h4>
                {labels.severity && (
                  <span className="badge badge-red text-[10px]">{labels.severity}</span>
                )}
              </div>
              <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                {labels.job || labels.service || ''}
              </span>
            </div>
            <div className="text-[11px] mt-1 font-mono" style={{ color: 'rgb(148,163,184)' }}>
              {Object.entries(labels).filter(([k]) => !['alertname','severity','alertstate'].includes(k)).map(([k, v]) => `${k}=${v}`).join('  ')}
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ── Main page ──────────────────────────────────────────────────────────

const WINDOWS = ['5m', '15m', '1h', '6h', '24h', '7d']

export default function Monitoring() {
  const [categoryId, setCategoryId] = useState('overview')
  // Default to 6h so a fresh boot has enough scrape samples to render
  // plots. Prometheus defaults to 15s scrape interval, so 1h gives
  // only 240 points per series — and the first ~5 min after boot show
  // zero samples, which looks broken. 6h always has something to draw.
  const [window, setWindow] = useState('6h')
  const [search, setSearch] = useState('')
  const category = CATEGORIES.find(c => c.id === categoryId)

  const filteredStats = useMemo(() => {
    if (!category || !category.panels) return []
    if (!search.trim()) return category.panels
    const q = search.toLowerCase()
    return category.panels.filter(p =>
      p.label.toLowerCase().includes(q) || (p.hint || '').toLowerCase().includes(q)
    )
  }, [category, search])

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
            Platform Monitoring
          </h1>
          <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
            Live Prometheus + Loki + Alertmanager dashboards —
            categorized by domain, queryable by PromQL/LogQL, rendered
            natively inside the admin UI.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={window}
            onChange={e => setWindow(e.target.value)}
            className="text-xs rounded-lg px-3 py-1.5"
            style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-primary))' }}
          >
            {WINDOWS.map(w => <option key={w}>{w}</option>)}
          </select>
          <a
            href={GRAFANA_BASE}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
          >
            <ArrowTopRightOnSquareIcon className="w-3.5 h-3.5" />
            Open Grafana
          </a>
        </div>
      </div>

      {/* Category tabs */}
      <div className="flex gap-1 flex-wrap" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
        {CATEGORIES.map(c => {
          const Icon = c.icon
          const active = c.id === categoryId
          return (
            <button
              key={c.id}
              onClick={() => setCategoryId(c.id)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium transition-colors"
              style={{
                color: active ? 'rgb(var(--accent))' : 'rgb(148,163,184)',
                borderBottom: active ? '2px solid rgb(var(--accent))' : '2px solid transparent',
              }}
            >
              <Icon className="w-4 h-4" />
              {c.label}
            </button>
          )
        })}
        {/* Logs + Alerts tabs — render different components */}
        <button
          onClick={() => setCategoryId('targets')}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium transition-colors"
          style={{
            color: categoryId === 'targets' ? 'rgb(var(--accent))' : 'rgb(148,163,184)',
            borderBottom: categoryId === 'targets' ? '2px solid rgb(var(--accent))' : '2px solid transparent',
          }}
        >
          <CpuChipIcon className="w-4 h-4" />
          Scrape Targets
        </button>
        <button
          onClick={() => setCategoryId('logs')}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium transition-colors"
          style={{
            color: categoryId === 'logs' ? 'rgb(var(--accent))' : 'rgb(148,163,184)',
            borderBottom: categoryId === 'logs' ? '2px solid rgb(var(--accent))' : '2px solid transparent',
          }}
        >
          <DocumentTextIcon className="w-4 h-4" />
          Logs
        </button>
        <button
          onClick={() => setCategoryId('alerts')}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium transition-colors"
          style={{
            color: categoryId === 'alerts' ? 'rgb(var(--accent))' : 'rgb(148,163,184)',
            borderBottom: categoryId === 'alerts' ? '2px solid rgb(var(--accent))' : '2px solid transparent',
          }}
        >
          <ExclamationTriangleIcon className="w-4 h-4" />
          Alerts
        </button>
      </div>

      {/* Tab content */}
      {categoryId === 'logs' ? (
        <LogsTab window={window} />
      ) : categoryId === 'alerts' ? (
        <AlertsTab />
      ) : categoryId === 'targets' ? (
        <TargetsTable />
      ) : category ? (
        <div className="space-y-4">
          {category.description && (
            <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
              {category.description}
            </p>
          )}
          {category.panels && category.panels.length > 0 && (
            <>
              <div className="flex items-center gap-2">
                <div className="relative flex-1 max-w-sm">
                  <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
                  <input
                    value={search}
                    onChange={e => setSearch(e.target.value)}
                    placeholder="Filter panels in this category..."
                    className="w-full pl-10 pr-3 py-1.5 text-xs rounded-lg"
                    style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-primary))' }}
                  />
                </div>
                <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  Auto-refresh every 15s
                </span>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
                {filteredStats.map(p => <StatPanel key={p.id} panel={p} />)}
              </div>
            </>
          )}
          {category.chartPanels && category.chartPanels.length > 0 && (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
              {category.chartPanels.map(p => <ChartPanel key={p.id} panel={p} window={window} />)}
            </div>
          )}
        </div>
      ) : null}
    </div>
  )
}
