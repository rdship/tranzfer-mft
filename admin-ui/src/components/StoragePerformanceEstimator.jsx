import { useState, useMemo } from 'react'

// ─── Performance Model ──────────────────────────────────────────────────────
// Deterministic predictions based on scale-test measurements (April 2026) and
// known PostgreSQL/ext4 characteristics.  Safe to predict because TranzFer
// controls the full storage stack — these hold in any environment.

const VFS = {
  listBaseMs: 0.8,         // Indexed DB query base
  listPerEntryMs: 0.005,   // Per-row transfer
  writeBaseMs: 4.5,        // DB INSERT + CAS store base
  writePerMbMs: 7.0,       // CAS throughput per MB
  provisionMs: 1.5,        // Per-account (5 default dirs, batched)
  metaBytes: 590,           // DB row + B-tree index per entry
  dedupSavings: 0.30,      // Typical CAS dedup for MFT workloads
  maxListOps: 2000,        // DB pool ceiling
  maxWriteOps: 500,        // DB + CAS ceiling
  decay: 0.92,             // Per-decade concurrency efficiency
}

const DIRECT = {
  listBaseMs: 2.0,         // OS readdir base
  listPerEntryMs: 0.05,    // Per-entry stat()
  degradeAt: 5000,         // Entries before degradation
  degradeRate: 0.0002,     // Degradation factor above threshold
  writeBaseMs: 3.0,        // OS write base
  writePerMbMs: 6.5,       // Disk throughput per MB
  provisionMs: 15.0,       // mkdir + chmod per account
  blockSize: 4096,         // ext4/xfs minimum block
  inodeBytes: 256,         // Per-inode metadata
  maxListOps: 500,         // I/O ceiling
  maxWriteOps: 200,        // Write contention ceiling
  decay: 0.60,             // Per-decade concurrency efficiency (FS degrades)
}

function predict(fileCount, avgBytes, users) {
  const mb = avgBytes / (1024 * 1024)
  const perUser = Math.max(1, Math.round(fileCount / Math.max(1, users)))
  const decades = Math.log10(Math.max(10, users))

  const vList = VFS.listBaseMs + perUser * VFS.listPerEntryMs
  const vWrite = VFS.writeBaseMs + mb * VFS.writePerMbMs
  const vProv = users * VFS.provisionMs / 1000
  const vStore = fileCount * VFS.metaBytes + fileCount * avgBytes * (1 - VFS.dedupSavings)
  const vWriteOps = Math.round(VFS.maxWriteOps * Math.pow(VFS.decay, decades))
  const vListOps = Math.round(VFS.maxListOps * Math.pow(VFS.decay, decades))

  const deg = perUser > DIRECT.degradeAt ? 1 + (perUser - DIRECT.degradeAt) * DIRECT.degradeRate : 1
  const dList = (DIRECT.listBaseMs + perUser * DIRECT.listPerEntryMs) * deg
  const dWrite = DIRECT.writeBaseMs + mb * DIRECT.writePerMbMs
  const dProv = users * DIRECT.provisionMs / 1000
  const dStore = fileCount * (avgBytes + DIRECT.blockSize + DIRECT.inodeBytes)
  const dWriteOps = Math.round(DIRECT.maxWriteOps * Math.pow(DIRECT.decay, decades))
  const dListOps = Math.round(DIRECT.maxListOps * Math.pow(DIRECT.decay, decades))

  return {
    vfs:    { listMs: vList, writeMs: vWrite, provSec: vProv, store: vStore, wOps: vWriteOps, lOps: vListOps },
    direct: { listMs: dList, writeMs: dWrite, provSec: dProv, store: dStore, wOps: dWriteOps, lOps: dListOps },
  }
}

// ─── Formatters ─────────────────────────────────────────────────────────────

