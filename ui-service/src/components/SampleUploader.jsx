import { useState, useCallback, useRef } from 'react'
import toast from 'react-hot-toast'
import {
  CloudArrowUpIcon, DocumentTextIcon, TrashIcon,
  CheckCircleIcon, ExclamationTriangleIcon, ArrowPathIcon,
  XMarkIcon, ArrowsRightLeftIcon,
} from '@heroicons/react/24/outline'
import { buildMapFromSamples } from '../api/ediConverter'

// ── Drop Zone ────────────────────────────────────────────────────────────────

function DropZone({ label, files, onDrop, onRemove }) {
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef(null)

  const handleDragOver = useCallback((e) => {
    e.preventDefault()
    e.stopPropagation()
    setDragging(true)
  }, [])

  const handleDragLeave = useCallback((e) => {
    e.preventDefault()
    e.stopPropagation()
    setDragging(false)
  }, [])

  const handleDrop = useCallback((e) => {
    e.preventDefault()
    e.stopPropagation()
    setDragging(false)
    const dropped = Array.from(e.dataTransfer.files)
    if (dropped.length > 0) onDrop(dropped)
  }, [onDrop])

  const handleFileSelect = useCallback((e) => {
    const selected = Array.from(e.target.files)
    if (selected.length > 0) onDrop(selected)
    e.target.value = ''
  }, [onDrop])

  return (
    <div className="flex-1 min-w-0 flex flex-col gap-3">
      <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
        {label}
      </h4>

      {/* Drop area */}
      <div
        className="rounded-xl border-2 border-dashed p-6 text-center cursor-pointer transition-all"
        style={{
          borderColor: dragging ? 'rgb(var(--accent))' : 'rgb(var(--border))',
          background: dragging ? 'rgb(var(--accent) / 0.06)' : 'transparent',
        }}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleFileSelect}
          accept=".edi,.x12,.txt,.json,.xml,.csv,.hl7,.dat,.flat,.yaml,.yml"
        />
        <CloudArrowUpIcon className="w-8 h-8 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
        <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
          Drop files here or <span style={{ color: 'rgb(var(--accent))' }}>browse</span>
        </p>
        <p className="text-[10px] mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
          EDI, JSON, XML, CSV, HL7, SWIFT, flat files
        </p>
      </div>

      {/* File list */}
      {files.length > 0 && (
        <div className="space-y-1.5">
          {files.map((f, i) => (
            <div
              key={i}
              className="flex items-center gap-2 px-3 py-2 rounded-lg group transition-colors"
              style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))' }}
            >
              <DocumentTextIcon className="w-4 h-4 flex-shrink-0" style={{ color: 'rgb(var(--accent))' }} />
              <div className="flex-1 min-w-0">
                <p className="text-xs font-medium truncate" style={{ color: 'rgb(var(--tx-primary))' }}>
                  {f.name}
                </p>
                <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {f.content ? `${f.content.length.toLocaleString()} chars` : 'Reading...'}
                </p>
              </div>
              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded"
                style={{ background: 'rgb(var(--accent) / 0.1)', color: 'rgb(var(--accent))' }}>
                #{i + 1}
              </span>
              <button
                onClick={(e) => { e.stopPropagation(); onRemove(i) }}
                className="p-1 rounded opacity-0 group-hover:opacity-100 transition-opacity"
                style={{ color: '#f87171' }}
              >
                <TrashIcon className="w-3.5 h-3.5" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ── Pairing Visualization ────────────────────────────────────────────────────

function PairingView({ sourceFiles, targetFiles }) {
  const pairCount = Math.min(sourceFiles.length, targetFiles.length)
  if (pairCount === 0) return null

  return (
    <div className="space-y-1.5">
      <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
        Paired Samples ({pairCount})
      </h4>
      {Array.from({ length: pairCount }).map((_, i) => (
        <div key={i} className="flex items-center gap-2 text-xs px-3 py-2 rounded-lg"
          style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))' }}>
          <span className="font-mono truncate flex-1" style={{ color: '#fbbf24' }}>
            {sourceFiles[i].name}
          </span>
          <ArrowsRightLeftIcon className="w-4 h-4 flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }} />
          <span className="font-mono truncate flex-1 text-right" style={{ color: '#4ade80' }}>
            {targetFiles[i].name}
          </span>
        </div>
      ))}
    </div>
  )
}

// ── Confidence Badge ─────────────────────────────────────────────────────────

function ConfidenceBadge({ confidence }) {
  const pct = typeof confidence === 'number' ? confidence : 0
  const color = pct >= 80 ? '#4ade80' : pct >= 50 ? '#fbbf24' : '#f87171'
  const bg = pct >= 80 ? '#14532d22' : pct >= 50 ? '#78350f22' : '#7f1d1d22'
  const label = pct >= 80 ? 'High' : pct >= 50 ? 'Medium' : 'Low'

  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-2 rounded-full overflow-hidden" style={{ background: 'rgb(var(--border))' }}>
        <div className="h-full rounded-full transition-all duration-700" style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className="text-xs font-bold px-2 py-0.5 rounded" style={{ background: bg, color }}>
        {pct}% {label}
      </span>
    </div>
  )
}

// ── Low Confidence Warnings ──────────────────────────────────────────────────

function LowConfidenceWarnings({ mappings }) {
  const low = (mappings || []).filter(m => (m.confidence ?? 100) < 70)
  if (low.length === 0) return null

  return (
    <div className="rounded-lg p-3 space-y-2" style={{ background: '#78350f12', border: '1px solid #78350f30' }}>
      <div className="flex items-center gap-2">
        <ExclamationTriangleIcon className="w-4 h-4 flex-shrink-0" style={{ color: '#fbbf24' }} />
        <span className="text-xs font-semibold" style={{ color: '#fbbf24' }}>
          {low.length} field{low.length !== 1 ? 's' : ''} with low confidence
        </span>
      </div>
      <div className="space-y-1">
        {low.slice(0, 8).map((m, i) => (
          <div key={i} className="flex items-center gap-2 text-[11px]">
            <span className="font-mono" style={{ color: 'rgb(var(--tx-secondary))' }}>
              {m.sourcePath || m.source}
            </span>
            <span style={{ color: 'rgb(var(--tx-muted))' }}>&rarr;</span>
            <span className="font-mono" style={{ color: 'rgb(var(--tx-secondary))' }}>
              {m.targetPath || m.target}
            </span>
            <span className="ml-auto text-[10px] font-bold" style={{ color: '#f87171' }}>
              {m.confidence ?? 0}%
            </span>
          </div>
        ))}
        {low.length > 8 && (
          <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
            ... and {low.length - 8} more
          </p>
        )}
      </div>
    </div>
  )
}

// ── Progress Ring ────────────────────────────────────────────────────────────

function ProgressRing({ text }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 gap-4">
      <div className="relative w-16 h-16">
        <svg className="w-16 h-16 animate-spin" viewBox="0 0 64 64">
          <circle
            cx="32" cy="32" r="28"
            fill="none"
            stroke="rgb(var(--border))"
            strokeWidth="4"
          />
          <circle
            cx="32" cy="32" r="28"
            fill="none"
            stroke="rgb(var(--accent))"
            strokeWidth="4"
            strokeLinecap="round"
            strokeDasharray="120"
            strokeDashoffset="80"
          />
        </svg>
      </div>
      <p className="text-sm font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>{text}</p>
    </div>
  )
}

