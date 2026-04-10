import { useState, useEffect } from 'react'
import toast from 'react-hot-toast'
import { ediApi as api } from '../api/client'
import { convertWithMap, getAvailableMaps, getMapDetail, detectDocumentType } from '../api/ediConverter'

const TABS = [
  { id: 'convert', label: 'Convert' },
  { id: 'maps', label: 'Maps' },
  { id: 'explain', label: 'Explain' },
  { id: 'heal', label: 'Self-Heal' },
  { id: 'compliance', label: 'Compliance' },
  { id: 'diff', label: 'Diff' },
  { id: 'nlcreate', label: 'NL Create' },
  { id: 'mapping', label: 'AI Mapping' },
  { id: 'partners', label: 'Partners' },
]

const DOC_TYPES = [
  'X12_850', 'X12_810', 'X12_856', 'X12_997', 'X12_834', 'X12_835', 'X12_837',
  'EDIFACT_ORDERS', 'EDIFACT_INVOIC', 'EDIFACT_DESADV',
  'SWIFT_MT103', 'SWIFT_MT202', 'SWIFT_MT940',
  'HL7_ADT', 'HL7_ORM', 'HL7_ORU',
  'NACHA_ACH', 'BAI2', 'ISO20022_PAIN', 'ISO20022_CAMT',
  'PEPPOL_INVOICE', 'PEPPOL_ORDER', 'FIX_NEWORDERSINGLE',
  'PURCHASE_ORDER_INH', 'INVOICE_INH', 'SHIP_NOTICE_INH',
  'JSON', 'XML', 'CSV', 'YAML', 'FLAT',
]

const CATEGORY_COLORS = {
  STANDARD: 'bg-blue-100 text-blue-800',
  TRAINED:  'bg-green-100 text-green-800',
  PARTNER:  'bg-purple-100 text-purple-800',
}

const SOURCE_STANDARDS = ['All', 'X12', 'EDIFACT', 'SWIFT', 'HL7', 'NACHA', 'BAI2', 'ISO20022', 'PEPPOL', 'FIX']

const SAMPLE_X12 = `ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00501*000000001*0*P*>~
GS*PO*SENDER*RECEIVER*20230101*1200*1*X*005010~
ST*850*0001~
BEG*00*NE*PO123456**20230101~
NM1*BY*2*ACME CORP*****ZZ*BUYER001~
NM1*SE*2*GLOBAL SUPPLY*****ZZ*SELLER001~
PO1*1*100*EA*12.50**VP*WIDGET-A1~
PO1*2*50*EA*25.00**VP*GADGET-B2~
CTT*2~
SE*9*0001~
GE*1*1~
IEA*1*000000001~`

