import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
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
  ArrowPathIcon,
  PlayCircleIcon,
  XCircleIcon,
  ExclamationTriangleIcon,
  XMarkIcon,
  BoltIcon,
} from '@heroicons/react/24/outline'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import ServiceUnavailable from '../components/ServiceUnavailable'
import Modal from '../components/Modal'

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

async function fetchStatus() {
  const res = await onboardingApi.get('/api/v1/db-advisory/status')
  return res.data
}

async function applyAdvisory(payload) {
  const res = await onboardingApi.post('/api/v1/db-advisory/apply', payload)
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

// ── Live status strip (current vs recommended) ─────────────────────

function StatusStrip({ status, isLoading, onRefresh }) {
  if (isLoading) {
    return (
      <div className="rounded-xl p-4 text-xs" style={{
        background: 'rgb(var(--surface))',
        border: '1px solid rgb(var(--border))',
        color: 'rgb(var(--tx-muted))',
      }}>Checking current Postgres settings…</div>
    )
  }
  if (!status) return null
  const pct = status.compliancePct ?? 0
  const color = pct >= 95 ? 'rgb(34,197,94)' : pct >= 75 ? 'rgb(245,158,11)' : 'rgb(248,113,113)'
  return (
    <div
      className="rounded-xl p-4"
      style={{
        background: 'rgb(var(--surface))',
        border: '1px solid rgb(var(--border))',
      }}
    >
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-3">
          <div
            className="w-12 h-12 rounded-full flex items-center justify-center"
            style={{
              background: `conic-gradient(${color} ${pct * 3.6}deg, rgb(var(--hover)) 0deg)`,
            }}
          >
            <div
              className="w-10 h-10 rounded-full flex items-center justify-center text-[11px] font-bold"
              style={{ background: 'rgb(var(--surface))', color }}
            >
              {pct}%
            </div>
          </div>
          <div>
            <p className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
              Current compliance
            </p>
            <p className="text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
              Live comparison against the running Postgres instance
            </p>
          </div>
        </div>
        <button
          onClick={onRefresh}
          className="inline-flex items-center gap-1 px-2 py-1 text-[11px] rounded"
          style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
          title="Refresh status"
        >
          <ArrowPathIcon className="w-3 h-3" /> Refresh
        </button>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
        <div>
          <p className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Matched
          </p>
          <p className="font-bold" style={{ color: 'rgb(34,197,94)' }}>
            {status.matched} <span className="text-[10px] font-normal" style={{ color: 'rgb(var(--tx-muted))' }}>/ {status.totalSettings}</span>
          </p>
        </div>
        <div>
          <p className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Drifted
          </p>
          <p className="font-bold" style={{ color: status.drifted > 0 ? 'rgb(245,158,11)' : 'rgb(var(--tx-muted))' }}>
            {status.drifted}
          </p>
        </div>
        <div>
          <p className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Restart required
          </p>
          <p className="font-bold" style={{ color: status.restartRequired > 0 ? 'rgb(100,140,255)' : 'rgb(var(--tx-muted))' }}>
            {status.restartRequired}
          </p>
        </div>
        <div>
          <p className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Unknown
          </p>
          <p className="font-bold" style={{ color: status.unknown > 0 ? 'rgb(248,113,113)' : 'rgb(var(--tx-muted))' }}>
            {status.unknown}
          </p>
        </div>
      </div>
      {status.drifted > 0 && (
        <div className="mt-3 pt-3" style={{ borderTop: '1px solid rgb(var(--border))' }}>
          <p className="text-[10px] uppercase tracking-wider mb-2" style={{ color: 'rgb(var(--tx-muted))' }}>
            Drifted settings
          </p>
          <div className="space-y-1 max-h-48 overflow-y-auto">
            {status.diffs.filter(d => !d.match && !d.restartRequired).map(d => (
              <div key={d.name} className="flex items-center justify-between text-[11px] font-mono">
                <span style={{ color: 'rgb(var(--tx-primary))' }}>{d.name}</span>
                <span>
                  <span style={{ color: 'rgb(248,113,113)' }}>{d.current || '—'}</span>
                  <span style={{ color: 'rgb(var(--tx-muted))' }}> → </span>
                  <span style={{ color: 'rgb(34,197,94)' }}>{d.recommended}</span>
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ── Apply modal ─────────────────────────────────────────────────────

function ApplyModal({ onClose, onComplete }) {
  const [useOverride, setUseOverride] = useState(false)
  const [jdbcUrl, setJdbcUrl] = useState('jdbc:postgresql://postgres:5432/filetransfer')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [onlyDrifted, setOnlyDrifted] = useState(true)
  const [reload, setReload] = useState(true)
  const [confirmed, setConfirmed] = useState(false)
  const [result, setResult] = useState(null)
  const applyMut = useMutation({
    mutationFn: applyAdvisory,
    onSuccess: (data) => {
      setResult(data)
      const s = data.summary
      toast.success(`Apply complete — ${s.applied} applied, ${s.restartRequired} need restart, ${s.failed} failed`)
      onComplete?.()
    },
    onError: (err) => {
      const msg = err?.response?.data?.message || err?.message || 'Apply failed'
      toast.error(msg)
    },
  })

  const payload = {
    onlyDrifted,
    reload,
    ...(useOverride ? { jdbcUrl, username, password } : {}),
  }
  const canSubmit = confirmed
    && !applyMut.isPending
    && (!useOverride || (jdbcUrl.trim() && username.trim()))

  return (
    <Modal title="Apply recommended settings" onClose={onClose} size="xl">
      <div className="space-y-4">
        {!result && (
          <>
            <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
              This runs <code className="font-mono">ALTER SYSTEM SET …</code> for every recommended setting against
              the target Postgres, then calls <code className="font-mono">pg_reload_conf()</code> so reloadable
              settings take effect immediately. Settings with <code className="font-mono">context=postmaster</code>
              (shared_buffers, max_connections, wal_level, etc.) will be accepted but need a Postgres restart.
            </p>

            {/* Target selector */}
            <div className="space-y-2">
              <p className="text-[11px] uppercase tracking-wider font-semibold"
                 style={{ color: 'rgb(var(--tx-muted))' }}>
                Target Postgres
              </p>
              <label className="flex items-start gap-2 cursor-pointer p-3 rounded-lg"
                style={{
                  background: !useOverride ? 'rgba(100,140,255,0.08)' : 'rgb(var(--surface))',
                  border: `1px solid ${!useOverride ? 'rgba(100,140,255,0.35)' : 'rgb(var(--border))'}`,
                }}>
                <input
                  type="radio"
                  checked={!useOverride}
                  onChange={() => setUseOverride(false)}
                  className="mt-0.5"
                />
                <div className="flex-1">
                  <p className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                    Use the current platform database
                  </p>
                  <p className="text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                    Runs the changes against the Postgres onboarding-api is already connected to,
                    using the service's own admin credentials. Safest option for demo and typical prod.
                  </p>
                </div>
              </label>
              <label className="flex items-start gap-2 cursor-pointer p-3 rounded-lg"
                style={{
                  background: useOverride ? 'rgba(100,140,255,0.08)' : 'rgb(var(--surface))',
                  border: `1px solid ${useOverride ? 'rgba(100,140,255,0.35)' : 'rgb(var(--border))'}`,
                }}>
                <input
                  type="radio"
                  checked={useOverride}
                  onChange={() => setUseOverride(true)}
                  className="mt-0.5"
                />
                <div className="flex-1">
                  <p className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
                    Connect to a different database
                  </p>
                  <p className="text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                    Target another Postgres instance (standby, secondary, DBA-managed cluster)
                    with admin-provided credentials. The backend connects directly via JDBC;
                    the credentials are never logged or persisted.
                  </p>
                  {useOverride && (
                    <div className="mt-3 space-y-2">
                      <div>
                        <label className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                          JDBC URL
                        </label>
                        <input
                          value={jdbcUrl}
                          onChange={e => setJdbcUrl(e.target.value)}
                          placeholder="jdbc:postgresql://host:5432/database"
                          className="w-full px-3 py-1.5 text-xs font-mono rounded-lg"
                          style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-primary))' }}
                        />
                      </div>
                      <div className="grid grid-cols-2 gap-2">
                        <div>
                          <label className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                            Username
                          </label>
                          <input
                            value={username}
                            onChange={e => setUsername(e.target.value)}
                            placeholder="postgres"
                            className="w-full px-3 py-1.5 text-xs font-mono rounded-lg"
                            style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-primary))' }}
                          />
                        </div>
                        <div>
                          <label className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                            Password
                          </label>
                          <input
                            type="password"
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                            className="w-full px-3 py-1.5 text-xs font-mono rounded-lg"
                            style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-primary))' }}
                          />
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              </label>
            </div>

            {/* Options */}
            <div className="space-y-2">
              <p className="text-[11px] uppercase tracking-wider font-semibold"
                 style={{ color: 'rgb(var(--tx-muted))' }}>
                Options
              </p>
              <label className="flex items-center gap-2 text-xs cursor-pointer">
                <input type="checkbox" checked={onlyDrifted} onChange={e => setOnlyDrifted(e.target.checked)} />
                <span style={{ color: 'rgb(var(--tx-primary))' }}>Only apply settings that have drifted from the recommendation</span>
              </label>
              <label className="flex items-center gap-2 text-xs cursor-pointer">
                <input type="checkbox" checked={reload} onChange={e => setReload(e.target.checked)} />
                <span style={{ color: 'rgb(var(--tx-primary))' }}>Run <code className="font-mono">pg_reload_conf()</code> after changes</span>
              </label>
            </div>

            {/* Confirm */}
            <div
              className="rounded-lg p-3 flex items-start gap-2"
              style={{
                background: 'rgba(245, 158, 11, 0.08)',
                border: '1px solid rgba(245, 158, 11, 0.35)',
              }}
            >
              <ExclamationTriangleIcon className="w-5 h-5 flex-shrink-0 mt-0.5" style={{ color: 'rgb(245, 158, 11)' }} />
              <label className="text-xs cursor-pointer flex-1" style={{ color: 'rgb(var(--tx-primary))' }}>
                <input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} className="mr-2" />
                I understand this will modify Postgres server settings.
                Changes are audited and the backend preserves the current values
                in the result log for easy rollback.
              </label>
            </div>

            {/* Action buttons */}
            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                onClick={onClose}
                className="px-3 py-1.5 text-xs font-semibold rounded-lg"
                style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
              >
                Cancel
              </button>
              <button
                onClick={() => applyMut.mutate(payload)}
                disabled={!canSubmit}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
                style={{
                  background: canSubmit ? 'rgb(var(--accent))' : 'rgb(var(--hover))',
                  color: canSubmit ? '#fff' : 'rgb(var(--tx-muted))',
                  cursor: canSubmit ? 'pointer' : 'not-allowed',
                }}
              >
                {applyMut.isPending ? (
                  <>
                    <ArrowPathIcon className="w-3.5 h-3.5 animate-spin" />
                    Applying…
                  </>
                ) : (
                  <>
                    <PlayCircleIcon className="w-3.5 h-3.5" />
                    Apply to {useOverride ? 'the override database' : 'platform database'}
                  </>
                )}
              </button>
            </div>
          </>
        )}

        {/* Result view */}
        {result && <ApplyResult result={result} onClose={onClose} />}
      </div>
    </Modal>
  )
}

function ApplyResult({ result, onClose }) {
  const s = result.summary
  const [filter, setFilter] = useState('all')
  const filtered = result.results.filter(r => {
    if (filter === 'all') return true
    if (filter === 'applied') return r.status === 'APPLIED'
    if (filter === 'restart') return r.status === 'RESTART_REQUIRED'
    if (filter === 'skipped') return r.status === 'SKIPPED'
    if (filter === 'failed') return r.status === 'FAILED'
    return true
  })
  return (
    <div className="space-y-3">
      <div
        className="rounded-xl p-4 grid grid-cols-5 gap-3 text-center"
        style={{
          background: 'rgb(var(--surface))',
          border: '1px solid rgb(var(--border))',
        }}
      >
        <StatPill label="Applied" count={s.applied} color="rgb(34,197,94)" active={filter === 'applied'} onClick={() => setFilter(filter === 'applied' ? 'all' : 'applied')} />
        <StatPill label="Skipped" count={s.skipped} color="rgb(148,163,184)" active={filter === 'skipped'} onClick={() => setFilter(filter === 'skipped' ? 'all' : 'skipped')} />
        <StatPill label="Restart required" count={s.restartRequired} color="rgb(100,140,255)" active={filter === 'restart'} onClick={() => setFilter(filter === 'restart' ? 'all' : 'restart')} />
        <StatPill label="Failed" count={s.failed} color="rgb(248,113,113)" active={filter === 'failed'} onClick={() => setFilter(filter === 'failed' ? 'all' : 'failed')} />
        <StatPill label="Total" count={s.totalConsidered} color="rgb(var(--tx-primary))" active={filter === 'all'} onClick={() => setFilter('all')} />
      </div>
      <div
        className="rounded-xl overflow-hidden"
        style={{ border: '1px solid rgb(var(--border))', maxHeight: 400, overflowY: 'auto' }}
      >
        <table className="w-full text-xs">
          <thead className="sticky top-0" style={{ background: 'rgb(var(--hover))' }}>
            <tr>
              <th className="text-left px-3 py-2 font-semibold">Setting</th>
              <th className="text-left px-3 py-2 font-semibold">Recommended</th>
              <th className="text-left px-3 py-2 font-semibold">Current (before)</th>
              <th className="text-left px-3 py-2 font-semibold">Status</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(r => {
              const statusStyle = {
                APPLIED:          { bg: 'rgba(34,197,94,0.12)',   fg: 'rgb(34,197,94)'  },
                SKIPPED:          { bg: 'rgba(148,163,184,0.12)', fg: 'rgb(148,163,184)' },
                RESTART_REQUIRED: { bg: 'rgba(100,140,255,0.12)', fg: 'rgb(100,140,255)' },
                FAILED:           { bg: 'rgba(248,113,113,0.12)', fg: 'rgb(248,113,113)' },
              }[r.status] || { bg: 'rgb(var(--hover))', fg: 'rgb(var(--tx-muted))' }
              return (
                <tr key={r.name} style={{ borderTop: '1px solid rgb(var(--border))' }}>
                  <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>{r.name}</td>
                  <td className="px-3 py-2 font-mono" style={{ color: 'rgb(34,197,94)' }}>{r.recommended}</td>
                  <td className="px-3 py-2 font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>{r.current || '—'}</td>
                  <td className="px-3 py-2">
                    <span className="inline-block px-2 py-0.5 rounded-full text-[10px] font-semibold"
                      style={{ background: statusStyle.bg, color: statusStyle.fg }}>
                      {r.status}
                    </span>
                    {r.message && (
                      <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>{r.message}</p>
                    )}
                  </td>
                </tr>
              )
            })}
            {filtered.length === 0 && (
              <tr><td colSpan={4} className="px-3 py-4 text-center text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>No rows match this filter.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="flex items-center justify-between text-[11px]" style={{ color: 'rgb(var(--tx-muted))' }}>
        <span>
          Reload: {s.reloaded ? '✓ pg_reload_conf() returned true' : '✗ not reloaded'}
          {s.reloadError && <span style={{ color: 'rgb(248,113,113)' }}> — {s.reloadError}</span>}
        </span>
        <span>Connection: {s.usedOverrideConnection ? 'override credentials' : 'platform database'}</span>
      </div>
      <div className="flex justify-end">
        <button
          onClick={onClose}
          className="px-3 py-1.5 text-xs font-semibold rounded-lg"
          style={{ background: 'rgb(var(--accent))', color: '#fff' }}
        >
          Done
        </button>
      </div>
    </div>
  )
}

function StatPill({ label, count, color, active, onClick }) {
  return (
    <button
      onClick={onClick}
      className="rounded-lg p-2 transition-all"
      style={{
        background: active ? `${color}22` : 'rgb(var(--canvas))',
        border: `1px solid ${active ? color : 'rgb(var(--border))'}`,
      }}
    >
      <p className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>{label}</p>
      <p className="text-xl font-bold" style={{ color }}>{count}</p>
    </button>
  )
}

// ── Main page ───────────────────────────────────────────────────────

export default function DatabaseAdvisory() {
  const [applyOpen, setApplyOpen] = useState(false)
  const qc = useQueryClient()
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['db-advisory'],
    queryFn: fetchAdvisory,
    staleTime: 30_000,
    meta: { errorMessage: "Couldn't load database advisory" },
  })
  const statusQuery = useQuery({
    queryKey: ['db-advisory-status'],
    queryFn: fetchStatus,
    refetchInterval: 60_000,
    meta: { errorMessage: "Couldn't load live compliance status" },
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
            onClick={() => setApplyOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{
              background: 'linear-gradient(135deg, rgb(var(--accent)), #8b5cf6)',
              color: '#fff',
              boxShadow: '0 2px 12px rgba(100, 140, 255, 0.3)',
            }}
          >
            <BoltIcon className="w-3.5 h-3.5" />
            Apply to database
          </button>
          <button
            onClick={handleCopyConf}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-primary))' }}
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

      {/* Live status strip — real-time compliance vs the current DB */}
      <StatusStrip
        status={statusQuery.data}
        isLoading={statusQuery.isLoading}
        onRefresh={() => qc.invalidateQueries({ queryKey: ['db-advisory-status'] })}
      />

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

      {/* Apply modal */}
      {applyOpen && (
        <ApplyModal
          onClose={() => setApplyOpen(false)}
          onComplete={() => qc.invalidateQueries({ queryKey: ['db-advisory-status'] })}
        />
      )}
    </div>
  )
}
