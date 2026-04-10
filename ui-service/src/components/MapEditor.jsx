import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import {
  XMarkIcon, PlusIcon, ChevronRightIcon, ChevronDownIcon,
  TrashIcon, ArrowsRightLeftIcon, TableCellsIcon,
} from '@heroicons/react/24/outline'

// ── Constants ─────────────────────────────────────────────────────────────────

const TRANSFORMS = [
  'COPY', 'TRIM', 'PAD', 'DATE_FORMAT', 'LOOKUP', 'SUBSTRING',
  'CONCAT', 'UPPERCASE', 'LOWERCASE', 'SPLIT', 'DEFAULT', 'REGEX',
]

const CONFIDENCE_COLORS = {
  high:   { bg: '#14532d22', text: '#4ade80', label: 'High' },
  medium: { bg: '#78350f22', text: '#fbbf24', label: 'Medium' },
  low:    { bg: '#7f1d1d22', text: '#f87171', label: 'Low' },
}

function confidenceLevel(c) {
  if (c == null) return CONFIDENCE_COLORS.high
  if (c >= 0.8) return CONFIDENCE_COLORS.high
  if (c >= 0.5) return CONFIDENCE_COLORS.medium
  return CONFIDENCE_COLORS.low
}

// ── Path tree helpers ─────────────────────────────────────────────────────────

/** Build a tree structure from flat dotted paths like "BEG.01", "N1.entityId" */
function buildTree(paths) {
  const root = { children: {}, label: '', path: '', isLeaf: false }
  for (const p of paths) {
    const parts = p.split('.')
    let node = root
    for (let i = 0; i < parts.length; i++) {
      const key = parts[i]
      if (!node.children[key]) {
        node.children[key] = {
          children: {},
          label: key,
          path: parts.slice(0, i + 1).join('.'),
          isLeaf: false,
        }
      }
      node = node.children[key]
    }
    node.isLeaf = true
    node.fullPath = p
  }
  return root
}

function flattenTree(node, depth = 0) {
  const entries = []
  for (const child of Object.values(node.children).sort((a, b) => a.label.localeCompare(b.label))) {
    const hasChildren = Object.keys(child.children).length > 0
    entries.push({ ...child, depth, hasChildren })
    if (hasChildren) {
      entries.push(...flattenTree(child, depth + 1))
    }
  }
  return entries
}

// ── TreePanel ─────────────────────────────────────────────────────────────────

