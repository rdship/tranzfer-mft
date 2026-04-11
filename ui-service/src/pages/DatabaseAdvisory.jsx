import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import {
  CircleStackIcon,
  ClipboardDocumentIcon,
  ArrowDownTrayIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  CheckCircleIcon,
  InformationCircleIcon,
  ServerStackIcon,
} from '@heroicons/react/24/outline'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import ServiceUnavailable from '../components/ServiceUnavailable'

/**
 * DatabaseAdvisory — publishes the evidence-based Postgres tuning
 * recommendations (R23) as a readable, copyable, downloadable page
 * for DBAs running the platform in any environment.
 *
 * Backend source: GET /api/v1/db-advisory (ADMIN auth).
 * Source of truth: config/postgres/postgresql.tuned.conf +
 * DatabaseAdvisoryController. Every setting shown here has a
 * rationale and a scaling note so DBAs can justify the change
 * in their own change-management process.
 *
 * Actions available:
 *   - Copy as postgresql.conf (streamable text from backend)
 *   - Copy as psql ALTER SYSTEM commands (hot-apply without restart)
 *   - Download JSON bundle (versioned, for automation tooling)
 */

async function fetchAdvisory() {
  const res = await onboardingApi.get('/api/v1/db-advisory')
  return res.data
}

async function fetchPostgresConf() {
  const res = await onboardingApi.get('/api/v1/db-advisory/postgres-conf', {
    responseType: 'text',
    transformResponse: r => r,
  })
  return res.data
}

async function fetchPsqlCommands() {
  const res = await onboardingApi.get('/api/v1/db-advisory/psql-commands', {
    responseType: 'text',
    transformResponse: r => r,
  })
  return res.data
}

function copyToClipboard(text, label) {
  navigator.clipboard?.writeText(text)
    .then(() => toast.success(`${label} copied to clipboard`))
    .catch(() => toast.error(`Couldn't copy ${label} — try again`))
}

function downloadFile(content, filename, mime = 'text/plain') {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ── Sub-components ──────────────────────────────────────────────────

function SettingCard({ setting }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <div
      className="rounded-lg p-3 transition-colors"
      style={{
        background: 'rgb(var(--surface))',
        border: '1px solid rgb(var(--border))',
      }}
    >
      <button
        onClick={() => setExpanded(e => !e)}
        className="w-full flex items-start justify-between text-left"
      >
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <code className="text-xs font-mono font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {setting.name}
            </code>
            <span
              className="text-xs font-mono px-2 py-0.5 rounded"
              style={{
                background: 'rgba(100, 140, 255, 0.15)',
                color: 'rgb(var(--accent))',
              }}
            >
              = {setting.value}
            </span>
          </div>
          {!expanded && (
            <p className="text-[11px] mt-1 truncate" style={{ color: 'rgb(var(--tx-muted))' }}>
              {setting.rationale}
            </p>
          )}
        </div>
        {expanded ? (
          <ChevronUpIcon className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }} />
        ) : (
          <ChevronDownIcon className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }} />
        )}
      </button>
      {expanded && (
        <div className="mt-2 space-y-2 text-xs">
          <div>
            <p className="text-[10px] uppercase tracking-wider font-semibold mb-0.5"
               style={{ color: 'rgb(var(--tx-muted))' }}>
              Why this value
            </p>
            <p style={{ color: 'rgb(var(--tx-primary))', lineHeight: 1.5 }}>
              {setting.rationale}
            </p>
          </div>
          <div>
            <p className="text-[10px] uppercase tracking-wider font-semibold mb-0.5"
               style={{ color: 'rgb(var(--tx-muted))' }}>
              How to scale
            </p>
            <p style={{ color: 'rgb(var(--tx-primary))', lineHeight: 1.5 }}>
              {setting.scalingNote}
            </p>
          </div>
        </div>
      )}
    </div>
  )
}

function CategoryBlock({ category }) {
  const [expanded, setExpanded] = useState(category.id === 'memory' || category.id === 'timeouts')
  return (
    <div>
      <button
        onClick={() => setExpanded(e => !e)}
        className="w-full flex items-center justify-between mb-2 px-1"
      >
        <h3 className="text-sm font-bold uppercase tracking-wider"
            style={{ color: 'rgb(var(--tx-primary))' }}>
          {category.label}
          <span className="ml-2 text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
            {category.settings.length}
          </span>
        </h3>
        {expanded ? (
          <ChevronUpIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
        ) : (
          <ChevronDownIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
        )}
      </button>
      {expanded && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
          {category.settings.map(s => (
            <SettingCard key={s.name} setting={s} />
          ))}
        </div>
      )}
    </div>
  )
}

