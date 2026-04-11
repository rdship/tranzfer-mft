import { useState, useEffect, useMemo, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import toast from 'react-hot-toast'
import {
  PlusIcon, ArrowPathIcon, MagnifyingGlassIcon,
  DocumentDuplicateIcon, CheckCircleIcon,
} from '@heroicons/react/24/outline'
import { getAvailableMaps, getMapDetail, updateMap } from '../api/ediConverter'
import MapEditor from '../components/MapEditor'
import MapTestPanel from '../components/MapTestPanel'
import MapAssistant from '../components/MapAssistant'
import ServiceUnavailable from '../components/ServiceUnavailable'

const CATEGORY_ORDER = ['STANDARD', 'TRAINED', 'PARTNER']

const CATEGORY_LABELS = {
  STANDARD: 'Standard',
  TRAINED:  'Trained',
  PARTNER:  'Partner',
  OTHER:    'Other',
}

const CATEGORY_COLORS = {
  STANDARD: { bg: '#1e3a8a22', fg: '#60a5fa', border: '#1e3a8a40' },
  TRAINED:  { bg: '#14532d22', fg: '#4ade80', border: '#14532d40' },
  PARTNER:  { bg: '#4c1d9522', fg: '#c084fc', border: '#4c1d9540' },
  OTHER:    { bg: '#37415122', fg: '#9ca3af', border: '#37415140' },
}

const mapKey = (m) => m?.mapId || m?.id || ''
const mapCategory = (m) => {
  const c = (m?.category || '').toUpperCase()
  return CATEGORY_ORDER.includes(c) ? c : 'OTHER'
}

export default function MapBuilder() {
  const [searchParams, setSearchParams] = useSearchParams()
  const urlMapId = searchParams.get('mapId') || ''

  // List state
  const [maps, setMaps] = useState([])
  const [mapsLoading, setMapsLoading] = useState(false)
  const [mapsError, setMapsError] = useState(null)
  const [search, setSearch] = useState('')

  // Selected map state
  const [selectedMapId, setSelectedMapId] = useState(urlMapId)
  const [mapDef, setMapDef] = useState(null)
  const [mapDefLoading, setMapDefLoading] = useState(false)
  const [isNewMap, setIsNewMap] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)

  // ── Load the map list ──────────────────────────────────────────────────────
  const loadMaps = useCallback(() => {
    setMapsLoading(true)
    setMapsError(null)
    getAvailableMaps()
      .then(data => {
        const list = Array.isArray(data) ? data : data?.maps || []
        setMaps(list)
      })
      .catch(e => {
        const msg = e?.response?.data?.message || e?.message || 'Unknown error'
        setMapsError(msg)
        setMaps([])
      })
      .finally(() => setMapsLoading(false))
  }, [])

  useEffect(() => { loadMaps() }, [loadMaps])

  // ── Load selected map detail ───────────────────────────────────────────────
  const loadMapDetail = useCallback(async (mapId) => {
    if (!mapId) return
    setMapDefLoading(true)
    try {
      const detail = await getMapDetail(mapId)
      setMapDef(detail)
      setIsNewMap(false)
      setDirty(false)
    } catch (e) {
      toast.error('Failed to load map: ' + (e?.response?.data?.message || e?.message))
      setMapDef(null)
    } finally {
      setMapDefLoading(false)
    }
  }, [])

  // Auto-load map from URL param or selection
  useEffect(() => {
    if (selectedMapId) {
      loadMapDetail(selectedMapId)
    } else {
      setMapDef(null)
      setIsNewMap(false)
      setDirty(false)
    }
  }, [selectedMapId, loadMapDetail])

  // Keep URL in sync with selection (replace mode — no history spam)
  useEffect(() => {
    const current = searchParams.get('mapId') || ''
    if (selectedMapId && selectedMapId !== current) {
      setSearchParams({ mapId: selectedMapId }, { replace: true })
    } else if (!selectedMapId && current) {
      const next = new URLSearchParams(searchParams)
      next.delete('mapId')
      setSearchParams(next, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedMapId])

  // ── Handlers ───────────────────────────────────────────────────────────────
  const handleSelectMap = (m) => {
    const id = mapKey(m)
    if (!id) return
    if (dirty && !confirm('You have unsaved changes. Discard and switch maps?')) return
    setSelectedMapId(id)
  }

  const handleNewMap = () => {
    if (dirty && !confirm('You have unsaved changes. Discard and start a new map?')) return
    setSelectedMapId('')
    setIsNewMap(true)
    setMapDef({
      mapId: '',
      name: 'Untitled Map',
      category: 'PARTNER',
      sourceType: '',
      targetType: '',
      fieldMappings: [],
      loopMappings: [],
      codeTables: {},
      sourceFields: [],
      targetFields: [],
    })
    setDirty(true)
  }

  const handleMapSave = async (updatedDef) => {
    const id = updatedDef?.mapId || updatedDef?.id
    if (!id) {
      toast.error('Cannot save: map has no id yet. Use "Build Map from Samples" for new maps.')
      return
    }
    setSaving(true)
    try {
      await updateMap(id, updatedDef)
      toast.success('Map saved')
      setMapDef(updatedDef)
      setDirty(false)
      setIsNewMap(false)
      // Refresh the browser list so counts / names stay current
      loadMaps()
    } catch (e) {
      toast.error('Save failed: ' + (e?.response?.data?.message || e?.message))
    } finally {
      setSaving(false)
    }
  }

  const handleHeaderSave = () => {
    if (!mapDef) return
    handleMapSave(mapDef)
  }

  // ── Filtering + grouping ───────────────────────────────────────────────────
  const filteredGrouped = useMemo(() => {
    const q = search.trim().toLowerCase()
    const filtered = maps.filter(m => {
      if (!q) return true
      const hay = [
        m.name, m.mapId, m.id, m.sourceType, m.targetType,
        m.sourceStandard, m.targetStandard, m.description,
      ].filter(Boolean).join(' ').toLowerCase()
      return hay.includes(q)
    })
    const groups = { STANDARD: [], TRAINED: [], PARTNER: [], OTHER: [] }
    for (const m of filtered) groups[mapCategory(m)].push(m)
    return groups
  }, [maps, search])

  const totalFiltered = useMemo(
    () => Object.values(filteredGrouped).reduce((a, b) => a + b.length, 0),
    [filteredGrouped]
  )

  const isStandardMap = (mapDef?.category || '').toUpperCase() === 'STANDARD'
  const canSave = !!mapDef && !isStandardMap && (dirty || isNewMap) && !saving

  // ── Service-unavailable state ──────────────────────────────────────────────
  // Uses the shared ServiceUnavailable primitive so the card matches every
  // other backend-down screen across the admin UI (same copy, same retry UX).
  if (mapsError && maps.length === 0 && !mapsLoading) {
    return (
      <div className="h-full w-full flex items-center justify-center" style={{ background: 'rgb(var(--canvas))' }}>
        <ServiceUnavailable
          service="edi-converter"
          port={8095}
          error={mapsError}
          onRetry={loadMaps}
          title="EDI Map Builder unavailable"
          hint="Couldn't load maps from edi-converter (:8095). Start the service with `docker compose up -d edi-converter`, then retry."
        />
      </div>
    )
  }

  // ── Main layout ────────────────────────────────────────────────────────────
  return (
    <div className="h-full w-full flex flex-col overflow-hidden" style={{ background: 'rgb(var(--canvas))' }}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 flex-shrink-0"
        style={{ background: 'rgb(var(--surface))', borderBottom: '1px solid rgb(var(--border))' }}>
        <div className="flex items-center gap-3">
          <h1 className="text-sm font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-primary))' }}>
            EDI Map Builder
          </h1>
          {mapDef && (
            <span className="text-xs font-mono" style={{ color: 'rgb(var(--tx-muted))', fontFamily: "'JetBrains Mono', monospace" }}>
              {mapDef.name || mapDef.mapId || mapDef.id || 'untitled'}
            </span>
          )}
          {isStandardMap && (
            <span className="text-xs px-2 py-0.5 rounded-full font-medium"
              style={{ background: CATEGORY_COLORS.STANDARD.bg, color: CATEGORY_COLORS.STANDARD.fg, border: `1px solid ${CATEGORY_COLORS.STANDARD.border}` }}>
              Read-only
            </span>
          )}
          {dirty && !isStandardMap && (
            <span className="text-xs px-2 py-0.5 rounded-full font-medium"
              style={{ background: '#78350f22', color: '#fbbf24', border: '1px solid #78350f40' }}>
              Unsaved
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleNewMap}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors"
            style={{ background: '#14532d22', color: '#4ade80', border: '1px solid #14532d40' }}>
            <PlusIcon className="w-4 h-4" />
            New Map
          </button>
          <button
            onClick={handleHeaderSave}
            disabled={!canSave}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            style={{ background: '#1e3a8a22', color: '#60a5fa', border: '1px solid #1e3a8a40' }}>
            <CheckCircleIcon className="w-4 h-4" />
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {/* Body — 3 columns */}
      <div className="flex-1 flex overflow-hidden min-h-0">
        {/* ── Left: Map Browser ─────────────────────────────────────────── */}
        <aside className="w-60 flex-shrink-0 flex flex-col overflow-hidden"
          style={{ background: 'rgb(var(--surface))', borderRight: '1px solid rgb(var(--border))' }}>
          <div className="p-3 flex-shrink-0" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
            <div className="relative">
              <MagnifyingGlassIcon className="w-4 h-4 absolute left-2.5 top-1/2 -translate-y-1/2"
                style={{ color: 'rgb(var(--tx-muted))' }} />
              <input
                type="text"
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Search maps…"
                className="w-full pl-8 pr-2 py-1.5 text-xs rounded-md"
                style={{
                  background: 'rgb(var(--canvas))',
                  border: '1px solid rgb(var(--border))',
                  color: 'rgb(var(--tx-primary))',
                }}
              />
            </div>
            <div className="flex items-center justify-between mt-2">
              <span className="text-[10px] uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
                {mapsLoading ? 'Loading…' : `${totalFiltered} / ${maps.length} maps`}
              </span>
              <button
                onClick={loadMaps}
                className="p-1 rounded hover:bg-white/5"
                title="Refresh"
                style={{ color: 'rgb(var(--tx-muted))' }}>
                <ArrowPathIcon className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto">
            {mapsLoading && maps.length === 0 && (
              <div className="p-4 text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>
                Loading maps…
              </div>
            )}
            {!mapsLoading && totalFiltered === 0 && (
              <div className="p-4 text-xs text-center" style={{ color: 'rgb(var(--tx-muted))' }}>
                {search ? 'No maps match your search.' : 'No maps yet. Click "New Map" to start.'}
              </div>
            )}
            {CATEGORY_ORDER.concat('OTHER').map(cat => {
              const list = filteredGrouped[cat] || []
              if (list.length === 0) return null
              const color = CATEGORY_COLORS[cat] || CATEGORY_COLORS.OTHER
              return (
                <div key={cat} className="py-1">
                  <div className="px-3 py-1.5 flex items-center gap-2">
                    <span className="text-[10px] uppercase tracking-wider font-semibold"
                      style={{ color: color.fg }}>
                      {CATEGORY_LABELS[cat]}
                    </span>
                    <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                      {list.length}
                    </span>
                  </div>
                  {list.map(m => {
                    const id = mapKey(m)
                    const active = id === selectedMapId
                    return (
                      <button
                        key={id || m.name}
                        onClick={() => handleSelectMap(m)}
                        className="w-full text-left px-3 py-1.5 text-xs flex items-center gap-2 transition-colors"
                        style={{
                          background: active ? 'rgb(var(--canvas))' : 'transparent',
                          borderLeft: active ? `2px solid ${color.fg}` : '2px solid transparent',
                          color: active ? 'rgb(var(--tx-primary))' : 'rgb(var(--tx-secondary))',
                        }}>
                        <DocumentDuplicateIcon className="w-3.5 h-3.5 flex-shrink-0" style={{ color: color.fg }} />
                        <span className="truncate">{m.name || id}</span>
                      </button>
                    )
                  })}
                </div>
              )
            })}
          </div>
        </aside>

        {/* ── Center: MapEditor ─────────────────────────────────────────── */}
        <main className="flex-1 overflow-auto min-w-0">
          {mapDefLoading && (
            <div className="h-full flex items-center justify-center text-xs"
              style={{ color: 'rgb(var(--tx-muted))' }}>
              Loading map…
            </div>
          )}
          {!mapDefLoading && !mapDef && (
            <div className="h-full flex items-center justify-center p-8">
              <div className="max-w-md w-full text-center p-8 rounded-lg"
                style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}>
                <DocumentDuplicateIcon className="w-12 h-12 mx-auto mb-4" style={{ color: 'rgb(var(--tx-muted))' }} />
                <h2 className="text-sm font-bold mb-2" style={{ color: 'rgb(var(--tx-primary))' }}>
                  No map selected
                </h2>
                <p className="text-xs mb-6" style={{ color: 'rgb(var(--tx-secondary))' }}>
                  Select a map from the left to view / edit, or click <span className="font-semibold">New Map</span> to start from scratch.
                </p>
                <button
                  onClick={handleNewMap}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium"
                  style={{ background: '#14532d22', color: '#4ade80', border: '1px solid #14532d40' }}>
                  <PlusIcon className="w-4 h-4" />
                  New Map
                </button>
              </div>
            </div>
          )}
          {!mapDefLoading && mapDef && (
            <MapEditor
              key={mapKey(mapDef) || 'draft'}
              mapDefinition={mapDef}
              onSave={handleMapSave}
              readOnly={isStandardMap}
            />
          )}
        </main>

        {/* ── Right: Test + Assistant ───────────────────────────────────── */}
        <aside className="w-80 flex-shrink-0 flex flex-col overflow-hidden"
          style={{ background: 'rgb(var(--surface))', borderLeft: '1px solid rgb(var(--border))' }}>
          <div className="overflow-y-auto p-4" style={{ borderBottom: '1px solid rgb(var(--border))' }}>
            <MapTestPanel
              mapId={mapDef?.mapId || mapDef?.id}
              sourceType={mapDef?.sourceType || mapDef?.sourceStandard}
              targetType={mapDef?.targetType || mapDef?.targetStandard}
            />
          </div>
          <div className="flex-1 min-h-0">
            <MapAssistant
              mapId={mapDef?.mapId || mapDef?.id || 'draft'}
              currentMappings={mapDef?.fieldMappings || mapDef?.mappings || []}
              onMapUpdated={(updatedMappings) => {
                setMapDef(prev => prev ? { ...prev, fieldMappings: updatedMappings, mappings: updatedMappings } : prev)
                setDirty(true)
              }}
              sampleInput={null}
            />
          </div>
        </aside>
      </div>
    </div>
  )
}