function TreePanel({ title, paths, side, mappedPaths, onFieldClick, selectedField, readOnly, fieldRefs, loopPaths }) {
  const [collapsed, setCollapsed] = useState({})
  const tree = useMemo(() => buildTree(paths), [paths])
  const flat = useMemo(() => flattenTree(tree), [tree])

  const toggleCollapse = (path) => {
    setCollapsed(prev => ({ ...prev, [path]: !prev[path] }))
  }

  // Filter out collapsed children
  const visible = useMemo(() => {
    const result = []
    const hiddenPrefixes = new Set()
    for (const entry of flat) {
      // Check if any ancestor is collapsed
      let hidden = false
      for (const prefix of hiddenPrefixes) {
        if (entry.path.startsWith(prefix + '.')) { hidden = true; break }
      }
      if (hidden) continue
      result.push(entry)
      if (entry.hasChildren && collapsed[entry.path]) {
        hiddenPrefixes.add(entry.path)
      }
    }
    return result
  }, [flat, collapsed])

  const isMapped = useCallback((path) => mappedPaths.has(path), [mappedPaths])
  const isLoop = useCallback((path) => loopPaths?.has(path), [loopPaths])

  return (
    <div className="flex-1 min-w-0 flex flex-col">
      <div className="px-3 py-2 border-b" style={{ borderColor: 'rgb(var(--border))' }}>
        <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          {title}
        </h4>
      </div>
      <div className="flex-1 overflow-y-auto py-1" style={{ maxHeight: 380 }}>
        {visible.map(entry => {
          const mapped = isMapped(entry.fullPath || entry.path)
          const loop = isLoop(entry.path)
          const isSelected = selectedField?.side === side && selectedField?.path === (entry.fullPath || entry.path)

          return (
            <div
              key={entry.path}
              ref={el => {
                if (fieldRefs?.current && (entry.fullPath || entry.isLeaf)) {
                  fieldRefs.current[`${side}:${entry.fullPath || entry.path}`] = el
                }
              }}
              className="flex items-center gap-1.5 px-2 py-1 cursor-pointer transition-colors group"
              style={{
                paddingLeft: 8 + entry.depth * 16,
                background: isSelected
                  ? 'rgb(var(--accent) / 0.15)'
                  : loop
                    ? 'rgb(var(--accent) / 0.05)'
                    : 'transparent',
              }}
              onClick={() => {
                if (entry.hasChildren) toggleCollapse(entry.path)
                if (entry.isLeaf || entry.fullPath) {
                  onFieldClick?.({ side, path: entry.fullPath || entry.path })
                }
              }}
            >
              {/* Expand/collapse toggle */}
              {entry.hasChildren ? (
                <button
                  className="w-4 h-4 flex items-center justify-center flex-shrink-0"
                  onClick={e => { e.stopPropagation(); toggleCollapse(entry.path) }}
                >
                  {collapsed[entry.path]
                    ? <ChevronRightIcon className="w-3 h-3" style={{ color: 'rgb(var(--tx-muted))' }} />
                    : <ChevronDownIcon className="w-3 h-3" style={{ color: 'rgb(var(--tx-muted))' }} />}
                </button>
              ) : (
                <span className="w-4 flex-shrink-0" />
              )}

              {/* Loop indicator */}
              {loop && (
                <span className="text-[9px] font-bold px-1 py-0.5 rounded"
                  style={{ background: '#4c1d9522', color: '#c084fc' }}>
                  LOOP
                </span>
              )}

              {/* Field label */}
              <span
                className="text-xs font-mono truncate flex-1"
                style={{
                  color: mapped
                    ? 'rgb(var(--accent))'
                    : isSelected
                      ? 'rgb(var(--tx-primary))'
                      : 'rgb(var(--tx-secondary))',
                  fontFamily: "'JetBrains Mono', monospace",
                  fontWeight: entry.hasChildren ? 600 : 400,
                }}
              >
                {entry.hasChildren && !entry.isLeaf ? entry.label : entry.label}
                {entry.hasChildren && !entry.isLeaf && ` (${Object.keys(entry.children).length})`}
              </span>

              {/* Mapped dot */}
              {mapped && (
                <div className="w-2 h-2 rounded-full flex-shrink-0"
                  style={{ background: 'rgb(var(--accent))' }} />
              )}

              {/* Edit hint in non-readOnly */}
              {!readOnly && !mapped && entry.isLeaf && (
                <PlusIcon className="w-3 h-3 opacity-0 group-hover:opacity-60 flex-shrink-0 transition-opacity"
                  style={{ color: 'rgb(var(--tx-muted))' }} />
              )}
            </div>
          )
        })}

        {visible.length === 0 && (
          <div className="px-3 py-6 text-center text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
            No fields available
          </div>
        )}
      </div>
    </div>
  )
}

// ── SVG Connection Lines ──────────────────────────────────────────────────────

