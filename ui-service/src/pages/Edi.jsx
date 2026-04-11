import { useState, useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import toast from 'react-hot-toast'
import { ediApi as api } from '../api/client'
import {
  convertWithMap, getAvailableMaps, getMapDetail, detectDocumentType,
  cloneMap, getPartnerMaps, updateMap, activateMap, deactivateMap, deleteMap,
  submitMapFeedback,
} from '../api/ediConverter'
import {
  SparklesIcon, XMarkIcon, ExclamationTriangleIcon,
} from '@heroicons/react/24/outline'
import { useServices } from '../context/ServiceContext'
import MapEditor from '../components/MapEditor'
import MapTestPanel from '../components/MapTestPanel'
import SampleUploader from '../components/SampleUploader'
import MapAssistant from '../components/MapAssistant'
import MapPreview from '../components/MapPreview'
import ConfirmDialog from '../components/ConfirmDialog'

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
  // Route-driven initial tab — sidebar has 3 entries that all render this page
  // but land on different tabs via the path:
  //   /edi           → convert (default)
  //   /edi-mapping   → maps    (visual map editor)
  //   /edi-partners  → partners
  const { pathname } = useLocation()
  const initialTab =
    pathname === '/edi-mapping'  ? 'maps' :
    pathname === '/edi-partners' ? 'partners' :
                                   'convert'

  const { isServiceRunning, loading: servicesLoading } = useServices()
  const ediDown = !servicesLoading && !isServiceRunning('ediConverter')

  const [content, setContent] = useState('')
  const [content2, setContent2] = useState('')
  const [nlText, setNlText] = useState('')
  const [mappingSource, setMappingSource] = useState('')
  const [mappingTarget, setMappingTarget] = useState('')
  const [partnerId, setPartnerId] = useState('')
  const [partnerName, setPartnerName] = useState('')
  const [result, setResult] = useState(null)
  const [tab, setTab] = useState(initialTab)
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

  // Map editor state
  const [mapEditorOpen, setMapEditorOpen] = useState(false)
  const [mapEditorReadOnly, setMapEditorReadOnly] = useState(false)
  const [mapEditorDef, setMapEditorDef] = useState(null)

  // Partner maps state
  const [mapMode, setMapMode] = useState('standard') // 'standard' | 'partner'
  const [partnerMaps, setPartnerMaps] = useState([])
  const [partnerMapsLoading, setPartnerMapsLoading] = useState(false)
  const [selectedPartnerForMaps, setSelectedPartnerForMaps] = useState('')

  // Clone modal state
  const [cloneModalOpen, setCloneModalOpen] = useState(false)
  const [clonePartnerName, setClonePartnerName] = useState('')
  const [cloneMapName, setCloneMapName] = useState('')
  const [cloning, setCloning] = useState(false)

  // Build Map flow state
  const [buildMapOpen, setBuildMapOpen] = useState(false)
  const [buildMapStep, setBuildMapStep] = useState('upload') // 'upload' | 'preview' | 'refine'
  const [buildMapResult, setBuildMapResult] = useState(null)
  const [buildMapPreview, setBuildMapPreview] = useState(null)
  const [deleteMapTarget, setDeleteMapTarget] = useState(null)

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

  // Open map in visual editor
  const handleEditMap = async (map) => {
    if (!map) return
    let detail = map
    if (!map.fieldMappings && !map.mappings) {
      setMapDetailLoading(true)
      try {
        detail = await getMapDetail(map.mapId || map.id)
      } catch (e) {
        toast.error('Failed to load map: ' + (e.response?.data?.message || e.message))
        setMapDetailLoading(false)
        return
      }
      setMapDetailLoading(false)
    }
    const isStandard = (detail.category || '').toUpperCase() === 'STANDARD'
    setMapEditorDef(detail)
    setMapEditorReadOnly(isStandard)
    setMapEditorOpen(true)
  }

  // Save updated map
  const handleMapSave = async (updatedDef) => {
    try {
      await updateMap(updatedDef.mapId || updatedDef.id, updatedDef)
      toast.success('Map saved successfully')
      setMapEditorOpen(false)
      // Refresh maps list
      if (mapMode === 'partner' && selectedPartnerForMaps) {
        loadPartnerMaps(selectedPartnerForMaps)
      }
    } catch (e) {
      toast.error('Save failed: ' + (e.response?.data?.message || e.message))
    }
  }

  // Clone a standard map for a partner
  const handleCloneMap = async () => {
    if (!clonePartnerName.trim()) { toast.error('Enter a partner name'); return }
    const sourceId = mapEditorDef?.mapId || mapEditorDef?.id || selectedMap?.mapId || selectedMap?.id
    if (!sourceId) { toast.error('No map selected to clone'); return }
    setCloning(true)
    try {
      const cloned = await cloneMap(sourceId, clonePartnerName.trim(), cloneMapName.trim() || undefined)
      toast.success('Map cloned for partner: ' + clonePartnerName)
      setCloneModalOpen(false)
      setClonePartnerName('')
      setCloneMapName('')
      // Open the cloned map in edit mode
      setMapEditorDef(cloned)
      setMapEditorReadOnly(false)
      setMapEditorOpen(true)
    } catch (e) {
      toast.error('Clone failed: ' + (e.response?.data?.message || e.message))
    } finally {
      setCloning(false)
    }
  }

  // Load partner maps
  const loadPartnerMaps = async (pid) => {
    if (!pid) return
    setPartnerMapsLoading(true)
    try {
      const data = await getPartnerMaps(pid)
      setPartnerMaps(Array.isArray(data) ? data : data?.maps || [])
    } catch (e) {
      toast.error('Failed to load partner maps: ' + (e.response?.data?.message || e.message))
      setPartnerMaps([])
    } finally {
      setPartnerMapsLoading(false)
    }
  }

  // Activate/deactivate partner map
  const handleToggleMapStatus = async (map) => {
    const mapId = map.mapId || map.id
    try {
      if (map.status === 'ACTIVE' || map.active) {
        await deactivateMap(mapId)
        toast.success('Map deactivated')
      } else {
        await activateMap(mapId)
        toast.success('Map activated')
      }
      if (selectedPartnerForMaps) loadPartnerMaps(selectedPartnerForMaps)
    } catch (e) {
      toast.error('Status change failed: ' + (e.response?.data?.message || e.message))
    }
  }

  // ── Build Map flow handlers ─────────────────────────────────────────────────
  const handleBuildMapComplete = (result) => {
    setBuildMapResult(result)
    setBuildMapStep('preview')
    // If result includes preview data, use it
    if (result.preview || result.output) {
      setBuildMapPreview(result.preview || result.output)
    }
  }

  const handleBuildMapApprove = async () => {
    if (!buildMapResult) return
    const mapId = buildMapResult.mapId || buildMapResult.id
    if (mapId) {
      try {
        await submitMapFeedback(mapId, true, 'Approved from UI', null)
        toast.success('Map approved and saved')
      } catch (e) {
        // Non-critical — the map was already built
        toast.success('Map saved')
      }
    }
    setBuildMapOpen(false)
    setBuildMapStep('upload')
    setBuildMapResult(null)
    setBuildMapPreview(null)
    // Refresh maps list
    setMapsLoading(true)
    getAvailableMaps()
      .then(data => setMaps(Array.isArray(data) ? data : data?.maps || []))
      .catch(e => toast.error('Failed to refresh map list: ' + (e?.response?.data?.message || e?.message || 'unknown error')))
      .finally(() => setMapsLoading(false))
  }

  const handleBuildMapRefine = () => {
    setBuildMapStep('refine')
  }

  const handleBuildMapUpdated = (updatedMappings) => {
    if (buildMapResult) {
      setBuildMapResult(prev => ({
        ...prev,
        fieldMappings: updatedMappings,
        mappings: updatedMappings,
      }))
    }
  }

  const handleCloseBuildMap = () => {
    setBuildMapOpen(false)
    setBuildMapStep('upload')
    setBuildMapResult(null)
    setBuildMapPreview(null)
  }

  // Delete partner map
  const handleDeleteMap = (map) => {
    setDeleteMapTarget(map)
  }

  const confirmDeleteMap = async () => {
    if (!deleteMapTarget) return
    const mapId = deleteMapTarget.mapId || deleteMapTarget.id
    setDeleteMapTarget(null)
    try {
      await deleteMap(mapId)
      toast.success('Map deleted')
      if (selectedPartnerForMaps) loadPartnerMaps(selectedPartnerForMaps)
    } catch (e) {
      toast.error('Delete failed: ' + (e.response?.data?.message || e.message))
    }
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
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">EDI Converter v3.0</h1>
          <p className="text-secondary text-sm">11 formats, 66 conversion paths, AI-powered -- convert, explain, heal, diff, score, map, create</p>
        </div>
        <button
          onClick={() => { setBuildMapOpen(true); setBuildMapStep('upload'); setBuildMapResult(null) }}
          className="flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold transition-all"
          style={{
            background: 'linear-gradient(135deg, rgb(var(--accent)), #8b5cf6)',
            color: '#fff',
            boxShadow: '0 2px 12px rgb(var(--accent) / 0.3)',
          }}
        >
          <SparklesIcon className="w-4 h-4" />
          Build Map
        </button>
      </div>

      {/* Service-down warning banner — non-blocking so the user can still
          browse tabs while the converter comes back up. When readiness is
          DOWN, calls will fail fast and each panel's toast will explain why. */}
      {ediDown && (
        <div
          className="rounded-xl px-4 py-3 flex items-start gap-3"
          style={{
            background: 'rgba(245, 158, 11, 0.08)',
            border: '1px solid rgba(245, 158, 11, 0.35)',
          }}
        >
          <ExclamationTriangleIcon
            className="w-5 h-5 flex-shrink-0 mt-0.5"
            style={{ color: 'rgb(245, 158, 11)' }}
          />
          <div className="flex-1 text-xs" style={{ color: 'rgb(var(--tx-primary))' }}>
            <div className="font-semibold mb-0.5">EDI Converter service is not responding</div>
            <div style={{ color: 'rgb(148, 163, 184)' }}>
              Couldn't reach edi-converter (:8095) on its readiness probe. You can still
              browse tabs, but Convert / Maps / NL Create calls will fail until the service
              comes back. Start it with <code className="font-mono text-[11px] px-1 rounded" style={{ background: 'rgb(var(--border))' }}>docker compose up -d edi-converter</code>.
            </div>
          </div>
        </div>
      )}

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
          {/* Mode toggle + Filters */}
          <div className="card space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <h3 className="font-semibold text-primary">Conversion Maps</h3>
                {/* Standard / Partner toggle */}
                <div className="flex rounded-lg overflow-hidden" style={{ border: '1px solid rgb(var(--border))' }}>
                  <button
                    onClick={() => setMapMode('standard')}
                    className="text-xs px-3 py-1.5 font-medium transition-colors"
                    style={{
                      background: mapMode === 'standard' ? 'rgb(var(--accent) / 0.15)' : 'transparent',
                      color: mapMode === 'standard' ? 'rgb(var(--accent))' : 'rgb(var(--tx-secondary))',
                    }}>
                    Standard Maps
                  </button>
                  <button
                    onClick={() => setMapMode('partner')}
                    className="text-xs px-3 py-1.5 font-medium transition-colors"
                    style={{
                      background: mapMode === 'partner' ? 'rgb(var(--accent) / 0.15)' : 'transparent',
                      color: mapMode === 'partner' ? 'rgb(var(--accent))' : 'rgb(var(--tx-secondary))',
                      borderLeft: '1px solid rgb(var(--border))',
                    }}>
                    Partner Maps
                  </button>
                </div>
              </div>
              <div className="flex items-center gap-2">
                {mapMode === 'partner' && (
                  <button
                    onClick={() => { setCloneModalOpen(true); setMapEditorDef(null) }}
                    className="btn-sm text-xs font-medium"
                    style={{ background: '#4c1d9522', color: '#c084fc', border: '1px solid #4c1d9540' }}>
                    Clone from Standard
                  </button>
                )}
                <button onClick={() => {
                  setMapsLoading(true)
                  getAvailableMaps()
                    .then(data => setMaps(Array.isArray(data) ? data : data?.maps || []))
                    .catch(e => toast.error('Refresh failed: ' + (e.response?.data?.message || e.message)))
                    .finally(() => setMapsLoading(false))
                }} className="btn-sm bg-gray-600 text-white text-xs">Refresh</button>
              </div>
            </div>

            {/* Partner selector for partner mode */}
            {mapMode === 'partner' && (
              <div className="flex items-center gap-3">
                <label className="text-xs font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>Partner:</label>
                <input
                  value={selectedPartnerForMaps}
                  onChange={e => setSelectedPartnerForMaps(e.target.value)}
                  className="flex-1 max-w-xs text-sm rounded px-3 py-1.5"
                  style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
                  placeholder="Enter partner ID..."
                />
                <button
                  onClick={() => loadPartnerMaps(selectedPartnerForMaps)}
                  disabled={!selectedPartnerForMaps.trim() || partnerMapsLoading}
                  className="btn-primary text-xs px-3 py-1.5">
                  {partnerMapsLoading ? 'Loading...' : 'Load Maps'}
                </button>
              </div>
            )}

            {/* Filters (standard mode) */}
            {mapMode === 'standard' && (
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
            )}
          </div>

          {/* Standard maps view */}
          {mapMode === 'standard' && (
            <>
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
                    <div className="flex items-center gap-3">
                      <button onClick={() => setSelectedMap(null)} className="text-sm text-blue-600 hover:underline">&larr; Back</button>
                      <span className="font-semibold text-primary text-lg">{selectedMap.name || selectedMap.mapId}</span>
                      {selectedMap.category && (
                        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${CATEGORY_COLORS[selectedMap.category?.toUpperCase()] || 'bg-gray-100 text-gray-800'}`}>
                          {selectedMap.category}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2">
                      <button onClick={() => handleEditMap(selectedMap)}
                        className="btn-sm text-xs font-medium flex items-center gap-1.5"
                        style={{ background: 'rgb(var(--accent) / 0.15)', color: 'rgb(var(--accent))', border: '1px solid rgb(var(--accent) / 0.3)' }}>
                        {(selectedMap.category || '').toUpperCase() === 'STANDARD' ? 'View in Editor' : 'Edit in Editor'}
                      </button>
                      {(selectedMap.category || '').toUpperCase() === 'STANDARD' && (
                        <button onClick={() => { setMapEditorDef(selectedMap); setCloneModalOpen(true) }}
                          className="btn-sm text-xs font-medium"
                          style={{ background: '#4c1d9522', color: '#c084fc', border: '1px solid #4c1d9540' }}>
                          Clone for Partner
                        </button>
                      )}
                    </div>
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
                  {selectedMap.codeTables && (typeof selectedMap.codeTables === 'object' ? Object.keys(selectedMap.codeTables).length > 0 : selectedMap.codeTables.length > 0) && (
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

                  {/* Test panel */}
                  <div style={{ borderTop: '1px solid rgb(var(--border))', paddingTop: 16 }}>
                    <MapTestPanel
                      mapId={selectedMap.mapId || selectedMap.id}
                      sourceType={selectedMap.sourceType || selectedMap.sourceStandard}
                      targetType={selectedMap.targetType || selectedMap.targetStandard}
                    />
                  </div>

                  {/* Raw JSON fallback */}
                  <details className="text-xs">
                    <summary className="cursor-pointer text-secondary hover:text-primary">Raw map data</summary>
                    <pre className="bg-canvas rounded-lg p-3 overflow-auto font-mono max-h-64 mt-2">
                      {JSON.stringify(selectedMap, null, 2)}
                    </pre>
                  </details>
                </div>
              )}
            </>
          )}

          {/* Partner maps view */}
          {mapMode === 'partner' && (
            <>
              {partnerMapsLoading && <div className="text-center text-secondary py-8">Loading partner maps...</div>}

              {!partnerMapsLoading && !selectedPartnerForMaps && (
                <div className="text-center py-8" style={{ color: 'rgb(var(--tx-muted))' }}>
                  Enter a partner ID above and click "Load Maps" to view partner-specific maps.
                </div>
              )}

              {!partnerMapsLoading && selectedPartnerForMaps && partnerMaps.length === 0 && (
                <div className="text-center py-8" style={{ color: 'rgb(var(--tx-muted))' }}>
                  No maps found for partner "{selectedPartnerForMaps}". Clone a standard map to get started.
                </div>
              )}

              {!partnerMapsLoading && partnerMaps.length > 0 && (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                  {partnerMaps.map(m => (
                    <div key={m.mapId || m.id}
                      className="card space-y-2 transition-all"
                      style={{ border: '1px solid rgb(var(--border))' }}>
                      <div className="flex items-start justify-between">
                        <h4 className="font-medium text-primary text-sm truncate">{m.name || m.mapId || m.id}</h4>
                        <span
                          className="text-[10px] font-bold uppercase px-1.5 py-0.5 rounded"
                          style={{
                            background: (m.status === 'ACTIVE' || m.active) ? '#14532d22' : '#7f1d1d22',
                            color: (m.status === 'ACTIVE' || m.active) ? '#4ade80' : '#f87171',
                          }}>
                          {m.status || (m.active ? 'ACTIVE' : 'INACTIVE')}
                        </span>
                      </div>
                      <div className="flex items-center gap-1 text-xs">
                        <span className="px-1.5 py-0.5 rounded" style={{ background: '#78350f22', color: '#fbbf24' }}>
                          {m.sourceType || m.sourceStandard || '?'}
                        </span>
                        <span style={{ color: 'rgb(var(--tx-muted))' }}>&rarr;</span>
                        <span className="px-1.5 py-0.5 rounded" style={{ background: '#14532d22', color: '#4ade80' }}>
                          {m.targetType || m.targetStandard || '?'}
                        </span>
                      </div>
                      <div className="flex items-center gap-3 text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
                        {m.version && <span>v{m.version}</span>}
                        {m.confidence != null && <span>Conf: {m.confidence}%</span>}
                        {m.fieldCount != null && <span>{m.fieldCount} fields</span>}
                      </div>
                      {/* Action buttons */}
                      <div className="flex items-center gap-1.5 pt-1" style={{ borderTop: '1px solid rgb(var(--border))' }}>
                        <button onClick={() => handleEditMap(m)}
                          className="flex-1 text-xs py-1 rounded transition-colors"
                          style={{ background: 'rgb(var(--accent) / 0.1)', color: 'rgb(var(--accent))' }}>
                          Edit
                        </button>
                        <button onClick={() => handleToggleMapStatus(m)}
                          className="flex-1 text-xs py-1 rounded transition-colors"
                          style={{
                            background: (m.status === 'ACTIVE' || m.active) ? '#78350f15' : '#14532d15',
                            color: (m.status === 'ACTIVE' || m.active) ? '#fbbf24' : '#4ade80',
                          }}>
                          {(m.status === 'ACTIVE' || m.active) ? 'Deactivate' : 'Activate'}
                        </button>
                        <button onClick={() => handleDeleteMap(m)}
                          className="text-xs py-1 px-2 rounded transition-colors"
                          style={{ background: '#7f1d1d15', color: '#f87171' }}>
                          Delete
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Build Map full-screen modal */}
      {buildMapOpen && (
        <div className="fixed inset-0 z-50 flex flex-col" style={{ background: 'rgb(var(--canvas))' }}>
          {/* Header */}
          <div className="flex items-center justify-between px-6 py-4 flex-shrink-0"
            style={{ background: 'rgb(var(--surface))', borderBottom: '1px solid rgb(var(--border))' }}>
            <div className="flex items-center gap-3">
              <SparklesIcon className="w-5 h-5" style={{ color: 'rgb(var(--accent))' }} />
              <h2 className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
                Build Map from Samples
              </h2>
              {/* Step indicator */}
              <div className="flex items-center gap-1 ml-4">
                {['upload', 'preview', 'refine'].map((step, i) => (
                  <div key={step} className="flex items-center gap-1">
                    {i > 0 && <div className="w-6 h-px" style={{ background: 'rgb(var(--border))' }} />}
                    <div
                      className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full"
                      style={{
                        background: buildMapStep === step ? 'rgb(var(--accent) / 0.15)' : 'transparent',
                        color: buildMapStep === step ? 'rgb(var(--accent))' : 'rgb(var(--tx-muted))',
                        border: `1px solid ${buildMapStep === step ? 'rgb(var(--accent) / 0.3)' : 'rgb(var(--border))'}`,
                      }}
                    >
                      {step === 'upload' ? '1. Upload' : step === 'preview' ? '2. Review' : '3. Refine'}
                    </div>
                  </div>
                ))}
              </div>
            </div>
            <button onClick={handleCloseBuildMap}
              className="flex items-center gap-1.5 p-2 rounded-lg transition-colors"
              style={{ color: 'rgb(var(--tx-secondary))' }}>
              <XMarkIcon className="w-5 h-5" />
            </button>
          </div>

          {/* Body */}
          <div className="flex-1 overflow-auto">
            {/* Step 1: Upload */}
            {buildMapStep === 'upload' && (
              <div className="max-w-4xl mx-auto px-6 py-8">
                <SampleUploader
                  onBuildComplete={handleBuildMapComplete}
                  partnerId={convertPartnerId || undefined}
                />
              </div>
            )}

            {/* Step 2: Preview / Approve */}
            {buildMapStep === 'preview' && buildMapResult && (
              <div className="max-w-5xl mx-auto px-6 py-8 space-y-6">
                <div className="grid grid-cols-2 gap-6">
                  {/* Left: Map info + stats */}
                  <div className="space-y-4">
                    <h3 className="text-sm font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                      Map Summary
                    </h3>
                    <div className="grid grid-cols-2 gap-3">
                      {[
                        ['Name', buildMapResult.name || buildMapResult.mapId || 'Untitled'],
                        ['Source', buildMapResult.sourceType || buildMapResult.sourceStandard || 'Auto'],
                        ['Target', buildMapResult.targetType || buildMapResult.targetStandard || 'Auto'],
                        ['Fields', (buildMapResult.fieldMappings || buildMapResult.mappings || []).length],
                        ['Loops', buildMapResult.loopMappings?.length || 0],
                        ['Confidence', (buildMapResult.confidence || buildMapResult.overallConfidence || 0) + '%'],
                      ].map(([label, val]) => (
                        <div key={label} className="rounded-lg p-3"
                          style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))' }}>
                          <div className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                            {label}
                          </div>
                          <div className="text-sm font-semibold mt-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
                            {val}
                          </div>
                        </div>
                      ))}
                    </div>

                    {/* Actions */}
                    <div className="flex items-center gap-3 pt-4">
                      <button onClick={handleBuildMapApprove}
                        className="btn-primary flex-1 py-2.5 text-sm font-semibold">
                        Approve & Save
                      </button>
                      <button onClick={handleBuildMapRefine}
                        className="flex-1 py-2.5 text-sm font-medium rounded-lg transition-colors"
                        style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--accent))', border: '1px solid rgb(var(--accent) / 0.3)' }}>
                        Refine with Assistant
                      </button>
                    </div>
                  </div>

                  {/* Right: Preview */}
                  <div>
                    <h3 className="text-sm font-bold uppercase tracking-wider mb-4" style={{ color: 'rgb(var(--tx-muted))' }}>
                      Output Preview
                    </h3>
                    <MapPreview
                      preview={buildMapPreview || buildMapResult.preview || buildMapResult.output || buildMapResult}
                      lowConfidenceFields={
                        (buildMapResult.fieldMappings || buildMapResult.mappings || [])
                          .filter(m => (m.confidence ?? 100) < 70)
                          .map(m => m.targetPath || m.target)
                      }
                    />
                  </div>
                </div>
              </div>
            )}

            {/* Step 3: Refine with chat */}
            {buildMapStep === 'refine' && buildMapResult && (
              <div className="flex h-full" style={{ minHeight: 'calc(100vh - 72px)' }}>
                {/* Left: Map preview / editor */}
                <div className="flex-1 overflow-auto p-6">
                  <h3 className="text-sm font-bold uppercase tracking-wider mb-4" style={{ color: 'rgb(var(--tx-muted))' }}>
                    Map Preview
                  </h3>
                  <MapPreview
                    preview={buildMapPreview || buildMapResult.preview || buildMapResult.output || buildMapResult}
                    lowConfidenceFields={
                      (buildMapResult.fieldMappings || buildMapResult.mappings || [])
                        .filter(m => (m.confidence ?? 100) < 70)
                        .map(m => m.targetPath || m.target)
                    }
                  />
                  <div className="mt-6 flex items-center gap-3">
                    <button onClick={handleBuildMapApprove}
                      className="btn-primary px-6 py-2.5 text-sm font-semibold">
                      Approve & Save
                    </button>
                    <button onClick={() => setBuildMapStep('preview')}
                      className="text-sm px-4 py-2.5 rounded-lg transition-colors"
                      style={{ color: 'rgb(var(--tx-secondary))' }}>
                      Back to Review
                    </button>
                  </div>
                </div>
                {/* Right: Chat panel */}
                <div className="w-[420px] flex-shrink-0 flex flex-col"
                  style={{ borderLeft: '1px solid rgb(var(--border))' }}>
                  <MapAssistant
                    mapId={buildMapResult.mapId || buildMapResult.id || 'draft'}
                    currentMappings={buildMapResult.fieldMappings || buildMapResult.mappings || []}
                    onMapUpdated={handleBuildMapUpdated}
                    sampleInput={null}
                  />
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Map Editor full-screen modal */}
      {mapEditorOpen && (
        <div className="fixed inset-0 z-50 flex flex-col" style={{ background: 'rgb(var(--canvas))' }}>
          {/* Modal header */}
          <div className="flex items-center justify-between px-4 py-3 flex-shrink-0"
            style={{ background: 'rgb(var(--surface))', borderBottom: '1px solid rgb(var(--border))' }}>
            <div className="flex items-center gap-3">
              <h2 className="text-sm font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
                {mapEditorReadOnly ? 'View Map' : 'Edit Map'}
              </h2>
              <span className="text-xs font-mono" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono', monospace" }}>
                {mapEditorDef?.mapId || mapEditorDef?.id || ''}
              </span>
            </div>
            <div className="flex items-center gap-2">
              {mapEditorReadOnly && (
                <button
                  onClick={() => { setCloneModalOpen(true) }}
                  className="btn-sm text-xs font-medium"
                  style={{ background: '#4c1d9522', color: '#c084fc', border: '1px solid #4c1d9540' }}>
                  Clone for Partner
                </button>
              )}
              <button onClick={() => setMapEditorOpen(false)}
                className="p-1.5 rounded-lg transition-colors"
                style={{ color: 'rgb(var(--tx-secondary))' }}>
                <span className="text-xs font-medium mr-1">Close</span>
                <span className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>ESC</span>
              </button>
            </div>
          </div>

          {/* Editor body */}
          <div className="flex-1 flex overflow-hidden">
            <div className="flex-1 overflow-auto">
              <MapEditor
                mapDefinition={mapEditorDef}
                onSave={handleMapSave}
                readOnly={mapEditorReadOnly}
              />
            </div>
            {/* Side panels: Test + Assistant */}
            <div className="w-96 flex-shrink-0 flex flex-col overflow-hidden"
              style={{ borderLeft: '1px solid rgb(var(--border))', background: 'rgb(var(--surface))' }}>
              {/* Test panel */}
              <div className="overflow-y-auto p-4" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
                <MapTestPanel
                  mapId={mapEditorDef?.mapId || mapEditorDef?.id}
                  sourceType={mapEditorDef?.sourceType || mapEditorDef?.sourceStandard}
                  targetType={mapEditorDef?.targetType || mapEditorDef?.targetStandard}
                />
              </div>
              {/* Assistant chat panel */}
              <div className="flex-1 min-h-0">
                <MapAssistant
                  mapId={mapEditorDef?.mapId || mapEditorDef?.id || 'draft'}
                  currentMappings={mapEditorDef?.fieldMappings || mapEditorDef?.mappings || []}
                  onMapUpdated={(updatedMappings) => {
                    setMapEditorDef(prev => prev ? { ...prev, fieldMappings: updatedMappings, mappings: updatedMappings } : prev)
                  }}
                  sampleInput={null}
                />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Clone modal */}
      {cloneModalOpen && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-4" style={{ background: 'rgba(0,0,0,0.6)' }}>
          <div className="rounded-xl shadow-xl w-full max-w-md flex flex-col"
            style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}>
            <div className="flex items-center justify-between p-4" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
              <h3 className="text-sm font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>Clone Map for Partner</h3>
              <button onClick={() => { setCloneModalOpen(false); setClonePartnerName(''); setCloneMapName('') }}
                className="p-1 rounded-lg transition-colors" style={{ color: 'rgb(var(--tx-muted))' }}>
                <span className="text-lg leading-none">&times;</span>
              </button>
            </div>
            <div className="p-4 space-y-3">
              <div>
                <label className="text-xs font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>Source Map</label>
                <div className="text-xs font-mono mt-1 px-2 py-1.5 rounded"
                  style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono', monospace" }}>
                  {mapEditorDef?.name || mapEditorDef?.mapId || selectedMap?.name || selectedMap?.mapId || '—'}
                </div>
              </div>
              <div>
                <label className="text-xs font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>Partner ID / Name</label>
                <input
                  value={clonePartnerName}
                  onChange={e => setClonePartnerName(e.target.value)}
                  className="w-full text-sm mt-1 rounded px-3 py-1.5"
                  style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
                  placeholder="e.g. acme-corp"
                  autoFocus
                />
              </div>
              <div>
                <label className="text-xs font-medium" style={{ color: 'rgb(var(--tx-secondary))' }}>Map Name (optional)</label>
                <input
                  value={cloneMapName}
                  onChange={e => setCloneMapName(e.target.value)}
                  className="w-full text-sm mt-1 rounded px-3 py-1.5"
                  style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-primary))', border: '1px solid rgb(var(--border))' }}
                  placeholder="Custom name for the cloned map..."
                />
              </div>
            </div>
            <div className="flex items-center justify-end gap-2 p-4" style={{ borderTop: '1px solid rgb(var(--border))' }}>
              <button onClick={() => { setCloneModalOpen(false); setClonePartnerName(''); setCloneMapName('') }}
                className="text-xs px-3 py-1.5 rounded"
                style={{ color: 'rgb(var(--tx-secondary))' }}>
                Cancel
              </button>
              <button onClick={handleCloneMap} disabled={cloning || !clonePartnerName.trim()}
                className="btn-primary text-xs px-4 py-1.5">
                {cloning ? 'Cloning...' : 'Clone & Edit'}
              </button>
            </div>
          </div>
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

      <ConfirmDialog
        open={!!deleteMapTarget}
        variant="danger"
        title="Delete map?"
        message={deleteMapTarget ? `Delete map "${deleteMapTarget.name || deleteMapTarget.mapId || deleteMapTarget.id}"? This cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={confirmDeleteMap}
        onCancel={() => setDeleteMapTarget(null)}
      />
    </div>
  )
}