export default function Edi() {
  const [content, setContent] = useState('')
  const [content2, setContent2] = useState('')
  const [nlText, setNlText] = useState('')
  const [mappingSource, setMappingSource] = useState('')
  const [mappingTarget, setMappingTarget] = useState('')
  const [partnerId, setPartnerId] = useState('')
  const [partnerName, setPartnerName] = useState('')
  const [result, setResult] = useState(null)
  const [tab, setTab] = useState('convert')
  const [target, setTarget] = useState('JSON')
  const [loading, setLoading] = useState(false)

  // Map-based conversion state
  const [sourceType, setSourceType] = useState('')
  const [targetType, setTargetType] = useState('')
  const [convertPartnerId, setConvertPartnerId] = useState('')
  const [detecting, setDetecting] = useState(false)

  // Maps browser state
  const [maps, setMaps] = useState([])
  const [mapsLoading, setMapsLoading] = useState(false)
  const [mapSearch, setMapSearch] = useState('')
  const [mapSourceFilter, setMapSourceFilter] = useState('All')
  const [mapTargetFilter, setMapTargetFilter] = useState('All')
  const [mapCategoryFilter, setMapCategoryFilter] = useState('All')
  const [selectedMap, setSelectedMap] = useState(null)
  const [mapDetailLoading, setMapDetailLoading] = useState(false)

  // Load maps when Maps tab is selected
  useEffect(() => {
    if (tab === 'maps' && maps.length === 0) {
      setMapsLoading(true)
      getAvailableMaps()
        .then(data => setMaps(Array.isArray(data) ? data : data?.maps || []))
        .catch(e => toast.error('Failed to load maps: ' + (e.response?.data?.message || e.message)))
        .finally(() => setMapsLoading(false))
    }
  }, [tab])

  const handleDetectType = async () => {
    if (!content.trim()) { toast.error('Paste content first'); return }
    setDetecting(true)
    try {
      const res = await detectDocumentType(content)
      setSourceType(res.documentType || res.type || '')
      toast.success('Detected: ' + (res.documentType || res.type || 'unknown'))
    } catch (e) { toast.error('Detection failed: ' + (e.response?.data?.message || e.message)) }
    finally { setDetecting(false) }
  }

  const handleConvertWithMap = async () => {
    if (!content.trim()) { toast.error('Paste content first'); return }
    if (!targetType) { toast.error('Select a target type'); return }
    setLoading(true)
    try {
      const res = await convertWithMap(content, sourceType, targetType, convertPartnerId)
      setResult({ label: 'Map Convert', data: res })
    } catch (e) {
      const errData = e.response?.data
      if (errData?.availableMaps) {
        setResult({ label: 'Map Convert Error', data: errData })
      }
      toast.error('Conversion failed: ' + (errData?.message || e.message))
    }
    finally { setLoading(false) }
  }

  const handleMapDetail = async (mapId) => {
    setMapDetailLoading(true)
    try {
      const detail = await getMapDetail(mapId)
      setSelectedMap(detail)
    } catch (e) { toast.error('Failed to load map: ' + (e.response?.data?.message || e.message)) }
    finally { setMapDetailLoading(false) }
  }

  const filteredMaps = maps.filter(m => {
    if (mapSearch && !((m.name || '').toLowerCase().includes(mapSearch.toLowerCase()) ||
        (m.mapId || m.id || '').toLowerCase().includes(mapSearch.toLowerCase()))) return false
    if (mapSourceFilter !== 'All' && !(m.sourceStandard || m.sourceType || '').toUpperCase().startsWith(mapSourceFilter)) return false
    if (mapTargetFilter !== 'All' && !(m.targetStandard || m.targetType || '').toUpperCase().startsWith(mapTargetFilter)) return false
    if (mapCategoryFilter !== 'All' && (m.category || '').toUpperCase() !== mapCategoryFilter) return false
    return true
  })

  const call = async (endpoint, data, label) => {
    setLoading(true)
    try {
      const r = await api.post(endpoint, data)
      setResult({ label, data: r.data })
    } catch (e) { toast.error(label + ' failed: ' + (e.response?.data?.message || e.message)) }
    finally { setLoading(false) }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-primary">EDI Converter v3.0</h1>
        <p className="text-secondary text-sm">11 formats, 66 conversion paths, AI-powered — convert, explain, heal, diff, score, map, create</p>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border gap-1 overflow-x-auto">
        {TABS.map(t => (
          <button key={t.id} onClick={() => { setTab(t.id); setResult(null) }}
            className={`pb-2 px-3 text-sm font-medium whitespace-nowrap transition-colors ${tab === t.id ? 'border-b-2 border-blue-600 text-blue-600' : 'text-secondary hover:text-primary'}`}>
            {t.label}
          </button>
        ))}
      </div>

      {/* Convert Tab */}
      {tab === 'convert' && (
        <div className="space-y-4">
          {/* Map-based conversion */}
          <div className="card space-y-4">
            <div className="flex items-center gap-3">
              <h3 className="font-semibold text-primary">Map-Based Conversion</h3>
              <button onClick={() => setContent(SAMPLE_X12)} className="text-xs text-blue-600 hover:underline">Load sample X12 850</button>
            </div>
            <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
              className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste any EDI content — X12, EDIFACT, HL7, SWIFT, PEPPOL..." />
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="text-xs font-medium text-secondary">Source Type</label>
                <div className="flex gap-1">
                  <select value={sourceType} onChange={e => setSourceType(e.target.value)}
                    className="flex-1 text-sm border rounded px-2 py-1.5">
                    <option value="">Auto-detect</option>
                    {DOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                  <button onClick={handleDetectType} disabled={detecting}
                    className="btn-sm bg-gray-700 text-white text-xs whitespace-nowrap">
                    {detecting ? '...' : 'Detect'}
                  </button>
                </div>
              </div>
              <div>
                <label className="text-xs font-medium text-secondary">Target Type</label>
                <select value={targetType} onChange={e => setTargetType(e.target.value)}
                  className="w-full text-sm border rounded px-2 py-1.5">
                  <option value="">Select target...</option>
                  {DOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div>
                <label className="text-xs font-medium text-secondary">Partner (optional)</label>
                <input value={convertPartnerId} onChange={e => setConvertPartnerId(e.target.value)}
                  className="w-full text-sm border rounded px-2 py-1.5" placeholder="partner-id" />
              </div>
            </div>
            <div className="flex gap-2">
              <button onClick={handleConvertWithMap} disabled={loading} className="btn-primary">
                {loading ? 'Converting...' : 'Convert with Map'}
              </button>
            </div>
          </div>

          {/* Quick tools row */}
          <div className="card space-y-3">
            <h3 className="font-semibold text-primary text-sm">Quick Tools</h3>
            <div className="flex flex-wrap gap-2">
              <button onClick={() => call('/api/v1/convert/detect', { content }, 'Detect')} className="btn-sm bg-gray-700 text-white">Detect Format</button>
              <button onClick={() => call('/api/v1/convert/validate', { content }, 'Validate')} className="btn-sm bg-green-600 text-white">Validate</button>
              <button onClick={() => call('/api/v1/convert/explain', { content }, 'Explain')} className="btn-sm bg-purple-600 text-white">Explain in English</button>
              <button onClick={() => call('/api/v1/convert/canonical', { content }, 'Canonical')} className="btn-sm bg-indigo-600 text-white">Canonical Model</button>
              <span className="border-l mx-1" />
              <select value={target} onChange={e => setTarget(e.target.value)} className="text-sm border rounded px-2 py-1">
                {['JSON', 'XML', 'CSV', 'YAML', 'FLAT', 'TIF'].map(f => <option key={f}>{f}</option>)}
              </select>
              <button onClick={() => call('/api/v1/convert/convert', { content, target }, 'Convert → ' + target)} className="btn-sm bg-blue-600 text-white">Convert (format)</button>
            </div>
          </div>
        </div>
      )}

      {/* Maps Tab */}
      {tab === 'maps' && (
        <div className="space-y-4">
          {/* Filters */}
          <div className="card space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold text-primary">Conversion Maps</h3>
              <button onClick={() => {
                setMapsLoading(true)
                getAvailableMaps()
                  .then(data => setMaps(Array.isArray(data) ? data : data?.maps || []))
                  .catch(e => toast.error('Refresh failed: ' + (e.response?.data?.message || e.message)))
                  .finally(() => setMapsLoading(false))
              }} className="btn-sm bg-gray-600 text-white text-xs">Refresh</button>
            </div>
            <div className="flex flex-wrap gap-3">
              <input value={mapSearch} onChange={e => setMapSearch(e.target.value)}
                className="flex-1 min-w-[200px] text-sm border rounded px-3 py-1.5" placeholder="Search by name or ID..." />
              <select value={mapSourceFilter} onChange={e => setMapSourceFilter(e.target.value)}
                className="text-sm border rounded px-2 py-1.5">
                {SOURCE_STANDARDS.map(s => <option key={s} value={s}>{s === 'All' ? 'Source: All' : s}</option>)}
              </select>
              <select value={mapTargetFilter} onChange={e => setMapTargetFilter(e.target.value)}
                className="text-sm border rounded px-2 py-1.5">
                {SOURCE_STANDARDS.map(s => <option key={s} value={s}>{s === 'All' ? 'Target: All' : s}</option>)}
              </select>
              <select value={mapCategoryFilter} onChange={e => setMapCategoryFilter(e.target.value)}
                className="text-sm border rounded px-2 py-1.5">
                <option value="All">Category: All</option>
                <option value="STANDARD">Standard</option>
                <option value="TRAINED">Trained</option>
                <option value="PARTNER">Partner</option>
              </select>
            </div>
          </div>

          {mapsLoading && <div className="text-center text-secondary py-8">Loading maps...</div>}

          {!mapsLoading && filteredMaps.length === 0 && (
            <div className="text-center text-secondary py-8">
              {maps.length === 0 ? 'No maps available. Train a model or import standard maps.' : 'No maps match the current filters.'}
            </div>
          )}

          {/* Map cards grid */}
          {!mapsLoading && filteredMaps.length > 0 && !selectedMap && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
              {filteredMaps.map(m => (
                <div key={m.mapId || m.id} onClick={() => handleMapDetail(m.mapId || m.id)}
                  className="card cursor-pointer hover:ring-2 hover:ring-blue-300 transition-all space-y-2">
                  <div className="flex items-start justify-between">
                    <h4 className="font-medium text-primary text-sm truncate">{m.name || m.mapId || m.id}</h4>
                    {m.category && (
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${CATEGORY_COLORS[m.category?.toUpperCase()] || 'bg-gray-100 text-gray-800'}`}>
                        {m.category}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-1 text-xs">
                    <span className="bg-amber-100 text-amber-800 px-1.5 py-0.5 rounded">{m.sourceType || m.sourceStandard || '?'}</span>
                    <span className="text-secondary">&rarr;</span>
                    <span className="bg-emerald-100 text-emerald-800 px-1.5 py-0.5 rounded">{m.targetType || m.targetStandard || '?'}</span>
                  </div>
                  <div className="flex items-center gap-3 text-xs text-secondary">
                    {m.version && <span>v{m.version}</span>}
                    {m.confidence != null && <span>Confidence: {m.confidence}%</span>}
                    {m.fieldCount != null && <span>{m.fieldCount} fields</span>}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Map detail panel */}
          {selectedMap && (
            <div className="card space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <button onClick={() => setSelectedMap(null)} className="text-sm text-blue-600 hover:underline mr-3">&larr; Back to maps</button>
                  <span className="font-semibold text-primary text-lg">{selectedMap.name || selectedMap.mapId}</span>
                </div>
                {selectedMap.category && (
                  <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${CATEGORY_COLORS[selectedMap.category?.toUpperCase()] || 'bg-gray-100 text-gray-800'}`}>
                    {selectedMap.category}
                  </span>
                )}
              </div>

              {/* Metadata */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
                <div className="bg-canvas rounded p-2">
                  <div className="text-xs text-secondary">Source</div>
                  <div className="font-medium">{selectedMap.sourceType || selectedMap.sourceStandard || '-'}</div>
                </div>
                <div className="bg-canvas rounded p-2">
                  <div className="text-xs text-secondary">Target</div>
                  <div className="font-medium">{selectedMap.targetType || selectedMap.targetStandard || '-'}</div>
                </div>
                <div className="bg-canvas rounded p-2">
                  <div className="text-xs text-secondary">Version</div>
                  <div className="font-medium">{selectedMap.version || '-'}</div>
                </div>
                <div className="bg-canvas rounded p-2">
                  <div className="text-xs text-secondary">Status</div>
                  <div className="font-medium">{selectedMap.status || '-'}</div>
                </div>
              </div>

              {selectedMap.description && (
                <p className="text-sm text-secondary">{selectedMap.description}</p>
              )}

              {/* Field mappings table */}
              {(selectedMap.fieldMappings || selectedMap.mappings) && (
                <div>
                  <h4 className="font-medium text-primary text-sm mb-2">Field Mappings</h4>
                  <div className="overflow-auto max-h-96 border rounded-lg">
                    <table className="w-full text-xs">
                      <thead className="bg-canvas sticky top-0">
                        <tr>
                          <th className="text-left px-3 py-2 font-medium text-secondary">Source Path</th>
                          <th className="text-left px-3 py-2 font-medium text-secondary">Target Path</th>
                          <th className="text-left px-3 py-2 font-medium text-secondary">Transform</th>
                          <th className="text-left px-3 py-2 font-medium text-secondary">Confidence</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {(selectedMap.fieldMappings || selectedMap.mappings || []).map((fm, i) => (
                          <tr key={i} className="hover:bg-canvas">
                            <td className="px-3 py-1.5 font-mono">{fm.sourcePath || fm.source}</td>
                            <td className="px-3 py-1.5 font-mono">{fm.targetPath || fm.target}</td>
                            <td className="px-3 py-1.5">{fm.transform || fm.transformation || 'direct'}</td>
                            <td className="px-3 py-1.5">
                              {fm.confidence != null && (
                                <span className={fm.confidence >= 90 ? 'text-green-600' : fm.confidence >= 70 ? 'text-yellow-600' : 'text-red-600'}>
                                  {fm.confidence}%
                                </span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {/* Code tables */}
              {selectedMap.codeTables && selectedMap.codeTables.length > 0 && (
                <div>
                  <h4 className="font-medium text-primary text-sm mb-2">Code Tables</h4>
                  <pre className="text-xs bg-canvas rounded-lg p-3 overflow-auto font-mono max-h-48">
                    {JSON.stringify(selectedMap.codeTables, null, 2)}
                  </pre>
                </div>
              )}

              {/* Loop mappings */}
              {selectedMap.loopMappings && selectedMap.loopMappings.length > 0 && (
                <div>
                  <h4 className="font-medium text-primary text-sm mb-2">Loop Mappings</h4>
                  <pre className="text-xs bg-canvas rounded-lg p-3 overflow-auto font-mono max-h-48">
                    {JSON.stringify(selectedMap.loopMappings, null, 2)}
                  </pre>
                </div>
              )}

              {/* Raw JSON fallback */}
              <details className="text-xs">
                <summary className="cursor-pointer text-secondary hover:text-primary">Raw map data</summary>
                <pre className="bg-canvas rounded-lg p-3 overflow-auto font-mono max-h-64 mt-2">
                  {JSON.stringify(selectedMap, null, 2)}
                </pre>
              </details>
            </div>
          )}
        </div>
      )}

      {/* Explain Tab */}
      {tab === 'explain' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Explain EDI in Plain English</h3>
          <p className="text-sm text-secondary">Paste any EDI document and get a human-readable explanation of every segment.</p>
          <button onClick={() => setContent(SAMPLE_X12)} className="text-xs text-blue-600 hover:underline">Load sample</button>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste EDI content..." />
          <button onClick={() => call('/api/v1/convert/explain', { content }, 'Explain')} className="btn-primary" disabled={loading}>
            {loading ? 'Explaining...' : 'Explain This'}
          </button>
        </div>
      )}

      {/* Self-Heal Tab */}
      {tab === 'heal' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Self-Healing Engine</h3>
          <p className="text-sm text-secondary">Paste a broken or malformed EDI document. The engine auto-detects and fixes common errors.</p>
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-3 text-sm text-blue-800">
            Fixes 25+ error types: missing terminators, wrong segment counts, ISA padding, missing trailers, BOM removal, null bytes, mixed line endings, and more.
          </div>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste broken EDI content..." />
          <button onClick={() => call('/api/v1/convert/heal', { content }, 'Self-Heal')} className="btn-primary" disabled={loading}>
            {loading ? 'Healing...' : 'Auto-Fix'}
          </button>
        </div>
      )}

      {/* Compliance Tab */}
      {tab === 'compliance' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Compliance Scoring (0-100)</h3>
          <p className="text-sm text-secondary">Score your EDI document against the standard. Get a grade (A+ to F) with detailed breakdown.</p>
          <button onClick={() => setContent(SAMPLE_X12)} className="text-xs text-blue-600 hover:underline">Load sample</button>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste EDI content..." />
          <button onClick={() => call('/api/v1/convert/compliance', { content }, 'Compliance')} className="btn-primary" disabled={loading}>
            {loading ? 'Scoring...' : 'Score Compliance'}
          </button>
        </div>
      )}

      {/* Diff Tab */}
      {tab === 'diff' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Semantic Diff</h3>
          <p className="text-sm text-secondary">Compare two EDI documents with field-level semantic diff — not character-level, BUSINESS-level.</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs font-medium text-secondary">Left (before)</label>
              <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste first EDI..." />
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Right (after)</label>
              <textarea value={content2} onChange={e => setContent2(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste second EDI..." />
            </div>
          </div>
          <button onClick={() => call('/api/v1/convert/diff', { left: content, right: content2 }, 'Diff')} className="btn-primary" disabled={loading}>
            {loading ? 'Comparing...' : 'Compare'}
          </button>
        </div>
      )}

      {/* NL Create Tab */}
      {tab === 'nlcreate' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Create EDI from Natural Language</h3>
          <p className="text-sm text-secondary">Describe what you need in plain English. We'll generate valid EDI.</p>
          <div className="bg-green-50 border border-green-100 rounded-lg p-3 text-sm text-green-800 space-y-1">
            <p className="font-medium">Try these:</p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Create a purchase order for 500 widgets at $12.50 each to Acme Corp')}>
              "Create a purchase order for 500 widgets at $12.50 each to Acme Corp"
            </p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Generate an invoice for $15,000 from GlobalSupplier to RetailBuyer')}>
              "Generate an invoice for $15,000 from GlobalSupplier to RetailBuyer"
            </p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Create a healthcare claim for patient John Doe, $1500, diagnosis J06.9')}>
              "Create a healthcare claim for patient John Doe, $1500, diagnosis J06.9"
            </p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Send a wire transfer for $50,000 from Acme Corp to Global Trading')}>
              "Send a wire transfer for $50,000 from Acme Corp to Global Trading"
            </p>
          </div>
          <textarea value={nlText} onChange={e => setNlText(e.target.value)} rows={3}
            className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="Describe what EDI document you need..." />
          <button onClick={() => call('/api/v1/convert/create', { text: nlText }, 'NL Create')} className="btn-primary" disabled={loading}>
            {loading ? 'Generating...' : 'Generate EDI'}
          </button>
        </div>
      )}

      {/* AI Mapping Tab */}
      {tab === 'mapping' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">AI Mapping Generator</h3>
          <p className="text-sm text-secondary">Upload a source EDI and your desired JSON output. We auto-generate the mapping rules.</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs font-medium text-secondary">Source EDI</label>
              <textarea value={mappingSource} onChange={e => setMappingSource(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste source EDI..." />
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Desired JSON Output</label>
              <textarea value={mappingTarget} onChange={e => setMappingTarget(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono"
                placeholder='{"poNumber":"PO123456","buyer":"ACME CORP","seller":"GLOBAL SUPPLY","items":[...]}' />
            </div>
          </div>
          <button onClick={() => call('/api/v1/convert/mapping/generate', { source: mappingSource, target: mappingTarget }, 'AI Mapping')} className="btn-primary" disabled={loading}>
            {loading ? 'Generating Mapping...' : 'Generate Mapping'}
          </button>
        </div>
      )}

      {/* Partners Tab */}
      {tab === 'partners' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Partner Profile Manager</h3>
          <p className="text-sm text-secondary">Upload a sample EDI from a new trading partner to auto-generate their profile.</p>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="text-xs font-medium text-secondary">Partner ID</label>
              <input value={partnerId} onChange={e => setPartnerId(e.target.value)} placeholder="acme-corp" /></div>
            <div><label className="text-xs font-medium text-secondary">Partner Name</label>
              <input value={partnerName} onChange={e => setPartnerName(e.target.value)} placeholder="Acme Corporation" /></div>
          </div>
          <label className="text-xs font-medium text-secondary">Sample EDI from this partner</label>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={6}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste a sample EDI from this partner..." />
          <button onClick={() => call(`/api/v1/convert/partners/${partnerId || 'partner-1'}/analyze`,
            { content, partnerName: partnerName || partnerId }, 'Analyze Partner')} className="btn-primary" disabled={loading}>
            {loading ? 'Analyzing...' : 'Analyze & Create Profile'}
          </button>
        </div>
      )}

      {/* Results */}
      {result && (
        <div className="card">
          <h3 className="font-semibold text-primary mb-2">{result.label} Result</h3>

          {/* Special rendering for compliance */}
          {result.label === 'Compliance' && result.data?.overallScore !== undefined && (
            <div className="mb-4 p-4 rounded-lg bg-canvas">
              <div className="flex items-center gap-4 mb-3">
                <div className={`text-4xl font-bold ${result.data.overallScore >= 90 ? 'text-green-600' : result.data.overallScore >= 70 ? 'text-yellow-600' : 'text-red-600'}`}>
                  {result.data.overallScore}
                </div>
                <div>
                  <div className="text-lg font-semibold">Grade: {result.data.grade}</div>
                  <div className="text-sm text-secondary">{result.data.verdict}</div>
                </div>
              </div>
              <div className="grid grid-cols-4 gap-2 text-sm">
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-blue-600">{result.data.structureScore}</div>
                  <div className="text-xs text-secondary">Structure</div>
                </div>
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-purple-600">{result.data.elementScore}</div>
                  <div className="text-xs text-secondary">Elements</div>
                </div>
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-green-600">{result.data.businessRuleScore}</div>
                  <div className="text-xs text-secondary">Business Rules</div>
                </div>
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-amber-600">{result.data.bestPracticeScore}</div>
                  <div className="text-xs text-secondary">Best Practice</div>
                </div>
              </div>
            </div>
          )}

          {/* Special rendering for self-heal */}
          {result.label === 'Self-Heal' && result.data?.verdict && (
            <div className={`mb-4 p-3 rounded-lg text-sm ${result.data.wasHealed ? 'bg-green-50 text-green-800 border border-green-100' : 'bg-canvas text-primary'}`}>
              <span className="font-medium">{result.data.verdict}</span>
              {result.data.wasHealed && <span className="ml-2">({result.data.issuesFixed} of {result.data.issuesFound} fixed)</span>}
            </div>
          )}

          {/* Special rendering for NL create */}
          {result.label === 'NL Create' && result.data?.generatedEdi && (
            <div className="mb-4 space-y-2">
              <div className="p-3 bg-blue-50 rounded-lg text-sm text-blue-800">{result.data.explanation}</div>
              {result.data.confidence > 0 && (
                <div className="text-xs text-secondary">Confidence: {result.data.confidence}%</div>
              )}
              <label className="text-xs font-medium text-secondary">Generated EDI:</label>
              <pre className="text-xs bg-gray-900 text-green-400 rounded-lg p-3 overflow-auto font-mono max-h-64">
                {result.data.generatedEdi}
              </pre>
            </div>
          )}

          {/* Special rendering for diff */}
          {result.label === 'Diff' && result.data?.verdict && (
            <div className="mb-4 p-3 bg-canvas rounded-lg text-sm">
              <span className="font-medium">{result.data.verdict}</span>
              <span className="text-secondary ml-2">
                (+{result.data.segmentsAdded} added, -{result.data.segmentsRemoved} removed, ~{result.data.segmentsModified} modified)
              </span>
            </div>
          )}

          {/* Special rendering for map convert */}
          {result.label === 'Map Convert' && result.data && (
            <div className="mb-4 space-y-3">
              <div className="flex items-center gap-4 text-sm">
                {result.data.mapUsed && (
                  <div className="bg-blue-50 border border-blue-100 rounded-lg px-3 py-2">
                    <span className="text-secondary">Map:</span> <span className="font-medium text-blue-800">{result.data.mapUsed}</span>
                  </div>
                )}
                {result.data.confidence != null && (
                  <div className={`rounded-lg px-3 py-2 ${result.data.confidence >= 90 ? 'bg-green-50 border border-green-100 text-green-800' : result.data.confidence >= 70 ? 'bg-yellow-50 border border-yellow-100 text-yellow-800' : 'bg-red-50 border border-red-100 text-red-800'}`}>
                    Confidence: <span className="font-bold">{result.data.confidence}%</span>
                  </div>
                )}
              </div>
              {result.data.output && (
                <>
                  <label className="text-xs font-medium text-secondary">Converted Output:</label>
                  <pre className="text-xs bg-gray-900 text-green-400 rounded-lg p-3 overflow-auto font-mono max-h-64">
                    {result.data.output}
                  </pre>
                </>
              )}
            </div>
          )}

          {/* Special rendering for map convert error — show available maps */}
          {result.label === 'Map Convert Error' && result.data?.availableMaps && (
            <div className="mb-4 space-y-2">
              <div className="bg-amber-50 border border-amber-100 rounded-lg p-3 text-sm text-amber-800">
                <p className="font-medium">No matching map found. Available maps for this conversion:</p>
                <ul className="mt-2 space-y-1">
                  {result.data.availableMaps.map((m, i) => (
                    <li key={i} className="font-mono text-xs">{m.mapId || m.name} ({m.sourceType} &rarr; {m.targetType})</li>
                  ))}
                </ul>
              </div>
            </div>
          )}

          {/* JSON output */}
          <pre className="text-xs bg-canvas rounded-lg p-3 overflow-auto font-mono max-h-96">
            {typeof result.data === 'string' ? result.data : JSON.stringify(result.data, null, 2)}
          </pre>
        </div>
      )}

      {/* Feature Overview */}
      {!result && tab === 'convert' && (
        <div className="grid grid-cols-3 gap-4">
          {[
            ['Map-Based Convert', 'Document-type-aware conversion via trained maps — select source & target type, auto-detect, partner-specific'],
            ['Map Browser', 'Browse, search, and inspect all conversion maps — field mappings, code tables, loop mappings, confidence scores'],
            ['11 Input Formats', 'X12, EDIFACT, TRADACOMS, SWIFT MT, HL7, NACHA, BAI2, ISO20022, FIX, PEPPOL/UBL, Auto-detect'],
            ['6 Output Formats', 'JSON, XML, CSV, YAML, Fixed-width, TIF (TranzFer Internal)'],
            ['Self-Healing', 'Auto-fixes 25+ common EDI errors — missing terminators, wrong counts, padding, trailers'],
            ['Compliance 0-100', 'A+ to F grading with structure, element, business rule, and best practice categories'],
            ['Semantic Diff', 'Field-level comparison — "PO1*02 changed from EA to CS" not "line 47 char 12 changed"'],
            ['AI Mapping', 'Upload source EDI + desired output → auto-generates mapping rules (no LLM needed)'],
            ['NL Create', 'Say "create a PO for 500 widgets to Acme" → get valid X12 850'],
            ['Partner Profiles', 'Upload a sample from a new partner → auto-generate their profile in minutes'],
            ['Canonical Model', 'Universal JSON schema — one model for all formats. Eliminates per-partner mapping.'],
          ].map(([title, desc]) => (
            <div key={title} className="p-4 bg-canvas rounded-lg">
              <h4 className="font-medium text-primary text-sm">{title}</h4>
              <p className="text-xs text-secondary mt-1">{desc}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