function fmtMs(ms) {
  if (ms < 0.01) return '<0.01ms'
  if (ms < 1) return `${(ms * 1000).toFixed(0)}μs`
  if (ms < 1000) return `${ms.toFixed(1)}ms`
  return fmtSec(ms / 1000)
}
function fmtSec(s) {
  if (s < 0.001) return '<1ms'
  if (s < 1) return `${(s * 1000).toFixed(0)}ms`
  if (s < 60) return `${s.toFixed(1)}s`
  if (s < 3600) return `${(s / 60).toFixed(1)} min`
  return `${(s / 3600).toFixed(1)} hr`
}
function fmtBytes(b) {
  if (b < 1024) return `${b} B`
  if (b < 1048576) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1073741824) return `${(b / 1048576).toFixed(1)} MB`
  if (b < 1099511627776) return `${(b / 1073741824).toFixed(1)} GB`
  return `${(b / 1099511627776).toFixed(1)} TB`
}
function fmtCount(n) {
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`
  if (n >= 1e3) return `${(n / 1e3).toFixed(1)}K`
  return String(n)
}
function pctDiff(a, b) {
  if (b === 0) return 0
  return Math.round(((a - b) / b) * 100)
}

// ─── Log slider helpers ─────────────────────────────────────────────────────

function toLog(min, max, pos) {
  return Math.round(Math.pow(10, Math.log10(min) + (pos / 100) * (Math.log10(max) - Math.log10(min))))
}
function toPos(min, max, val) {
  return Math.round(((Math.log10(val) - Math.log10(min)) / (Math.log10(max) - Math.log10(min))) * 100)
}

// ─── Component ──────────────────────────────────────────────────────────────

function MetricRow({ label, vfs, direct, unit, lower }) {
  const vWin = lower ? vfs < direct : vfs > direct
  const dWin = !vWin
  const diff = lower
    ? pctDiff(direct, vfs)   // how much slower direct is
    : pctDiff(vfs, direct)   // how much faster vfs is
  return (
    <tr className="border-t border-gray-100">
      <td className="py-2.5 pr-4 text-sm text-gray-600 font-medium">{label}</td>
      <td className={`py-2.5 px-4 text-sm font-mono text-right ${vWin ? 'text-emerald-700 font-semibold' : 'text-gray-500'}`}>
        {unit === 'time' ? fmtMs(vfs) : unit === 'timeSec' ? fmtSec(vfs) : unit === 'bytes' ? fmtBytes(vfs) : `${fmtCount(vfs)} ops/s`}
      </td>
      <td className={`py-2.5 px-4 text-sm font-mono text-right ${dWin ? 'text-emerald-700 font-semibold' : 'text-gray-500'}`}>
        {unit === 'time' ? fmtMs(direct) : unit === 'timeSec' ? fmtSec(direct) : unit === 'bytes' ? fmtBytes(direct) : `${fmtCount(direct)} ops/s`}
      </td>
      <td className="py-2.5 pl-4 text-xs text-right">
        {diff > 0 && <span className="text-emerald-600">VFS {diff}% {lower ? 'faster' : 'higher'}</span>}
        {diff < 0 && <span className="text-amber-600">Direct {Math.abs(diff)}% {lower ? 'faster' : 'higher'}</span>}
        {diff === 0 && <span className="text-gray-400">equal</span>}
      </td>
    </tr>
  )
}

export default function StoragePerformanceEstimator() {
  const [fp, setFp] = useState(64)   // ~100K files
  const [sp, setSp] = useState(50)   // ~1MB
  const [up, setUp] = useState(85)   // ~24K users

  const fileCount = toLog(100, 5_000_000, fp)
  const avgSize = toLog(1024, 1_073_741_824, sp)
  const users = toLog(10, 100_000, up)
  const [open, setOpen] = useState(false)

  const p = useMemo(() => predict(fileCount, avgSize, users), [fileCount, avgSize, users])

  const vfsWins = [
    p.vfs.listMs <= p.direct.listMs,
    p.vfs.provSec <= p.direct.provSec,
    p.vfs.wOps >= p.direct.wOps,
    p.vfs.lOps >= p.direct.lOps,
    p.vfs.store <= p.direct.store,
  ].filter(Boolean).length

  const recommendation = vfsWins >= 4
    ? { text: 'Virtual File System recommended', color: 'emerald', detail: 'VFS dominates on provisioning, throughput, and storage at this scale.' }
    : vfsWins <= 1
    ? { text: 'Direct File System may suffice', color: 'blue', detail: 'For low user counts with few files, Direct FS avoids DB overhead.' }
    : { text: 'Both viable — evaluate per use case', color: 'amber', detail: 'VFS scales better; Direct FS has lower single-op latency for large files.' }

  return (
    <div className="card">
      <button onClick={() => setOpen(!open)} className="w-full flex items-center justify-between text-left">
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-indigo-500" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 0 1 3 19.875v-6.75ZM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V8.625ZM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V4.125Z" />
          </svg>
          <span className="font-semibold text-gray-900">Storage Performance Estimator</span>
          <span className="text-xs text-gray-400">VFS vs Direct</span>
        </div>
        <svg className={`w-4 h-4 text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="m19.5 8.25-7.5 7.5-7.5-7.5" />
        </svg>
      </button>

      {open && (
        <div className="mt-5 space-y-5">
          {/* Sliders */}
          <div className="grid grid-cols-3 gap-6">
            <div>
              <label className="text-xs font-medium text-gray-500 uppercase tracking-wider">Total Files</label>
              <input type="range" min={0} max={100} value={fp} onChange={e => setFp(+e.target.value)} className="w-full mt-1 accent-indigo-500" />
              <div className="text-lg font-semibold text-gray-900 font-mono">{fmtCount(fileCount)}</div>
            </div>
            <div>
              <label className="text-xs font-medium text-gray-500 uppercase tracking-wider">Avg File Size</label>
              <input type="range" min={0} max={100} value={sp} onChange={e => setSp(+e.target.value)} className="w-full mt-1 accent-indigo-500" />
              <div className="text-lg font-semibold text-gray-900 font-mono">{fmtBytes(avgSize)}</div>
            </div>
            <div>
              <label className="text-xs font-medium text-gray-500 uppercase tracking-wider">Concurrent Users</label>
              <input type="range" min={0} max={100} value={up} onChange={e => setUp(+e.target.value)} className="w-full mt-1 accent-indigo-500" />
              <div className="text-lg font-semibold text-gray-900 font-mono">{fmtCount(users)}</div>
            </div>
          </div>

          {/* Comparison table */}
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="text-xs uppercase tracking-wider text-gray-400">
                  <th className="text-left pr-4 pb-2 font-medium">Metric</th>
                  <th className="text-right px-4 pb-2 font-medium">
                    <span className="inline-flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full bg-indigo-400" /> VFS (Phantom Folder)
                    </span>
                  </th>
                  <th className="text-right px-4 pb-2 font-medium">
                    <span className="inline-flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full bg-gray-400" /> Direct File System
                    </span>
                  </th>
                  <th className="text-right pl-4 pb-2 font-medium">Delta</th>
                </tr>
              </thead>
              <tbody>
                <MetricRow label={`Provision ${fmtCount(users)} accounts`} vfs={p.vfs.provSec} direct={p.direct.provSec} unit="timeSec" lower />
                <MetricRow label="Directory listing (per op)" vfs={p.vfs.listMs} direct={p.direct.listMs} unit="time" lower />
                <MetricRow label="File write (per file)" vfs={p.vfs.writeMs} direct={p.direct.writeMs} unit="time" lower />
                <MetricRow label="Concurrent write throughput" vfs={p.vfs.wOps} direct={p.direct.wOps} unit="ops" lower={false} />
                <MetricRow label="Concurrent listing throughput" vfs={p.vfs.lOps} direct={p.direct.lOps} unit="ops" lower={false} />
                <MetricRow label="Total storage required" vfs={p.vfs.store} direct={p.direct.store} unit="bytes" lower />
              </tbody>
            </table>
          </div>

          {/* Dedup note */}
          <div className="flex items-start gap-2 text-xs text-gray-500 bg-gray-50 rounded px-3 py-2">
            <span className="font-semibold text-indigo-600 shrink-0">VFS dedup:</span>
            <span>Content-addressed storage (SHA-256) deduplicates identical files across all {fmtCount(users)} accounts.
              Estimated {VFS.dedupSavings * 100}% storage savings ({fmtBytes(fileCount * avgSize * VFS.dedupSavings)} saved).</span>
          </div>

          {/* Recommendation */}
          <div className={`flex items-center gap-3 rounded-lg px-4 py-3 border ${
            recommendation.color === 'emerald' ? 'bg-emerald-50 border-emerald-200' :
            recommendation.color === 'blue' ? 'bg-blue-50 border-blue-200' :
            'bg-amber-50 border-amber-200'
          }`}>
            <div className={`w-2 h-2 rounded-full shrink-0 ${
              recommendation.color === 'emerald' ? 'bg-emerald-500' :
              recommendation.color === 'blue' ? 'bg-blue-500' : 'bg-amber-500'
            }`} />
            <div>
              <span className={`text-sm font-semibold ${
                recommendation.color === 'emerald' ? 'text-emerald-800' :
                recommendation.color === 'blue' ? 'text-blue-800' : 'text-amber-800'
              }`}>{recommendation.text}</span>
              <span className="text-xs text-gray-500 ml-2">{recommendation.detail}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
