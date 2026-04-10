import { useState, useMemo } from 'react'
import {
  ChevronDownIcon, ChevronRightIcon,
  CheckCircleIcon, ExclamationTriangleIcon, XCircleIcon,
  EyeIcon,
} from '@heroicons/react/24/outline'

// ── Helpers ──────────────────────────────────────────────────────────────────

function countFields(obj, prefix = '') {
  if (obj == null || typeof obj !== 'object') {
    return { total: 1, populated: obj !== '' && obj !== null && obj !== undefined ? 1 : 0 }
  }
  let total = 0, populated = 0
  const entries = Array.isArray(obj) ? obj.map((v, i) => [i, v]) : Object.entries(obj)
  for (const [key, val] of entries) {
    const sub = countFields(val, `${prefix}.${key}`)
    total += sub.total
    populated += sub.populated
  }
  return { total, populated }
}

function getFieldStatus(path, lowConfidenceFields, diffPaths) {
  if (diffPaths?.has(path)) return 'diff'
  if (lowConfidenceFields?.includes(path)) return 'low'
  return 'ok'
}

const STATUS_STYLES = {
  ok:   { bg: '#14532d10', border: '#14532d30', dot: '#4ade80' },
  low:  { bg: '#78350f10', border: '#78350f30', dot: '#fbbf24' },
  diff: { bg: '#7f1d1d10', border: '#7f1d1d30', dot: '#f87171' },
}

// ── Coverage Bar ─────────────────────────────────────────────────────────────

function CoverageBar({ populated, total }) {
  const pct = total > 0 ? Math.round((populated / total) * 100) : 0
  const color = pct >= 80 ? '#4ade80' : pct >= 50 ? '#fbbf24' : '#f87171'

  return (
    <div className="flex items-center gap-3 px-4 py-3"
      style={{ borderBottom: '1px solid rgb(var(--border))' }}>
      <div className="flex-1">
        <div className="flex items-center justify-between mb-1.5">
          <span className="text-xs font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>
            Field Coverage
          </span>
          <span className="text-xs font-bold" style={{ color }}>
            {populated} of {total} fields populated ({pct}%)
          </span>
        </div>
        <div className="h-2 rounded-full overflow-hidden" style={{ background: 'rgb(var(--border))' }}>
          <div
            className="h-full rounded-full transition-all duration-500"
            style={{ width: `${pct}%`, background: color }}
          />
        </div>
      </div>
    </div>
  )
}

// ── Legend ────────────────────────────────────────────────────────────────────

function Legend({ hasLowConf, hasDiff }) {
  return (
    <div className="flex items-center gap-4 px-4 py-2"
      style={{ borderBottom: '1px solid rgb(var(--border))' }}>
      <div className="flex items-center gap-1.5">
        <CheckCircleIcon className="w-3.5 h-3.5" style={{ color: '#4ade80' }} />
        <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>Mapped (high confidence)</span>
      </div>
      {hasLowConf && (
        <div className="flex items-center gap-1.5">
          <ExclamationTriangleIcon className="w-3.5 h-3.5" style={{ color: '#fbbf24' }} />
          <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>Low confidence</span>
        </div>
      )}
      {hasDiff && (
        <div className="flex items-center gap-1.5">
          <XCircleIcon className="w-3.5 h-3.5" style={{ color: '#f87171' }} />
          <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>Differs from expected</span>
        </div>
      )}
    </div>
  )
}

// ── JSON Node ────────────────────────────────────────────────────────────────