function WorkloadSnapshot({ snapshot }) {
  if (!snapshot) return null
  const stats = [
    { label: 'Services using Postgres', value: snapshot.servicesWithDatabase, icon: ServerStackIcon },
    { label: 'Total Hikari pool size', value: snapshot.totalHikariMaxPool, icon: CircleStackIcon },
    { label: '@Entity classes', value: snapshot.entityClasses, icon: ServerStackIcon },
    { label: 'JSONB/TEXT/BYTEA columns', value: snapshot.jsonbTextByteaColumns + '+', icon: ServerStackIcon },
    { label: 'Batch insert size', value: snapshot.batchSize, icon: ServerStackIcon },
    { label: 'Dashboard poll interval', value: snapshot.dashboardPollInterval, icon: ServerStackIcon },
  ]
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
      {stats.map(stat => {
        const Icon = stat.icon
        return (
          <div
            key={stat.label}
            className="rounded-lg p-3"
            style={{
              background: 'rgb(var(--surface))',
              border: '1px solid rgb(var(--border))',
            }}
          >
            <p className="text-[10px] uppercase tracking-wider font-semibold mb-1"
               style={{ color: 'rgb(var(--tx-muted))' }}>
              {stat.label}
            </p>
            <p className="text-xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {stat.value}
            </p>
          </div>
        )
      })}
    </div>
  )
}

function ScalingTable({ rows }) {
  if (!rows?.length) return null
  return (
    <div
      className="rounded-lg overflow-hidden"
      style={{ border: '1px solid rgb(var(--border))' }}
    >
      <table className="w-full text-xs">
        <thead>
          <tr style={{ background: 'rgb(var(--hover))' }}>
            <th className="text-left px-3 py-2 font-semibold">RAM</th>
            <th className="text-left px-3 py-2 font-semibold">CPUs</th>
            <th className="text-left px-3 py-2 font-semibold">shared_buffers</th>
            <th className="text-left px-3 py-2 font-semibold">effective_cache_size</th>
            <th className="text-left px-3 py-2 font-semibold">work_mem</th>
            <th className="text-left px-3 py-2 font-semibold">maintenance_work_mem</th>
            <th className="text-left px-3 py-2 font-semibold">max_connections</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} style={{ borderTop: '1px solid rgb(var(--border))' }}>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.ram}</td>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.cpus}</td>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.sharedBuffers}</td>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.effectiveCacheSize}</td>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.workMem}</td>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.maintenanceWorkMem}</td>
              <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{row.maxConnections}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ── Main page ───────────────────────────────────────────────────────

