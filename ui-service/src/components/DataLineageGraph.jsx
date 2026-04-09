import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import {
  ChevronDownIcon, ChevronRightIcon, DocumentIcon,
} from '@heroicons/react/24/outline'

// ── Constants ──────────────────────────────────────────────────────────────────

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
  RENAME:          'Rename File',
  EXECUTE_SCRIPT:  'Execute Script',
  APPROVE:         'Admin Approval',
}

const STEP_EMOJI = {
  COMPRESS_GZIP: '🗜️', DECOMPRESS_GZIP: '📂', COMPRESS_ZIP: '🗜️', DECOMPRESS_ZIP: '📂',
  ENCRYPT_PGP: '🔐',   DECRYPT_PGP: '🔓',     ENCRYPT_AES: '🔐',  DECRYPT_AES: '🔓',
  SCREEN: '🛡️',        MAILBOX: '📬',          FILE_DELIVERY: '🚀', ROUTE: '➡️',
  CONVERT_EDI: '🔄',   RENAME: '✏️',           EXECUTE_SCRIPT: '⚡', APPROVE: '✋',
}

const STATUS_CFG = {
  OK:      { color: '#22c55e', label: 'OK' },
  FAILED:  { color: '#ef4444', label: 'FAILED' },
  SKIPPED: { color: '#f59e0b', label: 'SKIPPED' },
}
const statusFor = (s) => {
  if (!s) return STATUS_CFG.OK
  if (s === 'FAILED') return STATUS_CFG.FAILED
  if (s === 'SKIPPED') return STATUS_CFG.SKIPPED
  return STATUS_CFG.OK
}

// ── Helpers ────────────────────────────────────────────────────────────────────

