import { useState, useMemo } from 'react'
import toast from 'react-hot-toast'
import {
  PlayIcon, DocumentCheckIcon, ExclamationTriangleIcon,
  BookmarkIcon, ChevronDownIcon, ChevronRightIcon,
} from '@heroicons/react/24/outline'
import { testMap } from '../api/ediConverter'

// ── Helpers ───────────────────────────────────────────────────────────────────

function countPopulatedFields(obj, prefix = '') {
  if (obj == null) return { total: 0, populated: 0, paths: [] }
  if (typeof obj !== 'object') return { total: 1, populated: obj !== '' && obj !== null ? 1 : 0, paths: [prefix] }

  let total = 0, populated = 0
  const paths = []

  if (Array.isArray(obj)) {
    for (let i = 0; i < obj.length; i++) {
      const sub = countPopulatedFields(obj[i], `${prefix}[${i}]`)
      total += sub.total
      populated += sub.populated
      paths.push(...sub.paths)
    }
  } else {
    for (const [key, val] of Object.entries(obj)) {
      const path = prefix ? `${prefix}.${key}` : key
      if (typeof val === 'object' && val !== null) {
        const sub = countPopulatedFields(val, path)
        total += sub.total
        populated += sub.populated
        paths.push(...sub.paths)
      } else {
        total++
        if (val !== '' && val !== null && val !== undefined) {
          populated++
          paths.push(path)
        }
      }
    }
  }

  return { total, populated, paths }
}

// ── JSON Tree Viewer with highlighting ────────────────────────────────────────