function ConnectionLines({ mappings, fieldRefs, containerRef, selectedMapping, onSelectMapping }) {
  const [lines, setLines] = useState([])

  const recalc = useCallback(() => {
    if (!containerRef.current || !fieldRefs.current) return
    const containerRect = containerRef.current.getBoundingClientRect()
    const newLines = []

    for (let i = 0; i < mappings.length; i++) {
      const m = mappings[i]
      const srcEl = fieldRefs.current[`source:${m.sourcePath || m.source}`]
      const tgtEl = fieldRefs.current[`target:${m.targetPath || m.target}`]
      if (!srcEl || !tgtEl) continue

      const srcRect = srcEl.getBoundingClientRect()
      const tgtRect = tgtEl.getBoundingClientRect()

      newLines.push({
        index: i,
        x1: srcRect.right - containerRect.left,
        y1: srcRect.top + srcRect.height / 2 - containerRect.top,
        x2: tgtRect.left - containerRect.left,
        y2: tgtRect.top + tgtRect.height / 2 - containerRect.top,
        isSelected: selectedMapping === i,
      })
    }
    setLines(newLines)
  }, [mappings, selectedMapping, containerRef, fieldRefs])

  useEffect(() => {
    recalc()
    const interval = setInterval(recalc, 300)
    return () => clearInterval(interval)
  }, [recalc])

  // Also recalc on scroll within trees
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const scrollables = container.querySelectorAll('[class*="overflow-y"]')
    const handler = () => recalc()
    scrollables.forEach(el => el.addEventListener('scroll', handler, { passive: true }))
    return () => scrollables.forEach(el => el.removeEventListener('scroll', handler))
  }, [containerRef, recalc])

  return (
    <svg
      className="absolute inset-0 pointer-events-none"
      style={{ width: '100%', height: '100%', zIndex: 5 }}
    >
      {lines.map(line => {
        const midX = (line.x1 + line.x2) / 2
        const isSelected = line.isSelected
        return (
          <g key={line.index}>
            <path
              d={`M ${line.x1} ${line.y1} C ${midX} ${line.y1}, ${midX} ${line.y2}, ${line.x2} ${line.y2}`}
              fill="none"
              stroke={isSelected ? 'rgb(100 140 255)' : 'rgb(100 140 255 / 0.35)'}
              strokeWidth={isSelected ? 2.5 : 1.5}
              strokeDasharray={isSelected ? 'none' : 'none'}
              style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
              onClick={() => onSelectMapping?.(line.index)}
            />
            {/* Arrow at target end */}
            <circle
              cx={line.x2 - 2}
              cy={line.y2}
              r={isSelected ? 3.5 : 2.5}
              fill={isSelected ? 'rgb(100 140 255)' : 'rgb(100 140 255 / 0.5)'}
              style={{ pointerEvents: 'auto', cursor: 'pointer' }}
              onClick={() => onSelectMapping?.(line.index)}
            />
          </g>
        )
      })}
    </svg>
  )
}

// ── MappingDetail Panel ───────────────────────────────────────────────────────

function MappingDetail({ mapping, index, readOnly, onChange, onRemove }) {
  if (!mapping) return null

  const source = mapping.sourcePath || mapping.source || ''
  const target = mapping.targetPath || mapping.target || ''
  const transform = mapping.transform || mapping.transformation || 'COPY'
  const defaultVal = mapping.defaultValue || ''
  const condition = mapping.condition || ''
  const required = mapping.required ?? false
  const confidence = mapping.confidence ?? 1.0
  const transformConfig = mapping.transformConfig || {}

  const update = (field, value) => {
    if (readOnly) return
    onChange?.(index, { ...mapping, [field]: value })
  }

  return (
    <div className="px-4 py-3 space-y-3" style={{ borderTop: '1px solid rgb(var(--border))' }}>
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          Mapping Detail
        </h4>
        {!readOnly && (
          <button
            onClick={() => onRemove?.(index)}
            className="flex items-center gap-1 text-xs px-2 py-1 rounded transition-colors"
            style={{ color: '#f87171', background: '#7f1d1d22' }}
          >
            <TrashIcon className="w-3.5 h-3.5" /> Remove
          </button>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Source
          </label>
          <div className="text-xs font-mono mt-0.5 px-2 py-1.5 rounded"
            style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono', monospace" }}>
            {source}
          </div>
        </div>
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Target
          </label>
          <div className="text-xs font-mono mt-0.5 px-2 py-1.5 rounded"
            style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono', monospace" }}>
            {target}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-3">
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Transform
          </label>
          {readOnly ? (
            <div className="text-xs mt-0.5 px-2 py-1.5 rounded"
              style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))' }}>
              {transform}
            </div>
          ) : (
            <select
              value={transform}
              onChange={e => update('transform', e.target.value)}
              className="w-full text-xs mt-0.5 rounded px-2 py-1.5"
              style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
            >
              {TRANSFORMS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          )}
        </div>
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Default
          </label>
          <input
            value={defaultVal}
            onChange={e => update('defaultValue', e.target.value)}
            readOnly={readOnly}
            className="w-full text-xs mt-0.5 rounded px-2 py-1.5"
            style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
            placeholder="—"
          />
        </div>
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Condition
          </label>
          <input
            value={condition}
            onChange={e => update('condition', e.target.value)}
            readOnly={readOnly}
            className="w-full text-xs mt-0.5 rounded px-2 py-1.5"
            style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
            placeholder="—"
          />
        </div>
        <div className="flex gap-3">
          <div className="flex-1">
            <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
              Required
            </label>
            <div className="mt-1">
              <button
                onClick={() => !readOnly && update('required', !required)}
                className="w-6 h-6 rounded flex items-center justify-center text-xs font-bold transition-colors"
                style={{
                  background: required ? 'rgb(var(--accent) / 0.2)' : 'rgb(var(--canvas))',
                  color: required ? 'rgb(var(--accent))' : 'rgb(var(--tx-muted))',
                  border: `1px solid ${required ? 'rgb(var(--accent) / 0.4)' : 'rgb(var(--border))'}`,
                }}
              >
                {required ? '\u2713' : '\u2717'}
              </button>
            </div>
          </div>
          <div className="flex-1">
            <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
              Conf
            </label>
            <div className="mt-0.5 text-xs font-mono px-2 py-1.5 rounded"
              style={{
                background: confidenceLevel(confidence).bg,
                color: confidenceLevel(confidence).text,
                fontFamily: "'JetBrains Mono', monospace",
              }}>
              {typeof confidence === 'number' ? confidence.toFixed(2) : confidence}
            </div>
          </div>
        </div>
      </div>

      {/* Transform config (show for DATE_FORMAT, SUBSTRING, PAD, REGEX, CONCAT) */}
      {['DATE_FORMAT', 'SUBSTRING', 'PAD', 'REGEX', 'CONCAT'].includes(transform) && (
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Transform Config
          </label>
          {readOnly ? (
            <pre className="text-xs font-mono mt-0.5 px-2 py-1.5 rounded overflow-auto"
              style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono', monospace", maxHeight: 80 }}>
              {JSON.stringify(transformConfig, null, 2)}
            </pre>
          ) : (
            <textarea
              value={JSON.stringify(transformConfig, null, 2)}
              onChange={e => {
                try { update('transformConfig', JSON.parse(e.target.value)) } catch {}
              }}
              rows={2}
              className="w-full text-xs font-mono mt-0.5 rounded px-2 py-1.5"
              style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))', fontFamily: "'JetBrains Mono', monospace" }}
            />
          )}
        </div>
      )}
    </div>
  )
}