// ── Main Component ───────────────────────────────────────────────────────────

export default function SampleUploader({ onBuildComplete, partnerId }) {
  const [sourceFiles, setSourceFiles] = useState([])
  const [targetFiles, setTargetFiles] = useState([])
  const [mapName, setMapName] = useState('')
  const [building, setBuilding] = useState(false)
  const [buildResult, setBuildResult] = useState(null)

  // Read file contents as text
  const readFiles = useCallback(async (files) => {
    const results = []
    for (const file of files) {
      const content = await new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result)
        reader.onerror = () => reject(new Error(`Failed to read ${file.name}`))
        reader.readAsText(file)
      })
      results.push({ name: file.name, content, size: file.size })
    }
    return results
  }, [])

  const handleSourceDrop = useCallback(async (files) => {
    try {
      const read = await readFiles(files)
      setSourceFiles(prev => [...prev, ...read])
    } catch (e) {
      toast.error(e.message)
    }
  }, [readFiles])

  const handleTargetDrop = useCallback(async (files) => {
    try {
      const read = await readFiles(files)
      setTargetFiles(prev => [...prev, ...read])
    } catch (e) {
      toast.error(e.message)
    }
  }, [readFiles])

  const removeSource = useCallback((idx) => {
    setSourceFiles(prev => prev.filter((_, i) => i !== idx))
  }, [])

  const removeTarget = useCallback((idx) => {
    setTargetFiles(prev => prev.filter((_, i) => i !== idx))
  }, [])

  // Build map from samples
  const handleBuild = async () => {
    const pairCount = Math.min(sourceFiles.length, targetFiles.length)
    if (pairCount < 2) {
      toast.error('Upload at least 2 source-target pairs')
      return
    }

    const samples = Array.from({ length: pairCount }).map((_, i) => ({
      sourceContent: sourceFiles[i].content,
      sourceFileName: sourceFiles[i].name,
      targetContent: targetFiles[i].content,
      targetFileName: targetFiles[i].name,
    }))

    setBuilding(true)
    setBuildResult(null)

    try {
      const result = await buildMapFromSamples(samples, partnerId || undefined, mapName || undefined)
      setBuildResult(result)
      toast.success(`Map built: ${result.fieldCount || result.mappings?.length || 0} fields mapped`)
    } catch (e) {
      toast.error('Build failed: ' + (e.response?.data?.message || e.message))
    } finally {
      setBuilding(false)
    }
  }

  // Approve and hand off to parent
  const handleApprove = () => {
    if (buildResult) {
      onBuildComplete?.(buildResult)
    }
  }

  // Reset and try again
  const handleReset = () => {
    setBuildResult(null)
  }

  const pairCount = Math.min(sourceFiles.length, targetFiles.length)
  const canBuild = pairCount >= 2 && !building

  // ── Result View ────────────────────────────────────────────────────────────

  if (buildResult) {
    const mappings = buildResult.fieldMappings || buildResult.mappings || []
    const fieldCount = buildResult.fieldCount || mappings.length
    const confidence = buildResult.confidence ?? buildResult.overallConfidence ?? 0

    return (
      <div className="space-y-5">
        {/* Success header */}
        <div className="flex items-start gap-3 p-4 rounded-xl"
          style={{ background: '#14532d12', border: '1px solid #14532d30' }}>
          <CheckCircleIcon className="w-6 h-6 flex-shrink-0 mt-0.5" style={{ color: '#4ade80' }} />
          <div className="flex-1">
            <h3 className="text-sm font-bold" style={{ color: '#4ade80' }}>
              Map Built Successfully
            </h3>
            <p className="text-xs mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>
              {fieldCount} field{fieldCount !== 1 ? 's' : ''} mapped from {pairCount} sample pair{pairCount !== 1 ? 's' : ''}
            </p>
          </div>
        </div>

        {/* Confidence */}
        <div className="space-y-2">
          <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Overall Confidence
          </h4>
          <ConfidenceBadge confidence={confidence} />
        </div>

        {/* Stats row */}
        <div className="grid grid-cols-4 gap-3">
          {[
            ['Fields', fieldCount],
            ['Loops', buildResult.loopMappings?.length || 0],
            ['Code Tables', buildResult.codeTables ? Object.keys(buildResult.codeTables).length : 0],
            ['Samples', pairCount],
          ].map(([label, val]) => (
            <div key={label} className="rounded-lg p-3 text-center"
              style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))' }}>
              <div className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>{val}</div>
              <div className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>{label}</div>
            </div>
          ))}
        </div>

        {/* Low confidence warnings */}
        <LowConfidenceWarnings mappings={mappings} />

        {/* Actions */}
        <div className="flex items-center gap-3 pt-2">
          <button onClick={handleApprove} className="btn-primary flex-1 py-2.5 text-sm font-semibold">
            Approve & Continue
          </button>
          <button onClick={handleReset}
            className="flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-lg text-sm font-medium transition-colors"
            style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-secondary))', border: '1px solid rgb(var(--border))' }}>
            <ArrowPathIcon className="w-4 h-4" />
            Refine
          </button>
        </div>
      </div>
    )
  }

  // ── Upload View ────────────────────────────────────────────────────────────

  if (building) {
    return <ProgressRing text="Analyzing samples..." />
  }

  return (
    <div className="space-y-5">
      {/* Map name input */}
      <div>
        <label className="text-xs font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>
          Map Name (optional)
        </label>
        <input
          value={mapName}
          onChange={e => setMapName(e.target.value)}
          className="w-full mt-1 text-sm rounded-lg px-3 py-2"
          style={{
            background: 'rgb(var(--canvas))',
            color: 'rgb(var(--tx-primary))',
            border: '1px solid rgb(var(--border))',
          }}
          placeholder="e.g. ACME 850 Purchase Order"
        />
      </div>

      {/* Two-column drop zones */}
      <div className="flex gap-6">
        <DropZone
          label="Source Files"
          files={sourceFiles}
          onDrop={handleSourceDrop}
          onRemove={removeSource}
        />
        <div className="w-px flex-shrink-0" style={{ background: 'rgb(var(--border))' }} />
        <DropZone
          label="Target Files"
          files={targetFiles}
          onDrop={handleTargetDrop}
          onRemove={removeTarget}
        />
      </div>

      {/* Pairing view */}
      <PairingView sourceFiles={sourceFiles} targetFiles={targetFiles} />

      {/* Status / hint */}
      {pairCount < 2 && (sourceFiles.length > 0 || targetFiles.length > 0) && (
        <div className="flex items-center gap-2 text-xs px-3 py-2 rounded-lg"
          style={{ background: '#78350f12', border: '1px solid #78350f30', color: '#fbbf24' }}>
          <ExclamationTriangleIcon className="w-4 h-4 flex-shrink-0" />
          Upload at least 2 source-target pairs. Currently: {sourceFiles.length} source, {targetFiles.length} target.
        </div>
      )}

      {/* Build button */}
      <div className="flex items-center justify-between pt-2">
        <p className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
          Files are paired by order: source1 &harr; target1, source2 &harr; target2, etc.
        </p>
        <button
          onClick={handleBuild}
          disabled={!canBuild}
          className="btn-primary px-6 py-2.5 text-sm font-semibold flex items-center gap-2"
          style={{ opacity: canBuild ? 1 : 0.4 }}
        >
          <ArrowsRightLeftIcon className="w-4 h-4" />
          Build Map
        </button>
      </div>
    </div>
  )
}