function JsonNode({ keyName, value, depth, path, lowConfidenceFields, diffPaths }) {
  const [collapsed, setCollapsed] = useState(depth > 2)
  const isObj = typeof value === 'object' && value !== null
  const isArray = Array.isArray(value)
  const entries = isObj ? (isArray ? value.map((v, i) => [i, v]) : Object.entries(value)) : []
  const childCount = entries.length

  const status = !isObj ? getFieldStatus(path, lowConfidenceFields, diffPaths) : null
  const statusStyle = status ? STATUS_STYLES[status] : null

  // Primitive value
  if (!isObj) {
    const isString = typeof value === 'string'
    const isNumber = typeof value === 'number'
    const isBool = typeof value === 'boolean'
    const isNull = value === null || value === undefined

    return (
      <div
        className="flex items-start gap-1 px-1 py-0.5 rounded"
        style={{
          paddingLeft: depth * 16 + 4,
          background: statusStyle?.bg || 'transparent',
          borderLeft: statusStyle ? `2px solid ${statusStyle.border}` : '2px solid transparent',
        }}
      >
        {keyName !== null && (
          <>
            <span className="text-xs flex-shrink-0" style={{ color: '#c084fc', fontFamily: "'JetBrains Mono', monospace" }}>
              {typeof keyName === 'number' ? `[${keyName}]` : `"${keyName}"`}
            </span>
            <span className="flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }}>:</span>
          </>
        )}
        <span className="text-xs" style={{
          color: isNull ? 'rgb(var(--tx-muted))'
            : isString ? '#4ade80'
            : isNumber ? '#60a5fa'
            : isBool ? '#fbbf24'
            : 'rgb(var(--tx-primary))',
          fontFamily: "'JetBrains Mono', monospace",
        }}>
          {isNull ? 'null' : isString ? `"${value}"` : String(value)}
        </span>
        {status === 'low' && (
          <ExclamationTriangleIcon className="w-3 h-3 flex-shrink-0 ml-1" style={{ color: '#fbbf24' }} />
        )}
        {status === 'diff' && (
          <XCircleIcon className="w-3 h-3 flex-shrink-0 ml-1" style={{ color: '#f87171' }} />
        )}
      </div>
    )
  }

  // Object / array
  return (
    <div>
      <div
        className="flex items-center gap-1 px-1 py-0.5 cursor-pointer group"
        style={{ paddingLeft: depth * 16 + 4 }}
        onClick={() => setCollapsed(prev => !prev)}
      >
        <button className="w-4 h-4 flex items-center justify-center flex-shrink-0">
          {collapsed
            ? <ChevronRightIcon className="w-3 h-3" style={{ color: 'rgb(var(--tx-muted))' }} />
            : <ChevronDownIcon className="w-3 h-3" style={{ color: 'rgb(var(--tx-muted))' }} />}
        </button>
        {keyName !== null && (
          <>
            <span className="text-xs" style={{ color: '#c084fc', fontFamily: "'JetBrains Mono', monospace" }}>
              {typeof keyName === 'number' ? `[${keyName}]` : `"${keyName}"`}
            </span>
            <span style={{ color: 'rgb(var(--tx-muted))' }}>:</span>
          </>
        )}
        <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
          {collapsed
            ? `${isArray ? '[' : '{'} ... ${childCount} ${isArray ? ']' : '}'}`
            : `${isArray ? '[' : '{'}`}
        </span>
      </div>

      {!collapsed && (
        <>
          {entries.map(([key, val]) => {
            const childPath = path ? `${path}.${key}` : String(key)
            return (
              <JsonNode
                key={childPath}
                keyName={key}
                value={val}
                depth={depth + 1}
                path={childPath}
                lowConfidenceFields={lowConfidenceFields}
                diffPaths={diffPaths}
              />
            )
          })}
          <div className="text-[10px] px-1 py-0.5" style={{ paddingLeft: depth * 16 + 4, color: 'rgb(var(--tx-muted))' }}>
            {isArray ? ']' : '}'}
          </div>
        </>
      )}
    </div>
  )
}

// ── Main Component ───────────────────────────────────────────────────────────

export default function MapPreview({ preview, expected, lowConfidenceFields = [] }) {
  // Compute diff paths if expected is provided
  const diffPaths = useMemo(() => {
    if (!expected || !preview) return new Set()
    const diffs = new Set()

    function compare(a, b, path) {
      if (a === b) return
      if (typeof a !== typeof b || a === null || b === null) {
        diffs.add(path)
        return
      }
      if (typeof a !== 'object') {
        if (a !== b) diffs.add(path)
        return
      }
      const aKeys = Array.isArray(a) ? a.map((_, i) => i) : Object.keys(a)
      const bKeys = Array.isArray(b) ? b.map((_, i) => i) : Object.keys(b)
      const allKeys = new Set([...aKeys.map(String), ...bKeys.map(String)])
      for (const key of allKeys) {
        compare(a[key], b[key], path ? `${path}.${key}` : String(key))
      }
    }

    compare(preview, expected, '')
    return diffs
  }, [preview, expected])

  // Field counts
  const { total, populated } = useMemo(() => countFields(preview), [preview])

  if (!preview) {
    return (
      <div className="flex flex-col items-center justify-center py-12 gap-2"
        style={{ color: 'rgb(var(--tx-muted))' }}>
        <EyeIcon className="w-8 h-8" />
        <p className="text-sm">No preview data available</p>
      </div>
    )
  }

  const hasLowConf = lowConfidenceFields.length > 0
  const hasDiff = diffPaths.size > 0

  return (
    <div className="flex flex-col rounded-xl overflow-hidden"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}>

      {/* Coverage bar */}
      <CoverageBar populated={populated} total={total} />

      {/* Legend */}
      <Legend hasLowConf={hasLowConf} hasDiff={hasDiff} />

      {/* JSON tree */}
      <div className="flex-1 overflow-auto px-2 py-2" style={{ maxHeight: 500, minHeight: 200 }}>
        <JsonNode
          keyName={null}
          value={preview}
          depth={0}
          path=""
          lowConfidenceFields={lowConfidenceFields}
          diffPaths={diffPaths}
        />
      </div>
    </div>
  )
}