// ── Code Table Editor ─────────────────────────────────────────────────────────

function CodeTableEditor({ mapping, codeTables, readOnly, onUpdateCodeTables }) {
  const transform = mapping?.transform || mapping?.transformation || ''
  if (transform !== 'LOOKUP') return null

  const tableName = mapping?.transformConfig?.tableName || mapping?.lookupTable || ''
  const table = codeTables?.[tableName] || {}
  const entries = Object.entries(table)

  const [newKey, setNewKey] = useState('')
  const [newVal, setNewVal] = useState('')

  const updateTable = (updatedTable) => {
    onUpdateCodeTables?.({ ...codeTables, [tableName]: updatedTable })
  }

  const addEntry = () => {
    if (!newKey.trim()) return
    updateTable({ ...table, [newKey.trim()]: newVal.trim() })
    setNewKey('')
    setNewVal('')
  }

  const removeEntry = (key) => {
    const updated = { ...table }
    delete updated[key]
    updateTable(updated)
  }

  return (
    <div className="px-4 py-3 space-y-2" style={{ borderTop: '1px solid rgb(var(--border))' }}>
      <div className="flex items-center gap-2">
        <TableCellsIcon className="w-4 h-4" style={{ color: 'rgb(var(--tx-muted))' }} />
        <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          Code Table: {tableName || 'unnamed'}
        </h4>
      </div>

      <div className="grid grid-cols-[1fr_auto_1fr_auto] gap-1 items-center text-xs">
        {/* Header */}
        <div className="font-bold px-2 py-1" style={{ color: 'rgb(var(--tx-muted))' }}>Source Code</div>
        <div />
        <div className="font-bold px-2 py-1" style={{ color: 'rgb(var(--tx-muted))' }}>Target Value</div>
        <div />

        {entries.map(([key, val]) => (
          <div key={key} className="contents">
            <div className="font-mono px-2 py-1 rounded"
              style={{ background: 'rgb(var(--canvas))', color: '#fbbf24', fontFamily: "'JetBrains Mono', monospace" }}>
              {key}
            </div>
            <span className="text-center" style={{ color: 'rgb(var(--tx-muted))' }}>&rarr;</span>
            <div className="font-mono px-2 py-1 rounded"
              style={{ background: 'rgb(var(--canvas))', color: '#4ade80', fontFamily: "'JetBrains Mono', monospace" }}>
              {val}
            </div>
            {!readOnly ? (
              <button onClick={() => removeEntry(key)} className="p-1 rounded hover:bg-red-900/20 transition-colors">
                <XMarkIcon className="w-3.5 h-3.5" style={{ color: '#f87171' }} />
              </button>
            ) : <div />}
          </div>
        ))}

        {/* Add new row */}
        {!readOnly && (
          <div className="contents">
            <input
              value={newKey}
              onChange={e => setNewKey(e.target.value)}
              className="text-xs font-mono px-2 py-1 rounded"
              style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))', fontFamily: "'JetBrains Mono', monospace" }}
              placeholder="Code"
              onKeyDown={e => e.key === 'Enter' && addEntry()}
            />
            <span className="text-center" style={{ color: 'rgb(var(--tx-muted))' }}>&rarr;</span>
            <input
              value={newVal}
              onChange={e => setNewVal(e.target.value)}
              className="text-xs font-mono px-2 py-1 rounded"
              style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))', fontFamily: "'JetBrains Mono', monospace" }}
              placeholder="Value"
              onKeyDown={e => e.key === 'Enter' && addEntry()}
            />
            <button onClick={addEntry}
              className="p-1 rounded transition-colors"
              style={{ background: 'rgb(var(--accent) / 0.15)', color: 'rgb(var(--accent))' }}>
              <PlusIcon className="w-3.5 h-3.5" />
            </button>
          </div>
        )}
      </div>

      {entries.length === 0 && (
        <div className="text-xs text-center py-2" style={{ color: 'rgb(var(--tx-muted))' }}>
          No entries in this code table
        </div>
      )}
    </div>
  )
}

