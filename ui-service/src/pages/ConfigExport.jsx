import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import {
  ArrowDownTrayIcon,
  ShieldCheckIcon,
  DocumentDuplicateIcon,
  ExclamationTriangleIcon,
  CheckIcon,
} from '@heroicons/react/24/outline'
import { getExportScope, buildBundle, downloadBundleAsJson } from '../api/configExport'
import ServiceUnavailable from '../components/ServiceUnavailable'
import Skeleton from '../components/Skeleton'
import CopyButton from '../components/CopyButton'

/**
 * ConfigExport — Phase 1 of the Configuration Export/Import feature.
 *
 * Read-only export side: operator picks which entity types to include,
 * clicks Build Bundle, backend walks the repos, returns a signed JSON
 * blob, browser triggers a download.
 *
 * Phase 1 scope: partners, accounts, flows, folder-mappings, server-instances.
 * All other entity types (security profiles, keys, policies, …) come in
 * later phases — see docs/plans/CONFIG-EXPORT-IMPORT.md for the full plan.
 *
 * Design principles (locked):
 *   • Stability       — single API call for scope, single call for export
 *   • Transparency    — every entity type shows live count + status after build
 *   • Resilience      — full error state via ServiceUnavailable if backend down
 *   • Minimalism      — one card, one button, nothing clever
 *   • Guidance        — explains what's included + what's redacted + what comes next
 */

const ENTITY_LABELS = {
  'partners':          { label: 'Partners',           hint: 'Trading partners + onboarding phases' },
  'accounts':          { label: 'Transfer Accounts',  hint: 'SFTP / FTP / HTTPS account config (password hashes redacted)' },
  'flows':             { label: 'Processing Flows',   hint: 'Full flow definitions including step configs' },
  'folder-mappings':   { label: 'Folder Mappings',    hint: 'Source → destination routing rules' },
  'server-instances':  { label: 'Server Instances',   hint: 'SFTP / FTP / HTTPS server configurations' },
}