export default function DatabaseAdvisory() {
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['db-advisory'],
    queryFn: fetchAdvisory,
    staleTime: 300_000,
    meta: { errorMessage: "Couldn't load database advisory" },
  })

  const handleCopyConf = async () => {
    try {
      const conf = await fetchPostgresConf()
      copyToClipboard(conf, 'postgresql.conf')
    } catch (e) {
      toast.error("Couldn't fetch postgresql.conf from backend")
    }
  }

  const handleCopyPsql = async () => {
    try {
      const sql = await fetchPsqlCommands()
      copyToClipboard(sql, 'psql ALTER SYSTEM commands')
    } catch (e) {
      toast.error("Couldn't fetch psql commands from backend")
    }
  }

  const handleDownloadJson = () => {
    if (!data) return
    downloadFile(
      JSON.stringify(data, null, 2),
      `tranzfer-db-advisory-v${data.version}.json`,
      'application/json',
    )
    toast.success('JSON bundle downloaded')
  }

  const handleDownloadConf = async () => {
    try {
      const conf = await fetchPostgresConf()
      downloadFile(conf, `postgresql.tuned.v${data?.version ?? '1.0'}.conf`, 'text/plain')
      toast.success('postgresql.conf downloaded')
    } catch (e) {
      toast.error("Couldn't download config file")
    }
  }

  if (isLoading) return <LoadingSpinner text="Loading database advisory..." />
  if (isError) {
    return (
      <ServiceUnavailable
        service="onboarding-api"
        port={8080}
        error={error}
        onRetry={refetch}
        title="Database Advisory unavailable"
        hint="The /api/v1/db-advisory endpoint isn't responding. Make sure onboarding-api is running."
      />
    )
  }
  if (!data) return null

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2">
            <CircleStackIcon className="w-6 h-6" style={{ color: 'rgb(var(--accent))' }} />
            <h1 className="text-2xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              Database Advisory
            </h1>
            <span
              className="text-[10px] font-mono px-2 py-0.5 rounded"
              style={{
                background: 'rgb(var(--hover))',
                color: 'rgb(var(--tx-muted))',
              }}
            >
              v{data.version} · Postgres {data.postgresMinVersion}+
            </span>
          </div>
          <p className="text-sm mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
            Evidence-based Postgres tuning for the TranzFer MFT workload.
            Backed by a live audit of the running codebase — every
            setting has a reason and a scaling formula.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            onClick={handleCopyConf}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{ background: 'rgb(var(--accent))', color: '#fff' }}
          >
            <ClipboardDocumentIcon className="w-3.5 h-3.5" />
            Copy postgresql.conf
          </button>
          <button
            onClick={handleCopyPsql}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
          >
            <ClipboardDocumentIcon className="w-3.5 h-3.5" />
            Copy psql commands
          </button>
          <button
            onClick={handleDownloadConf}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
          >
            <ArrowDownTrayIcon className="w-3.5 h-3.5" />
            Download .conf
          </button>
          <button
            onClick={handleDownloadJson}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
          >
            <ArrowDownTrayIcon className="w-3.5 h-3.5" />
            Download JSON
          </button>
        </div>
      </div>

      {/* Summary banner */}
      <div
        className="rounded-xl p-4 flex items-start gap-3"
        style={{
          background: 'rgba(100, 140, 255, 0.08)',
          border: '1px solid rgba(100, 140, 255, 0.25)',
        }}
      >
        <InformationCircleIcon
          className="w-5 h-5 flex-shrink-0 mt-0.5"
          style={{ color: 'rgb(var(--accent))' }}
        />
        <div className="text-xs" style={{ color: 'rgb(var(--tx-primary))' }}>
          <p className="font-semibold mb-1">Why these settings were chosen</p>
          <p style={{ color: 'rgb(var(--tx-secondary))', lineHeight: 1.5 }}>
            {data.summary}
          </p>
          <p className="mt-2" style={{ color: 'rgb(var(--tx-muted))' }}>
            Published on {data.publishedAt}. Apply via psql ALTER SYSTEM for
            zero-downtime changes (shared_buffers / max_connections / wal_level
            still require a restart).
          </p>
        </div>
      </div>

      {/* Workload snapshot */}
      <div>
        <h2 className="text-sm font-bold uppercase tracking-wider mb-3"
            style={{ color: 'rgb(var(--tx-primary))' }}>
          Workload Snapshot
        </h2>
        <WorkloadSnapshot snapshot={data.workloadSnapshot} />
      </div>

      {/* Per-category settings */}
      <div className="space-y-5">
        <h2 className="text-sm font-bold uppercase tracking-wider"
            style={{ color: 'rgb(var(--tx-primary))' }}>
          Recommended Settings
          <span className="ml-2 text-[10px] font-normal" style={{ color: 'rgb(var(--tx-muted))' }}>
            click any row to expand its rationale
          </span>
        </h2>
        {data.categories?.map(cat => (
          <CategoryBlock key={cat.id} category={cat} />
        ))}
      </div>

      {/* Hardware scaling */}
      <div>
        <h2 className="text-sm font-bold uppercase tracking-wider mb-3"
            style={{ color: 'rgb(var(--tx-primary))' }}>
          Hardware Scaling Table
        </h2>
        <p className="text-xs mb-3" style={{ color: 'rgb(var(--tx-muted))' }}>
          Scale every memory setting proportionally. The reference row for the
          demo machine (23 GB RAM, 9 CPUs) is closest to "16 GB / 8 CPUs".
        </p>
        <ScalingTable rows={data.hardwareScaling} />
      </div>

      {/* Verification */}
      {data.verificationChecklist?.length > 0 && (
        <div>
          <h2 className="text-sm font-bold uppercase tracking-wider mb-3"
              style={{ color: 'rgb(var(--tx-primary))' }}>
            Verification Checklist
          </h2>
          <div
            className="rounded-xl p-4 space-y-2"
            style={{
              background: 'rgb(var(--surface))',
              border: '1px solid rgb(var(--border))',
            }}
          >
            {data.verificationChecklist.map((item, i) => (
              <div key={i} className="flex items-start gap-2 text-xs">
                <CheckCircleIcon className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" style={{ color: 'rgb(34, 197, 94)' }} />
                <span style={{ color: 'rgb(var(--tx-primary))' }}>{item}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