// ── Main MapEditor Component ──────────────────────────────────────────────────

export default function MapEditor({ mapDefinition, onSave, readOnly = false }) {
  const [mappings, setMappings] = useState([])
  const [loopMappings, setLoopMappings] = useState([])
  const [codeTables, setCodeTables] = useState({})
  const [selectedMapping, setSelectedMapping] = useState(null)
  const [pendingSource, setPendingSource] = useState(null)
  const [dirty, setDirty] = useState(false)

  const containerRef = useRef(null)
  const fieldRefs = useRef({})

  // Initialize from mapDefinition
  useEffect(() => {
    if (!mapDefinition) return
    setMappings(mapDefinition.fieldMappings || mapDefinition.mappings || [])
    setLoopMappings(mapDefinition.loopMappings || [])
    setCodeTables(mapDefinition.codeTables || {})
    setSelectedMapping(null)
    setPendingSource(null)
    setDirty(false)
  }, [mapDefinition])

  // Derive source and target paths
  const sourcePaths = useMemo(() => {
    const paths = new Set()
    for (const m of mappings) paths.add(m.sourcePath || m.source)
    for (const lm of loopMappings) {
      if (lm.sourceLoop) paths.add(lm.sourceLoop)
      for (const fm of (lm.fieldMappings || [])) paths.add(fm.sourcePath || fm.source)
    }
    // Also include unmapped source fields from the definition
    if (mapDefinition?.sourceFields) {
      for (const f of mapDefinition.sourceFields) paths.add(f)
    }
    return [...paths].filter(Boolean).sort()
  }, [mappings, loopMappings, mapDefinition])

  const targetPaths = useMemo(() => {
    const paths = new Set()
    for (const m of mappings) paths.add(m.targetPath || m.target)
    for (const lm of loopMappings) {
      if (lm.targetLoop) paths.add(lm.targetLoop)
      for (const fm of (lm.fieldMappings || [])) paths.add(fm.targetPath || fm.target)
    }
    if (mapDefinition?.targetFields) {
      for (const f of mapDefinition.targetFields) paths.add(f)
    }
    return [...paths].filter(Boolean).sort()
  }, [mappings, loopMappings, mapDefinition])

  // Sets of mapped paths for highlighting
  const mappedSourcePaths = useMemo(() => {
    const set = new Set()
    for (const m of mappings) set.add(m.sourcePath || m.source)
    for (const lm of loopMappings) {
      for (const fm of (lm.fieldMappings || [])) set.add(fm.sourcePath || fm.source)
    }
    return set
  }, [mappings, loopMappings])

  const mappedTargetPaths = useMemo(() => {
    const set = new Set()
    for (const m of mappings) set.add(m.targetPath || m.target)
    for (const lm of loopMappings) {
      for (const fm of (lm.fieldMappings || [])) set.add(fm.targetPath || fm.target)
    }
    return set
  }, [mappings, loopMappings])

  // Loop source paths for styling
  const loopSourcePaths = useMemo(() => {
    const set = new Set()
    for (const lm of loopMappings) {
      if (lm.sourceLoop) set.add(lm.sourceLoop)
    }
    return set
  }, [loopMappings])

  const loopTargetPaths = useMemo(() => {
    const set = new Set()
    for (const lm of loopMappings) {
      if (lm.targetLoop) set.add(lm.targetLoop)
    }
    return set
  }, [loopMappings])

  // Handle field click — create new mapping
  const handleFieldClick = useCallback((field) => {
    if (readOnly) {
      // In read-only, just highlight the mapping that involves this field
      const idx = mappings.findIndex(m =>
        (m.sourcePath || m.source) === field.path || (m.targetPath || m.target) === field.path
      )
      if (idx >= 0) setSelectedMapping(idx)
      return
    }

    if (field.side === 'source') {
      setPendingSource(field.path)
    } else if (field.side === 'target' && pendingSource) {
      // Create new mapping
      const newMapping = {
        sourcePath: pendingSource,
        targetPath: field.path,
        transform: 'COPY',
        confidence: 1.0,
        required: false,
        defaultValue: '',
        condition: '',
        transformConfig: {},
      }
      setMappings(prev => [...prev, newMapping])
      setSelectedMapping(mappings.length)
      setPendingSource(null)
      setDirty(true)
    }
  }, [readOnly, pendingSource, mappings])

  // Update a mapping
  const handleMappingChange = useCallback((index, updated) => {
    setMappings(prev => prev.map((m, i) => i === index ? updated : m))
    setDirty(true)
  }, [])

  // Remove a mapping
  const handleMappingRemove = useCallback((index) => {
    setMappings(prev => prev.filter((_, i) => i !== index))
    setSelectedMapping(null)
    setDirty(true)
  }, [])

  // Save handler
  const handleSave = () => {
    if (readOnly || !onSave) return
    const updated = {
      ...mapDefinition,
      fieldMappings: mappings,
      loopMappings,
      codeTables,
    }
    onSave(updated)
    setDirty(false)
  }

  // Stats
  const totalMappings = mappings.length
  const loopCount = loopMappings.length
  const codeTableCount = Object.keys(codeTables).length
  const avgConfidence = totalMappings > 0
    ? (mappings.reduce((s, m) => s + (m.confidence ?? 1), 0) / totalMappings).toFixed(2)
    : '—'

  return (
    <div className="flex flex-col h-full" style={{ minHeight: 500 }}>
      {/* Toolbar */}
      <div
        className="flex items-center justify-between px-4 py-2.5 flex-shrink-0"
        style={{ background: 'rgb(var(--surface))', borderBottom: '1px solid rgb(var(--border))' }}
      >
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <ArrowsRightLeftIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
            <span className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>
              {mapDefinition?.name || 'Map Editor'}
            </span>
          </div>
          {readOnly && (
            <span className="text-[10px] font-bold uppercase px-2 py-0.5 rounded"
              style={{ background: '#78350f22', color: '#fbbf24' }}>
              Read Only
            </span>
          )}
          {pendingSource && !readOnly && (
            <span className="text-xs px-2 py-1 rounded animate-pulse"
              style={{ background: 'rgb(var(--accent) / 0.15)', color: 'rgb(var(--accent))' }}>
              Select target for: {pendingSource}
            </span>
          )}
        </div>

        <div className="flex items-center gap-3">
          {/* Stats */}
          <div className="flex items-center gap-3 text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
            <span>{totalMappings} mappings</span>
            <span>{loopCount} loops</span>
            <span>{codeTableCount} tables</span>
            <span>avg conf: {avgConfidence}</span>
          </div>

          {pendingSource && !readOnly && (
            <button
              onClick={() => setPendingSource(null)}
              className="text-xs px-2 py-1 rounded transition-colors"
              style={{ background: '#7f1d1d22', color: '#f87171' }}
            >
              Cancel
            </button>
          )}

          {!readOnly && (
            <button
              onClick={handleSave}
              disabled={!dirty}
              className="btn-primary text-xs px-4 py-1.5"
              style={{ opacity: dirty ? 1 : 0.4 }}
            >
              Save Map
            </button>
          )}
        </div>
      </div>

      {/* Main visual area */}
      <div className="flex-1 flex flex-col overflow-hidden">
        <div ref={containerRef} className="flex-1 flex relative" style={{ minHeight: 300 }}>
          {/* Source tree */}
          <TreePanel
            title={`Source Schema${mapDefinition?.sourceType ? ` (${mapDefinition.sourceType})` : ''}`}
            paths={sourcePaths}
            side="source"
            mappedPaths={mappedSourcePaths}
            onFieldClick={handleFieldClick}
            selectedField={pendingSource ? { side: 'source', path: pendingSource } : null}
            readOnly={readOnly}
            fieldRefs={fieldRefs}
            loopPaths={loopSourcePaths}
          />

          {/* Center divider with SVG */}
          <div className="w-px flex-shrink-0" style={{ background: 'rgb(var(--border))' }} />

          {/* Target tree */}
          <TreePanel
            title={`Target Schema${mapDefinition?.targetType ? ` (${mapDefinition.targetType})` : ''}`}
            paths={targetPaths}
            side="target"
            mappedPaths={mappedTargetPaths}
            onFieldClick={handleFieldClick}
            selectedField={null}
            readOnly={readOnly}
            fieldRefs={fieldRefs}
            loopPaths={loopTargetPaths}
          />

          {/* SVG overlay */}
          <ConnectionLines
            mappings={mappings}
            fieldRefs={fieldRefs}
            containerRef={containerRef}
            selectedMapping={selectedMapping}
            onSelectMapping={setSelectedMapping}
          />
        </div>

        {/* Mapping detail panel */}
        {selectedMapping != null && mappings[selectedMapping] && (
          <MappingDetail
            mapping={mappings[selectedMapping]}
            index={selectedMapping}
            readOnly={readOnly}
            onChange={handleMappingChange}
            onRemove={handleMappingRemove}
          />
        )}

        {/* Code table panel */}
        {selectedMapping != null && mappings[selectedMapping] && (
          <CodeTableEditor
            mapping={mappings[selectedMapping]}
            codeTables={codeTables}
            readOnly={readOnly}
            onUpdateCodeTables={(updated) => { setCodeTables(updated); setDirty(true) }}
          />
        )}
      </div>

      {/* Loop mappings summary */}
      {loopMappings.length > 0 && (
        <div
          className="px-4 py-2 flex-shrink-0"
          style={{ borderTop: '1px solid rgb(var(--border))', background: 'rgb(var(--surface))' }}
        >
          <h4 className="text-xs font-bold uppercase tracking-wider mb-1.5" style={{ color: 'rgb(var(--tx-muted))' }}>
            Loop Mappings
          </h4>
          <div className="flex flex-wrap gap-2">
            {loopMappings.map((lm, i) => (
              <div key={i}
                className="flex items-center gap-2 text-xs px-2.5 py-1.5 rounded-lg"
                style={{ background: '#4c1d9512', border: '1px solid #4c1d9530' }}>
                <span className="font-mono font-semibold" style={{ color: '#c084fc', fontFamily: "'JetBrains Mono', monospace" }}>
                  {lm.sourceLoop || '?'}
                </span>
                <span style={{ color: 'rgb(var(--tx-muted))' }}>&rarr;</span>
                <span className="font-mono font-semibold" style={{ color: '#c084fc', fontFamily: "'JetBrains Mono', monospace" }}>
                  {lm.targetLoop || '?'}
                </span>
                <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  ({(lm.fieldMappings || []).length} fields)
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Keyboard hints */}
      {!readOnly && (
        <div
          className="px-4 py-1.5 flex items-center gap-4 text-[10px] flex-shrink-0"
          style={{ borderTop: '1px solid rgb(var(--border))', color: 'rgb(var(--tx-muted))' }}
        >
          <span>Click source field, then target field to create mapping</span>
          <span>Click connection line to select</span>
          <span>Use detail panel to configure transform</span>
        </div>
      )}
    </div>
  )
}