export default function ConfigExport() {
  const [selected, setSelected] = useState(new Set(Object.keys(ENTITY_LABELS)))
  const [lastBundle, setLastBundle] = useState(null)

  const {
    data: scope,
    isLoading: scopeLoading,
    isError: scopeError,
    error: scopeErr,
    refetch: refetchScope,
  } = useQuery({
    queryKey: ['config-export-scope'],
    queryFn: getExportScope,
    retry: 0,
  })

  const buildMutation = useMutation({
    mutationFn: () => buildBundle(Array.from(selected)),
    onSuccess: (bundle) => {
      setLastBundle(bundle)
      downloadBundleAsJson(bundle)
      toast.success('Bundle built and downloaded')
    },
    onError: (e) => {
      toast.error('Build failed: ' + (e?.response?.data?.error || e?.message || 'unknown'))
    },
  })

  const toggle = (key) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const selectAll = () => setSelected(new Set(Object.keys(ENTITY_LABELS)))
  const selectNone = () => setSelected(new Set())

  if (scopeError) {
    return (
      <ServiceUnavailable
        service="onboarding-api"
        port={8080}
        error={scopeErr}
        onRetry={refetchScope}
        title="Configuration Export unavailable"
        hint="Couldn't reach the onboarding-api /api/v1/config-export endpoint. Is the service running?"
      />
    )
  }

  const counts = scope || {}
  const selectedCount = Array.from(selected).reduce((n, k) => n + (counts[k] || 0), 0)
  const hasSelection = selected.size > 0

  return (
    <div className="space-y-6">
      {/* ── Header ───────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary flex items-center gap-2">
            <ArrowDownTrayIcon className="w-6 h-6 text-blue-500" />
            Configuration Export
          </h1>
          <p className="text-secondary text-sm mt-1">
            Export platform configuration as a portable JSON bundle for promotion to another environment.
          </p>
        </div>
      </div>

      {/* ── Help card ────────────────────────────────────────── */}
      <div
        className="rounded-xl p-4 text-sm"
        style={{ background: 'rgba(59, 130, 246, 0.06)', border: '1px solid rgba(59, 130, 246, 0.25)' }}
      >
        <div className="flex gap-3">
          <ShieldCheckIcon className="w-5 h-5 flex-shrink-0 text-blue-500" />
          <div className="space-y-1">
            <p className="font-semibold text-primary">What's included</p>
            <p className="text-secondary text-xs">
              Configuration data only — no runtime state, no audit logs, no transfer history. Secrets
              (password hashes, private keys, webhook tokens) are redacted to placeholders. The bundle
              is checksummed with SHA-256 and includes a schema version for upgrade safety.
            </p>
            <p className="text-secondary text-xs">
              Full feature plan (import side, merge/replace modes, CLI support) is in{' '}
              <span className="font-mono text-primary">docs/plans/CONFIG-EXPORT-IMPORT.md</span>.
            </p>
          </div>
        </div>
      </div>

      {/* ── Scope picker ─────────────────────────────────────── */}
      <div className="card">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-primary">Scope</h2>
          <div className="flex items-center gap-2 text-xs">
            <button
              onClick={selectAll}
              className="px-2 py-1 rounded transition-colors hover:bg-surface-hover"
              style={{ color: 'rgb(100, 140, 255)' }}
            >
              Select all
            </button>
            <span className="text-muted">·</span>
            <button
              onClick={selectNone}
              className="px-2 py-1 rounded transition-colors hover:bg-surface-hover"
              style={{ color: 'rgb(148, 163, 184)' }}
            >
              Select none
            </button>
          </div>
        </div>

        {scopeLoading ? (
          <Skeleton.Table rows={5} cols={[24, 200, 80]} rowHeight={48} />
        ) : (
          <div className="space-y-2">
            {Object.entries(ENTITY_LABELS).map(([key, def]) => {
              const count = counts[key] ?? 0
              const isSelected = selected.has(key)
              return (
                <label
                  key={key}
                  className="flex items-center gap-3 p-3 rounded-lg cursor-pointer transition-colors hover:bg-surface-hover"
                  style={{ border: '1px solid rgb(30, 30, 36)' }}
                >
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => toggle(key)}
                    className="w-4 h-4 rounded"
                    style={{ accentColor: 'rgb(var(--accent, 79 70 229))' }}
                  />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-primary">{def.label}</span>
                      <span
                        className="text-[10px] font-mono px-1.5 py-0.5 rounded"
                        style={{ background: 'rgb(30, 30, 36)', color: 'rgb(148, 163, 184)' }}
                      >
                        {count.toLocaleString()}
                      </span>
                    </div>
                    <p className="text-xs text-secondary mt-0.5">{def.hint}</p>
                  </div>
                </label>
              )
            })}
          </div>
        )}

        <div className="flex items-center justify-between mt-4 pt-4" style={{ borderTop: '1px solid rgb(30, 30, 36)' }}>
          <div className="text-xs text-secondary">
            <span className="text-primary font-semibold">{selected.size}</span> type{selected.size === 1 ? '' : 's'} ·{' '}
            <span className="text-primary font-semibold">{selectedCount.toLocaleString()}</span> records
          </div>
          <button
            onClick={() => buildMutation.mutate()}
            disabled={!hasSelection || buildMutation.isPending}
            className="flex items-center gap-1.5 px-4 py-2 text-sm font-semibold rounded-lg transition-colors"
            style={{
              background: hasSelection && !buildMutation.isPending ? 'rgb(var(--accent, 79 70 229))' : 'rgb(30, 30, 36)',
              color: hasSelection && !buildMutation.isPending ? '#fff' : 'rgb(148, 163, 184)',
              cursor: hasSelection && !buildMutation.isPending ? 'pointer' : 'not-allowed',
            }}
          >
            <ArrowDownTrayIcon className="w-4 h-4" />
            {buildMutation.isPending ? 'Building bundle…' : 'Build & download'}
          </button>
        </div>
      </div>

      {/* ── Last bundle card ─────────────────────────────────── */}
      {lastBundle && (
        <div className="card">
          <h2 className="text-lg font-semibold text-primary mb-3 flex items-center gap-2">
            <CheckIcon className="w-5 h-5 text-green-500" />
            Last bundle
          </h2>
          <div className="grid grid-cols-2 gap-3 text-xs">
            <div>
              <div className="uppercase font-bold tracking-wide text-[10px] text-muted">Schema</div>
              <div className="font-mono text-primary mt-0.5">{lastBundle.schemaVersion}</div>
            </div>
            <div>
              <div className="uppercase font-bold tracking-wide text-[10px] text-muted">Environment</div>
              <div className="font-mono text-primary mt-0.5">{lastBundle.sourceEnvironment || '—'}</div>
            </div>
            <div>
              <div className="uppercase font-bold tracking-wide text-[10px] text-muted">Cluster</div>
              <div className="font-mono text-primary mt-0.5">{lastBundle.sourceCluster || '—'}</div>
            </div>
            <div>
              <div className="uppercase font-bold tracking-wide text-[10px] text-muted">Exported at</div>
              <div className="font-mono text-primary mt-0.5">{lastBundle.exportedAt || '—'}</div>
            </div>
            <div className="col-span-2">
              <div className="uppercase font-bold tracking-wide text-[10px] text-muted">SHA-256 checksum</div>
              <div className="flex items-center gap-2 mt-0.5">
                <div className="font-mono text-primary text-[10px] truncate flex-1">
                  {lastBundle.checksum}
                </div>
                <CopyButton value={lastBundle.checksum} label="checksum" size="xs" />
              </div>
            </div>
          </div>

          {lastBundle.redactions && lastBundle.redactions.length > 0 && (
            <div
              className="mt-4 p-3 rounded-lg flex items-start gap-2"
              style={{ background: 'rgba(234, 179, 8, 0.08)', border: '1px solid rgba(234, 179, 8, 0.25)' }}
            >
              <ExclamationTriangleIcon className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: 'rgb(250, 204, 21)' }} />
              <div className="text-xs">
                <div className="font-semibold text-primary">
                  {lastBundle.redactions.length} secret field{lastBundle.redactions.length === 1 ? '' : 's'} redacted
                </div>
                <p className="text-secondary mt-0.5">
                  These fields were stripped on export. The target environment's operator must supply them on import
                  (password hashes, private keys, webhook tokens, etc.).
                </p>
              </div>
            </div>
          )}

          <button
            onClick={() => downloadBundleAsJson(lastBundle)}
            className="flex items-center gap-1.5 mt-4 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors hover:bg-surface-hover"
            style={{ border: '1px solid rgb(48, 48, 56)', color: 'rgb(var(--tx-primary))' }}
          >
            <DocumentDuplicateIcon className="w-3.5 h-3.5" />
            Re-download bundle
          </button>
        </div>
      )}
    </div>
  )
}