function JsonTree({ data, highlightPaths, depth = 0 }) {
  const [collapsed, setCollapsed] = useState({})

  if (data == null) return <span style={{ color: 'rgb(var(--tx-muted))' }}>null</span>
  if (typeof data !== 'object') {
    const isString = typeof data === 'string'
    return (
      <span style={{
        color: isString ? '#4ade80' : typeof data === 'number' ? '#60a5fa' : '#fbbf24',
        fontFamily: "'JetBrains Mono', monospace",
      }}>
        {isString ? `"${data}"` : String(data)}
      </span>
    )
  }

  const isArray = Array.isArray(data)
  const entries = isArray ? data.map((v, i) => [i, v]) : Object.entries(data)

  if (entries.length === 0) {
    return <span style={{ color: 'rgb(var(--tx-muted))' }}>{isArray ? '[]' : '{}'}</span>
  }

  return (
    <div style={{ paddingLeft: depth > 0 ? 16 : 0 }}>
      {entries.map(([key, val]) => {
        const fullPath = typeof key === 'number' ? `[${key}]` : key
        const isObj = typeof val === 'object' && val !== null
        const isCollapsed = collapsed[fullPath]
        const hasHighlight = highlightPaths?.has?.(fullPath) || highlightPaths?.has?.(key)
        const childCount = isObj ? (Array.isArray(val) ? val.length : Object.keys(val).length) : 0

        return (
          <div key={fullPath} className="leading-relaxed">
            <div
              className="flex items-start gap-1 group"
              style={{
                background: hasHighlight ? 'rgb(var(--accent) / 0.08)' : 'transparent',
                borderRadius: 4,
                paddingLeft: 2,
                paddingRight: 2,
              }}
            >
              {isObj ? (
                <button
                  className="w-4 h-4 flex items-center justify-center flex-shrink-0 mt-0.5"
                  onClick={() => setCollapsed(prev => ({ ...prev, [fullPath]: !prev[fullPath] }))}
                >
                  {isCollapsed
                    ? <ChevronRightIcon className="w-3 h-3" style={{ color: 'rgb(var(--tx-muted))' }} />
                    : <ChevronDownIcon className="w-3 h-3" style={{ color: 'rgb(var(--tx-muted))' }} />}
                </button>
              ) : (
                <span className="w-4 flex-shrink-0" />
              )}

              <span className="text-xs" style={{ color: '#c084fc', fontFamily: "'JetBrains Mono', monospace" }}>
                {typeof key === 'number' ? `[${key}]` : `"${key}"`}
              </span>
              <span style={{ color: 'rgb(var(--tx-muted))' }}>:</span>

              {isObj ? (
                <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                  {isCollapsed
                    ? `${Array.isArray(val) ? '[' : '{'} ... ${childCount} ${Array.isArray(val) ? ']' : '}'}`
                    : `${Array.isArray(val) ? '[' : '{'}`}
                </span>
              ) : (
                <JsonTree data={val} depth={depth + 1} />
              )}
            </div>

            {isObj && !isCollapsed && (
              <>
                <JsonTree data={val} highlightPaths={highlightPaths} depth={depth + 1} />
                <div style={{ paddingLeft: 20 }}>
                  <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                    {Array.isArray(val) ? ']' : '}'}
                  </span>
                </div>
              </>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ── Main MapTestPanel ─────────────────────────────────────────────────────────

export default function MapTestPanel({ mapId, sourceType, targetType }) {
  const [input, setInput] = useState('')
  const [output, setOutput] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [testCaseName, setTestCaseName] = useState('')

  const coverage = useMemo(() => {
    if (!output || typeof output !== 'object') return null
    return countPopulatedFields(output)
  }, [output])

  const populatedPaths = useMemo(() => {
    if (!coverage) return new Set()
    return new Set(coverage.paths)
  }, [coverage])

  const handleTest = async () => {
    if (!input.trim()) {
      toast.error('Paste sample content first')
      return
    }
    if (!mapId) {
      toast.error('No map selected')
      return
    }
    setLoading(true)
    setError(null)
    setOutput(null)
    try {
      const result = await testMap(mapId, input)
      if (result?.error) {
        setError(result.error)
        setOutput(result.partialOutput || null)
      } else {
        setOutput(result?.convertedDocument || result?.output || result)
        toast.success('Test completed')
      }
    } catch (e) {
      const errData = e.response?.data
      setError(errData?.message || errData?.error || e.message)
      if (errData?.partialOutput) setOutput(errData.partialOutput)
      toast.error('Test failed: ' + (errData?.message || e.message))
    } finally {
      setLoading(false)
    }
  }

  const handleSaveTestCase = () => {
    if (!testCaseName.trim()) {
      toast.error('Enter a test case name')
      return
    }
    // Store to localStorage as a simple persistence mechanism
    const key = `edi-test-cases:${mapId}`
    const existing = JSON.parse(localStorage.getItem(key) || '[]')
    existing.push({
      name: testCaseName.trim(),
      input,
      expectedOutput: output,
      createdAt: new Date().toISOString(),
    })
    localStorage.setItem(key, JSON.stringify(existing))
    toast.success(`Test case "${testCaseName}" saved`)
    setShowSaveDialog(false)
    setTestCaseName('')
  }

  return (
    <div className="space-y-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <PlayIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
          <h4 className="text-xs font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Test Panel
          </h4>
          {sourceType && (
            <span className="text-[10px] px-1.5 py-0.5 rounded"
              style={{ background: '#78350f22', color: '#fbbf24' }}>
              {sourceType}
            </span>
          )}
          {sourceType && targetType && (
            <span style={{ color: 'rgb(var(--tx-muted))' }}>&rarr;</span>
          )}
          {targetType && (
            <span className="text-[10px] px-1.5 py-0.5 rounded"
              style={{ background: '#14532d22', color: '#4ade80' }}>
              {targetType}
            </span>
          )}
        </div>
      </div>

      {/* Input */}
      <div>
        <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
          Sample Input Document
        </label>
        <textarea
          value={input}
          onChange={e => setInput(e.target.value)}
          rows={6}
          className="w-full mt-1 text-xs font-mono rounded-lg px-3 py-2"
          style={{
            background: 'rgb(var(--canvas))',
            color: 'rgb(var(--tx-primary))',
            border: '1px solid rgb(var(--border))',
            fontFamily: "'JetBrains Mono', monospace",
            resize: 'vertical',
          }}
          placeholder={sourceType
            ? `Paste a ${sourceType} document here...`
            : 'Paste sample EDI/data here...'}
        />
      </div>

      {/* Actions */}
      <div className="flex items-center gap-2">
        <button
          onClick={handleTest}
          disabled={loading || !input.trim()}
          className="btn-primary text-xs px-4 py-1.5 flex items-center gap-1.5"
        >
          {loading ? (
            <>
              <span className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />
              Testing...
            </>
          ) : (
            <>
              <PlayIcon className="w-3.5 h-3.5" />
              Test
            </>
          )}
        </button>

        {output && (
          <button
            onClick={() => setShowSaveDialog(true)}
            className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded transition-colors"
            style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-secondary))', border: '1px solid rgb(var(--border))' }}
          >
            <BookmarkIcon className="w-3.5 h-3.5" />
            Save as Test Case
          </button>
        )}
      </div>

      {/* Save dialog */}
      {showSaveDialog && (
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg"
          style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))' }}>
          <input
            value={testCaseName}
            onChange={e => setTestCaseName(e.target.value)}
            className="flex-1 text-xs px-2 py-1.5 rounded"
            style={{ background: 'rgb(var(--surface))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
            placeholder="Test case name..."
            onKeyDown={e => e.key === 'Enter' && handleSaveTestCase()}
            autoFocus
          />
          <button onClick={handleSaveTestCase} className="btn-primary text-xs px-3 py-1">Save</button>
          <button onClick={() => setShowSaveDialog(false)}
            className="text-xs px-2 py-1 rounded"
            style={{ color: 'rgb(var(--tx-muted))' }}>
            Cancel
          </button>
        </div>
      )}

      {/* Error display */}
      {error && (
        <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg"
          style={{ background: '#7f1d1d18', border: '1px solid #7f1d1d40' }}>
          <ExclamationTriangleIcon className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: '#f87171' }} />
          <div>
            <p className="text-xs font-semibold" style={{ color: '#f87171' }}>Conversion Error</p>
            <p className="text-xs mt-0.5" style={{ color: '#fca5a5' }}>{error}</p>
          </div>
        </div>
      )}

      {/* Coverage */}
      {coverage && (
        <div className="flex items-center gap-4 px-3 py-2 rounded-lg"
          style={{ background: 'rgb(var(--hover))' }}>
          <div className="flex items-center gap-1.5">
            <DocumentCheckIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
            <span className="text-xs font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>Coverage</span>
          </div>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <div className="flex-1 h-1.5 rounded-full" style={{ background: 'rgb(var(--border))' }}>
                <div
                  className="h-full rounded-full transition-all"
                  style={{
                    width: `${coverage.total > 0 ? (coverage.populated / coverage.total) * 100 : 0}%`,
                    background: coverage.populated === coverage.total ? '#4ade80' : 'rgb(var(--accent))',
                  }}
                />
              </div>
              <span className="text-xs font-mono" style={{
                color: coverage.populated === coverage.total ? '#4ade80' : 'rgb(var(--accent))',
                fontFamily: "'JetBrains Mono', monospace",
              }}>
                {coverage.populated}/{coverage.total}
              </span>
            </div>
          </div>
          <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
            {coverage.total > 0 ? Math.round((coverage.populated / coverage.total) * 100) : 0}% populated
          </span>
        </div>
      )}

      {/* Output */}
      {output && (
        <div>
          <label className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
            Conversion Output
          </label>
          <div
            className="mt-1 rounded-lg px-3 py-2 overflow-auto text-xs"
            style={{
              background: 'rgb(var(--canvas))',
              border: '1px solid rgb(var(--border))',
              maxHeight: 300,
            }}
          >
            {typeof output === 'object' ? (
              <JsonTree data={output} highlightPaths={populatedPaths} />
            ) : (
              <pre className="font-mono whitespace-pre-wrap"
                style={{ color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono', monospace" }}>
                {typeof output === 'string' ? output : JSON.stringify(output, null, 2)}
              </pre>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