function fmtBytes(b) {
  if (!b && b !== 0) return null
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(2)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function fmtMs(ms) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function shortHash(hash) {
  if (!hash) return '—'
  return hash.substring(0, 8) + '…' + hash.substring(hash.length - 4)
}

function sizeDelta(inBytes, outBytes) {
  if (!inBytes || !outBytes || inBytes === outBytes) return null
  const delta = ((outBytes - inBytes) / inBytes) * 100
  const smaller = outBytes < inBytes
  return { pct: Math.abs(delta).toFixed(1), smaller }
}

/**
 * Build a flat sequence of alternating FileState and StepTransform nodes
 * from the ordered FlowStepSnapshot list.
 */
function buildChain(snapshots) {
  if (!snapshots?.length) return []

  const chain = []

  // Initial file state (from first snapshot's input)
  const first = snapshots[0]
  chain.push({
    kind: 'file',
    label: 'Source File',
    sha256: first.inputStorageKey,
    size: first.inputSizeBytes,
    path: first.inputVirtualPath,
    isFirst: true,
  })

  for (const snap of snapshots) {
    const sc = statusFor(snap.stepStatus)
    const delta = sizeDelta(snap.inputSizeBytes, snap.outputSizeBytes)

    chain.push({
      kind: 'step',
      stepType: snap.stepType,
      label: STEP_LABELS[snap.stepType] || snap.stepType,
      emoji: STEP_EMOJI[snap.stepType] || '⚙️',
      duration: snap.durationMs,
      status: snap.stepStatus,
      statusCfg: sc,
      delta,
      error: snap.errorMessage,
    })

    // Output file state (skip if step failed with no output)
    const outputKey = snap.outputStorageKey
    if (outputKey) {
      const isLast = snap === snapshots[snapshots.length - 1]
      const unchanged = outputKey === snap.inputStorageKey
      chain.push({
        kind: 'file',
        label: isLast ? 'Final Output' : `After ${STEP_LABELS[snap.stepType] || snap.stepType}`,
        sha256: outputKey,
        size: snap.outputSizeBytes,
        path: snap.outputVirtualPath,
        unchanged,
        isLast,
      })
    }
  }

  return chain
}

// ── Node Components ────────────────────────────────────────────────────────────

function FileStateNode({ node }) {
  const accentColor = node.isFirst ? '#60a5fa' : node.isLast ? '#22c55e' : '#a78bfa'

  return (
    <div
      className="relative rounded-xl px-4 py-3 mx-auto"
      style={{
        background: 'rgb(var(--surface))',
        border: `1.5px solid ${accentColor}55`,
        boxShadow: node.isFirst || node.isLast ? `0 0 16px ${accentColor}18` : 'none',
        maxWidth: 520,
        width: '100%',
      }}
    >
      <div className="flex items-center gap-3">
        <div
          className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
          style={{ background: `${accentColor}18` }}
        >
          <DocumentIcon className="w-4 h-4" style={{ color: accentColor }} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {node.label}
            </p>
            {node.unchanged && (
              <span className="text-[9px] px-1.5 py-0.5 rounded-full font-medium"
                style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-muted))' }}>
                unchanged
              </span>
            )}
          </div>
          <div className="flex items-center gap-3 mt-0.5 flex-wrap">
            <p
              className="text-[11px] font-mono"
              style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono', monospace" }}
            >
              {shortHash(node.sha256)}
            </p>
            {node.size != null && (
              <p className="text-[11px] font-mono" style={{ color: accentColor, fontFamily: "'JetBrains Mono', monospace" }}>
                {fmtBytes(node.size)}
              </p>
            )}
            {node.path && (
              <p className="text-[10px] truncate max-w-48" style={{ color: 'rgb(var(--tx-muted))' }}>
                {node.path}
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function StepTransformNode({ node }) {
  const { color } = node.statusCfg
  const hasError = node.status === 'FAILED'

  return (
    <div className="relative flex flex-col items-center">
      {/* Connector line top */}
      <div className="w-px flex-shrink-0" style={{ height: 20, background: `${color}50` }} />

      {/* Arrow tip */}
      <div style={{
        width: 0, height: 0,
        borderLeft: '5px solid transparent',
        borderRight: '5px solid transparent',
        borderTop: `7px solid ${color}80`,
        marginBottom: 4,
      }} />

      {/* Step box */}
      <div
        className="rounded-lg px-4 py-2.5 flex items-center gap-3"
        style={{
          background: `${color}10`,
          border: `1px solid ${color}40`,
          minWidth: 200,
          maxWidth: 320,
        }}
      >
        <span className="text-lg flex-shrink-0">{node.emoji}</span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <p className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {node.label}
            </p>
            <span
              className="text-[9px] font-bold px-1.5 py-0.5 rounded-full"
              style={{ background: `${color}20`, color }}
            >
              {node.statusCfg.label}
            </span>
          </div>
          <div className="flex items-center gap-3 mt-0.5 flex-wrap">
            {node.duration != null && (
              <span className="text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono', monospace" }}>
                {fmtMs(node.duration)}
              </span>
            )}
            {node.delta && (
              <span
                className="text-[10px] font-semibold font-mono"
                style={{ color: node.delta.smaller ? '#22c55e' : '#ef4444', fontFamily: "'JetBrains Mono', monospace" }}
              >
                {node.delta.smaller ? '↓' : '↑'} {node.delta.pct}%
              </span>
            )}
          </div>
          {hasError && node.error && (
            <p className="text-[10px] mt-1 leading-snug" style={{ color: '#ef4444' }}>
              {node.error}
            </p>
          )}
        </div>
      </div>

      {/* Connector line bottom */}
      <div className="w-px" style={{ height: 20, background: `${color}50` }} />
    </div>
  )
}

// ── Main Component ─────────────────────────────────────────────────────────────

export default function DataLineageGraph({ trackId }) {
  const [expanded, setExpanded] = useState(true)

  const { data: snapshots = [], isLoading } = useQuery({
    queryKey: ['flow-steps', trackId],
    queryFn: () => onboardingApi.get(`/api/flow-steps/${trackId}`).then(r => r.data),
    enabled: !!trackId,
    staleTime: 30_000,
  })

  if (isLoading || !snapshots.length) return null

  const chain = buildChain(snapshots)
  const totalMs = snapshots.reduce((s, snap) => s + (snap.durationMs || 0), 0)
  const firstSize = snapshots[0]?.inputSizeBytes
  const lastSize  = snapshots[snapshots.length - 1]?.outputSizeBytes
  const overallDelta = sizeDelta(firstSize, lastSize)

  return (
    <div className="card">
      {/* Header */}
      <button
        className="flex items-center justify-between w-full"
        onClick={() => setExpanded(v => !v)}
      >
        <h3 className="font-semibold flex items-center gap-2" style={{ color: 'rgb(var(--tx-primary))' }}>
          <span>🔗</span>
          Data Lineage Graph
          <span
            className="text-xs font-normal px-2 py-0.5 rounded-full"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-muted))' }}
          >
            {snapshots.length} transformation{snapshots.length !== 1 ? 's' : ''}
          </span>
          {overallDelta && (
            <span
              className="text-xs font-mono"
              style={{
                color: overallDelta.smaller ? '#22c55e' : '#ef4444',
                fontFamily: "'JetBrains Mono', monospace",
              }}
            >
              {fmtBytes(firstSize)} → {fmtBytes(lastSize)} ({overallDelta.smaller ? '↓' : '↑'}{overallDelta.pct}%)
            </span>
          )}
        </h3>
        {expanded
          ? <ChevronDownIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
          : <ChevronRightIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
        }
      </button>

      {expanded && (
        <div className="mt-4">
          {/* Summary bar */}
          <div
            className="flex items-center gap-4 px-3 py-2 rounded-lg mb-5 flex-wrap"
            style={{ background: 'rgb(var(--hover))' }}
          >
            <div className="flex items-center gap-1.5 text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
              <span style={{ color: 'rgb(var(--tx-muted))' }}>Steps:</span>
              <span className="font-semibold font-mono" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                {snapshots.length}
              </span>
            </div>
            <div className="flex items-center gap-1.5 text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
              <span style={{ color: 'rgb(var(--tx-muted))' }}>Total time:</span>
              <span className="font-semibold font-mono" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                {fmtMs(totalMs)}
              </span>
            </div>
            {firstSize != null && (
              <div className="flex items-center gap-1.5 text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                <span style={{ color: 'rgb(var(--tx-muted))' }}>Input:</span>
                <span className="font-semibold font-mono" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
                  {fmtBytes(firstSize)}
                </span>
              </div>
            )}
            {lastSize != null && (
              <div className="flex items-center gap-1.5 text-xs" style={{ color: 'rgb(var(--tx-secondary))' }}>
                <span style={{ color: 'rgb(var(--tx-muted))' }}>Output:</span>
                <span
                  className="font-semibold font-mono"
                  style={{
                    color: overallDelta ? (overallDelta.smaller ? '#22c55e' : '#ef4444') : 'rgb(var(--tx-primary))',
                    fontFamily: "'JetBrains Mono', monospace",
                  }}
                >
                  {fmtBytes(lastSize)}
                </span>
              </div>
            )}
          </div>

          {/* The lineage chain — centered vertical flow */}
          <div className="flex flex-col items-center">
            {chain.map((node, i) =>
              node.kind === 'file'
                ? <FileStateNode key={i} node={node} />
                : <StepTransformNode key={i} node={node} />
            )}
          </div>

          {/* Legend */}
          <div
            className="flex items-center gap-4 mt-5 px-3 py-2 rounded-lg text-[10px] flex-wrap"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-muted))' }}
          >
            <span className="font-semibold uppercase tracking-wider">Legend:</span>
            {[['#60a5fa', 'Source file'], ['#a78bfa', 'Intermediate'], ['#22c55e', 'Final output'],
              ['#22c55e', '↓% size reduced'], ['#ef4444', '↑% size increased']].map(([color, label]) => (
              <div key={label} className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-full" style={{ background: color }} />
                <span>{label}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
