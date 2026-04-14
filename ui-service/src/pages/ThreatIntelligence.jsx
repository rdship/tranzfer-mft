import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { formatDistanceToNow, format } from 'date-fns'
import toast from 'react-hot-toast'
import {
  ShieldExclamationIcon, EyeIcon, MagnifyingGlassIcon,
  MapIcon, PlusIcon, TrashIcon, XMarkIcon, PlayIcon,
  ChevronRightIcon, ArrowPathIcon, ExclamationTriangleIcon,
  GlobeAltIcon, BoltIcon, FingerPrintIcon, ClockIcon,
  FunnelIcon, DocumentMagnifyingGlassIcon, LinkIcon
} from '@heroicons/react/24/outline'
import * as threatApi from '../api/threats'

// ─── Constants ───────────────────────────────────────────────────────────────

const TABS = [
  { key: 'overview',   label: 'Overview',       icon: ShieldExclamationIcon },
  { key: 'indicators', label: 'Indicators',     icon: FingerPrintIcon },
  { key: 'hunting',    label: 'Threat Hunting',  icon: MagnifyingGlassIcon },
  { key: 'mitre',      label: 'MITRE ATT&CK',  icon: MapIcon },
  { key: 'chains',     label: 'Attack Chains',  icon: LinkIcon },
]

const THREAT_LEVEL_CONFIG = {
  CRITICAL: { color: 'text-red-400',    bg: 'bg-red-500',    border: 'border-red-500/40',  label: 'Critical' },
  HIGH:     { color: 'text-orange-400',  bg: 'bg-orange-500', border: 'border-orange-500/40', label: 'High' },
  MEDIUM:   { color: 'text-yellow-400',  bg: 'bg-yellow-500', border: 'border-yellow-500/40', label: 'Medium' },
  LOW:      { color: 'text-green-400',   bg: 'bg-green-500',  border: 'border-green-500/40',  label: 'Low' },
}

const SEV_BADGE = {
  CRITICAL: 'badge badge-red', HIGH: 'badge badge-red',
  MEDIUM: 'badge badge-yellow', LOW: 'badge badge-green', INFO: 'badge badge-green',
}

const IOC_TYPES = ['IP', 'DOMAIN', 'HASH', 'EMAIL', 'URL', 'CVE']
const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

const BLANK_INDICATOR = {
  type: 'IP', value: '', confidence: 75, severity: 'MEDIUM', source: '', description: ''
}

// ─── Shared Components ───────────────────────────────────────────────────────

function Spinner() {
  return (
    <div className="flex justify-center py-12">
      <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
    </div>
  )
}

function EmptyState({ icon: Icon, title, subtitle }) {
  return (
    <div className="card text-center py-12">
      <Icon className="w-10 h-10 mx-auto mb-3 text-muted" />
      <div className="text-gray-300 font-medium">{title}</div>
      {subtitle && <div className="text-muted text-sm mt-1">{subtitle}</div>}
    </div>
  )
}

function StatCard({ label, value, color = 'text-white', icon: Icon }) {
  return (
    <div className="card-sm">
      <div className="flex items-center justify-between mb-1">
        {Icon && <Icon className="w-4 h-4 text-muted" />}
        <span className="text-muted text-xs">{label}</span>
      </div>
      <div className={`text-2xl font-bold ${color}`}>{value ?? 0}</div>
    </div>
  )
}

// ─── Tab 1: Overview ─────────────────────────────────────────────────────────

function OverviewTab() {
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['threat-dashboard'],
    queryFn: threatApi.getThreatDashboard,
    meta: { silent: true }, refetchInterval: 30000,
  })
  const { data: incidents } = useQuery({
    queryKey: ['threat-incidents'],
    queryFn: threatApi.getIncidents,
    meta: { silent: true }, refetchInterval: 30000,
  })
  const { data: actors } = useQuery({
    queryKey: ['threat-actors'],
    queryFn: threatApi.getThreatActors,
    meta: { silent: true }, refetchInterval: 60000,
  })
  const { data: chains } = useQuery({
    queryKey: ['threat-chains-overview'],
    queryFn: threatApi.getAttackChains,
    meta: { silent: true }, refetchInterval: 60000,
  })

  if (isLoading) return <Spinner />

  const threatLevel = dashboard?.threatLevel || 'LOW'
  const cfg = THREAT_LEVEL_CONFIG[threatLevel] || THREAT_LEVEL_CONFIG.LOW
  const indicators = dashboard?.activeIndicators ?? dashboard?.totalIndicators ?? 0
  const chainCount = chains?.length ?? dashboard?.attackChains ?? 0
  const actorCount = actors?.length ?? dashboard?.threatActors ?? 0
  const geoData = dashboard?.geoDistribution || dashboard?.topCountries || []
  const recentIncidents = (incidents || dashboard?.recentIncidents || []).slice(0, 10)

  return (
    <div className="space-y-6">
      {/* Threat level gauge + stats */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className={`md:col-span-1 card flex flex-col items-center justify-center ${cfg.border}`}>
          <div className={`w-20 h-20 rounded-full border-4 ${cfg.border} flex items-center justify-center mb-3`}>
            <ShieldExclamationIcon className={`w-10 h-10 ${cfg.color}`} />
          </div>
          <div className={`text-2xl font-bold ${cfg.color}`}>{cfg.label}</div>
          <div className="text-muted text-xs mt-1">Threat Level</div>
          <div className="w-full bg-gray-700 rounded-full h-2 mt-3">
            <div className={`${cfg.bg} h-2 rounded-full transition-all`}
                 style={{ width: `${threatLevel === 'CRITICAL' ? 100 : threatLevel === 'HIGH' ? 75 : threatLevel === 'MEDIUM' ? 50 : 25}%` }} />
          </div>
        </div>
        <div className="md:col-span-4 grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Active Indicators" value={indicators} color="text-blue-400" icon={FingerPrintIcon} />
          <StatCard label="Attack Chains" value={chainCount} color="text-orange-400" icon={LinkIcon} />
          <StatCard label="Threat Actors" value={actorCount} color="text-red-400" icon={ExclamationTriangleIcon} />
          <StatCard label="Open Incidents" value={recentIncidents.filter(i => i.status === 'OPEN' || i.status === 'ACTIVE').length || recentIncidents.length}
                    color="text-yellow-400" icon={BoltIcon} />
        </div>
      </div>

      {/* Recent incidents */}
      <div className="card p-0 overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-700/60">
          <h3 className="text-gray-300 text-sm font-medium">Recent Incidents</h3>
        </div>
        <div className="divide-y divide-gray-700/40">
          {recentIncidents.length > 0 ? recentIncidents.map((inc, i) => (
            <div key={inc.id || i} className="px-5 py-3 flex items-center gap-3 hover:bg-gray-800/40">
              <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                (inc.severity === 'CRITICAL' || inc.severity === 'HIGH') ? 'bg-red-500' :
                inc.severity === 'MEDIUM' ? 'bg-yellow-500' : 'bg-green-500'
              }`} />
              <span className={SEV_BADGE[inc.severity] || 'badge badge-green'}>{inc.severity || 'INFO'}</span>
              <span className="text-gray-300 text-sm flex-1 truncate">{inc.title || inc.description || inc.type || 'Incident'}</span>
              {inc.source && <span className="text-muted text-xs">{inc.source}</span>}
              <span className="text-muted text-xs flex-shrink-0">
                {inc.createdAt ? formatDistanceToNow(new Date(inc.createdAt), { addSuffix: true }) : ''}
              </span>
            </div>
          )) : (
            <div className="px-5 py-8 text-center text-muted">
              <ShieldExclamationIcon className="w-8 h-8 mx-auto mb-2 text-green-500" />
              No recent incidents
            </div>
          )}
        </div>
      </div>

      {/* Geo distribution */}
      {geoData.length > 0 && (
        <div className="card">
          <h3 className="text-gray-300 text-sm font-medium mb-4">Threat Activity by Country</h3>
          <div className="space-y-2">
            {geoData.slice(0, 10).map((g, i) => {
              const country = g.country || g.countryCode || g.name || 'Unknown'
              const count = g.count || g.threats || g.value || 0
              const maxCount = geoData[0]?.count || geoData[0]?.threats || geoData[0]?.value || 1
              return (
                <div key={i} className="flex items-center gap-3">
                  <GlobeAltIcon className="w-4 h-4 text-muted flex-shrink-0" />
                  <span className="text-gray-300 text-sm w-28 truncate">{country}</span>
                  <div className="flex-1 bg-gray-700 rounded-full h-2">
                    <div className="bg-blue-500 h-2 rounded-full transition-all"
                         style={{ width: `${Math.max(5, (count / maxCount) * 100)}%` }} />
                  </div>
                  <span className="text-muted text-xs w-12 text-right">{count}</span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* Tracked threat actors */}
      {actors?.length > 0 && (
        <div className="card">
          <h3 className="text-gray-300 text-sm font-medium mb-4">Tracked Threat Actors</h3>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {actors.slice(0, 6).map((actor, i) => (
              <div key={actor.id || i} className="card-sm">
                <div className="flex items-center gap-2 mb-2">
                  <ExclamationTriangleIcon className={`w-4 h-4 ${
                    actor.severity === 'CRITICAL' ? 'text-red-400' :
                    actor.severity === 'HIGH' ? 'text-orange-400' : 'text-yellow-400'
                  }`} />
                  <span className="text-gray-200 font-medium text-sm truncate">{actor.name || actor.alias || 'Unknown Actor'}</span>
                </div>
                {actor.motivation && <p className="text-muted text-xs mb-1">Motivation: {actor.motivation}</p>}
                {actor.ttps?.length > 0 && (
                  <div className="flex flex-wrap gap-1 mt-2">
                    {actor.ttps.slice(0, 3).map((ttp, j) => (
                      <span key={j} className="badge badge-yellow">{ttp}</span>
                    ))}
                  </div>
                )}
                {actor.lastSeen && (
                  <div className="text-muted text-xs mt-2">
                    Last seen: {formatDistanceToNow(new Date(actor.lastSeen), { addSuffix: true })}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Tab 2: Indicators (IOCs) ────────────────────────────────────────────────

function IndicatorsTab() {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [sevFilter, setSevFilter] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState(BLANK_INDICATOR)
  const [confirmDelete, setConfirmDelete] = useState(null)

  const params = useMemo(() => {
    const p = {}
    if (search) p.search = search
    if (typeFilter) p.type = typeFilter
    if (sevFilter) p.severity = sevFilter
    return p
  }, [search, typeFilter, sevFilter])

  const { data, isLoading } = useQuery({
    queryKey: ['threat-indicators', params],
    queryFn: () => threatApi.getIndicators(params),
    meta: { silent: true }, refetchInterval: 20000,
  })

  const createMutation = useMutation({
    mutationFn: threatApi.createIndicator,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['threat-indicators'] })
      setShowCreate(false)
      setForm(BLANK_INDICATOR)
      toast.success('Indicator created')
    },
    onError: (err) => toast.error(err?.response?.data?.error || 'Failed to create indicator'),
  })

  const deleteMutation = useMutation({
    mutationFn: threatApi.deleteIndicator,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['threat-indicators'] })
      setConfirmDelete(null)
      toast.success('Indicator deleted')
    },
    onError: (err) => {
      toast.error(err?.response?.data?.error || 'Failed to delete indicator')
      setConfirmDelete(null)
    },
  })

  const indicators = Array.isArray(data) ? data : data?.content || data?.indicators || []

  return (
    <div className="space-y-4">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[200px]">
          <MagnifyingGlassIcon className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input type="text" value={search} onChange={e => setSearch(e.target.value)}
                 placeholder="Search indicators..."
                 className="w-full bg-gray-800 border border-gray-600 rounded-lg pl-9 pr-3 py-2 text-sm text-gray-200 placeholder-gray-500" />
        </div>
        <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)}
                className="bg-gray-800 text-gray-300 text-sm border border-gray-600 rounded-lg px-3 py-2">
          <option value="">Type: All</option>
          {IOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
        </select>
        <select value={sevFilter} onChange={e => setSevFilter(e.target.value)}
                className="bg-gray-800 text-gray-300 text-sm border border-gray-600 rounded-lg px-3 py-2">
          <option value="">Severity: All</option>
          {SEVERITIES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <button onClick={() => { setShowCreate(true); setForm(BLANK_INDICATOR) }}
                className="btn-primary flex items-center gap-1.5 text-sm">
          <PlusIcon className="w-4 h-4" /> Add IOC
        </button>
      </div>

      {/* Table */}
      {isLoading ? <Spinner /> : (
        <div className="card p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-700/60 text-muted text-xs">
                  <th className="px-4 py-3 text-left">Type</th>
                  <th className="px-4 py-3 text-left">Value</th>
                  <th className="px-4 py-3 text-left">Confidence</th>
                  <th className="px-4 py-3 text-left">Severity</th>
                  <th className="px-4 py-3 text-left">Source</th>
                  <th className="px-4 py-3 text-left">First Seen</th>
                  <th className="px-4 py-3 text-left">Last Seen</th>
                  <th className="px-4 py-3 text-left">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-700/40">
                {indicators.map((ioc) => (
                  <tr key={ioc.id} className="hover:bg-gray-800/40">
                    <td className="px-4 py-2.5">
                      <span className="badge badge-yellow">{ioc.type}</span>
                    </td>
                    <td className="px-4 py-2.5 text-gray-300 font-mono text-xs max-w-[240px] truncate">{ioc.value}</td>
                    <td className="px-4 py-2.5">
                      <div className="flex items-center gap-2">
                        <div className="w-16 bg-gray-700 rounded-full h-1.5">
                          <div className={`h-1.5 rounded-full ${ioc.confidence >= 80 ? 'bg-green-500' : ioc.confidence >= 50 ? 'bg-yellow-500' : 'bg-red-500'}`}
                               style={{ width: `${ioc.confidence || 0}%` }} />
                        </div>
                        <span className="text-muted text-xs">{ioc.confidence}%</span>
                      </div>
                    </td>
                    <td className="px-4 py-2.5">
                      <span className={SEV_BADGE[ioc.severity] || 'badge badge-green'}>{ioc.severity}</span>
                    </td>
                    <td className="px-4 py-2.5 text-muted text-xs">{ioc.source || '--'}</td>
                    <td className="px-4 py-2.5 text-muted text-xs">
                      {ioc.firstSeen ? format(new Date(ioc.firstSeen), 'MMM d, HH:mm') : '--'}
                    </td>
                    <td className="px-4 py-2.5 text-muted text-xs">
                      {ioc.lastSeen ? format(new Date(ioc.lastSeen), 'MMM d, HH:mm') : '--'}
                    </td>
                    <td className="px-4 py-2.5">
                      {confirmDelete === ioc.id ? (
                        <div className="flex items-center gap-1">
                          <button onClick={() => deleteMutation.mutate(ioc.id)}
                                  disabled={deleteMutation.isPending}
                                  className="text-xs px-2 py-0.5 bg-red-600/20 text-red-400 rounded hover:bg-red-600/30">
                            {deleteMutation.isPending ? '...' : 'Confirm'}
                          </button>
                          <button onClick={() => setConfirmDelete(null)}
                                  className="text-muted hover:text-gray-300">
                            <XMarkIcon className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      ) : (
                        <button onClick={() => setConfirmDelete(ioc.id)}
                                title="Delete indicator"
                                className="text-muted hover:text-red-400 transition-colors">
                          <TrashIcon className="w-4 h-4" />
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
                {indicators.length === 0 && (
                  <tr>
                    <td colSpan={8} className="px-4 py-8 text-center text-muted">
                      No indicators found
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Create indicator modal */}
      {showCreate && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setShowCreate(false)}>
          <div className="bg-gray-800 border border-gray-700 rounded-xl p-6 w-full max-w-lg shadow-2xl" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h2 className="text-white font-semibold">Add Indicator of Compromise</h2>
              <button onClick={() => setShowCreate(false)} className="text-muted hover:text-white">
                <XMarkIcon className="w-5 h-5" />
              </button>
            </div>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="text-muted text-xs mb-1 block">Type *</label>
                  <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))}
                          className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white">
                    {IOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
                <div>
                  <label className="text-muted text-xs mb-1 block">Severity *</label>
                  <select value={form.severity} onChange={e => setForm(f => ({ ...f, severity: e.target.value }))}
                          className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white">
                    {SEVERITIES.map(s => <option key={s} value={s}>{s}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label className="text-muted text-xs mb-1 block">Value *</label>
                <input value={form.value} onChange={e => setForm(f => ({ ...f, value: e.target.value }))}
                       placeholder={form.type === 'IP' ? '192.168.1.1' : form.type === 'DOMAIN' ? 'malware.example.com' : 'Enter value'}
                       className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-500" />
              </div>
              <div>
                <label className="text-muted text-xs mb-1 block">Confidence: {form.confidence}%</label>
                <input type="range" min="0" max="100" value={form.confidence}
                       onChange={e => setForm(f => ({ ...f, confidence: parseInt(e.target.value) }))}
                       className="w-full accent-blue-500" />
                <div className="flex justify-between text-xs text-muted mt-1">
                  <span>Low</span><span>Medium</span><span>High</span>
                </div>
              </div>
              <div>
                <label className="text-muted text-xs mb-1 block">Source</label>
                <input value={form.source} onChange={e => setForm(f => ({ ...f, source: e.target.value }))}
                       placeholder="e.g., VirusTotal, AlienVault, Manual"
                       className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-500" />
              </div>
              <div>
                <label className="text-muted text-xs mb-1 block">Description</label>
                <textarea value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                          rows={2} placeholder="Optional description"
                          className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-500 resize-none" />
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowCreate(false)} className="btn-secondary text-sm">Cancel</button>
              <button onClick={() => createMutation.mutate(form)}
                      disabled={!form.value || createMutation.isPending}
                      className="btn-primary text-sm">
                {createMutation.isPending ? 'Creating...' : 'Create Indicator'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Tab 3: Threat Hunting ───────────────────────────────────────────────────

function HuntingTab() {
  const [query, setQuery] = useState('')
  const [structured, setStructured] = useState({ type: '', target: '', timeRange: '24h' })
  const [mode, setMode] = useState('free') // 'free' | 'structured'
  const [huntHistory, setHuntHistory] = useState([])

  const huntMutation = useMutation({
    mutationFn: threatApi.huntThreats,
    onSuccess: (data) => {
      setHuntHistory(prev => [
        { query: mode === 'free' ? query : JSON.stringify(structured), results: data, timestamp: new Date().toISOString() },
        ...prev,
      ].slice(0, 5))
      toast.success(`Hunt complete: ${data?.matches?.length || data?.results?.length || 0} matches found`)
    },
    onError: (err) => toast.error(err?.response?.data?.error || 'Hunt failed'),
  })

  const runHunt = () => {
    if (mode === 'free') {
      if (!query.trim()) return toast.error('Enter a hunt query')
      huntMutation.mutate({ query: query.trim() })
    } else {
      huntMutation.mutate(structured)
    }
  }

  const results = huntMutation.data
  const matches = results?.matches || results?.results || (Array.isArray(results) ? results : [])

  return (
    <div className="space-y-6">
      {/* Query input */}
      <div className="card">
        <div className="flex items-center gap-3 mb-4">
          <h3 className="text-gray-300 font-medium">Threat Hunt</h3>
          <div className="flex bg-gray-700 rounded-lg p-0.5 text-xs">
            <button onClick={() => setMode('free')}
                    className={`px-3 py-1 rounded-md transition-colors ${mode === 'free' ? 'bg-blue-600 text-white' : 'text-muted hover:text-gray-300'}`}>
              Free Query
            </button>
            <button onClick={() => setMode('structured')}
                    className={`px-3 py-1 rounded-md transition-colors ${mode === 'structured' ? 'bg-blue-600 text-white' : 'text-muted hover:text-gray-300'}`}>
              Structured
            </button>
          </div>
        </div>

        {mode === 'free' ? (
          <textarea value={query} onChange={e => setQuery(e.target.value)}
                    rows={3} placeholder="Enter hunt query... e.g., 'Find all connections from suspicious IPs in the last 24 hours' or 'Search for lateral movement patterns'"
                    className="w-full bg-gray-700 border border-gray-600 rounded-lg px-4 py-3 text-sm text-white placeholder-gray-500 resize-none font-mono" />
        ) : (
          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="text-muted text-xs mb-1 block">Hunt Type</label>
              <select value={structured.type} onChange={e => setStructured(s => ({ ...s, type: e.target.value }))}
                      className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white">
                <option value="">Select type...</option>
                <option value="IP_SCAN">IP Scan</option>
                <option value="LATERAL_MOVEMENT">Lateral Movement</option>
                <option value="DATA_EXFILTRATION">Data Exfiltration</option>
                <option value="CREDENTIAL_ABUSE">Credential Abuse</option>
                <option value="ANOMALOUS_TRANSFER">Anomalous Transfer</option>
                <option value="PERSISTENCE">Persistence</option>
              </select>
            </div>
            <div>
              <label className="text-muted text-xs mb-1 block">Target (IP/Domain/User)</label>
              <input value={structured.target} onChange={e => setStructured(s => ({ ...s, target: e.target.value }))}
                     placeholder="e.g., 10.0.0.0/8"
                     className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-500" />
            </div>
            <div>
              <label className="text-muted text-xs mb-1 block">Time Range</label>
              <select value={structured.timeRange} onChange={e => setStructured(s => ({ ...s, timeRange: e.target.value }))}
                      className="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-sm text-white">
                <option value="1h">Last 1 hour</option>
                <option value="6h">Last 6 hours</option>
                <option value="24h">Last 24 hours</option>
                <option value="7d">Last 7 days</option>
                <option value="30d">Last 30 days</option>
              </select>
            </div>
          </div>
        )}

        <div className="flex justify-end mt-4">
          <button onClick={runHunt} disabled={huntMutation.isPending}
                  className="btn-primary flex items-center gap-2 text-sm">
            {huntMutation.isPending ? (
              <><ArrowPathIcon className="w-4 h-4 animate-spin" /> Hunting...</>
            ) : (
              <><PlayIcon className="w-4 h-4" /> Run Hunt</>
            )}
          </button>
        </div>
      </div>

      {/* Results */}
      {matches.length > 0 && (
        <div className="space-y-3">
          <h3 className="text-gray-300 text-sm font-medium">Results ({matches.length} matches)</h3>
          {matches.map((m, i) => (
            <div key={i} className="card">
              <div className="flex items-start justify-between mb-2">
                <div className="flex items-center gap-2">
                  <DocumentMagnifyingGlassIcon className="w-5 h-5 text-blue-400 flex-shrink-0" />
                  <span className="text-gray-200 font-medium text-sm">{m.entity || m.name || m.indicator || `Match #${i + 1}`}</span>
                </div>
                {(m.confidence || m.score) && (
                  <span className={`badge ${(m.confidence || m.score) >= 80 ? 'badge-red' : (m.confidence || m.score) >= 50 ? 'badge-yellow' : 'badge-green'}`}>
                    {m.confidence || m.score}% confidence
                  </span>
                )}
              </div>
              {m.evidence && (
                <p className="text-muted text-sm mb-2">{typeof m.evidence === 'string' ? m.evidence : JSON.stringify(m.evidence)}</p>
              )}
              {m.description && <p className="text-muted text-sm mb-2">{m.description}</p>}
              {m.matchedIndicators?.length > 0 && (
                <div className="flex flex-wrap gap-1 mt-2">
                  {m.matchedIndicators.map((mi, j) => (
                    <span key={j} className="badge badge-yellow">{mi}</span>
                  ))}
                </div>
              )}
              {m.tactics?.length > 0 && (
                <div className="flex flex-wrap gap-1 mt-2">
                  {m.tactics.map((t, j) => (
                    <span key={j} className="badge badge-red">{t}</span>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {huntMutation.isSuccess && matches.length === 0 && (
        <EmptyState icon={MagnifyingGlassIcon} title="No matches found" subtitle="Try broadening your search criteria" />
      )}

      {/* Hunt history */}
      {huntHistory.length > 0 && (
        <div className="card">
          <h3 className="text-gray-300 text-sm font-medium mb-3">Hunt History</h3>
          <div className="space-y-2">
            {huntHistory.map((h, i) => (
              <div key={i} className="flex items-center gap-3 text-sm py-2 border-b border-gray-700/40 last:border-0">
                <ClockIcon className="w-4 h-4 text-muted flex-shrink-0" />
                <span className="text-gray-300 flex-1 truncate font-mono text-xs">{h.query}</span>
                <span className="badge badge-green">
                  {h.results?.matches?.length || h.results?.results?.length || 0} matches
                </span>
                <span className="text-muted text-xs flex-shrink-0">
                  {formatDistanceToNow(new Date(h.timestamp), { addSuffix: true })}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Tab 4: MITRE ATT&CK ────────────────────────────────────────────────────

const MITRE_TACTICS = [
  'Reconnaissance', 'Resource Development', 'Initial Access', 'Execution',
  'Persistence', 'Privilege Escalation', 'Defense Evasion', 'Credential Access',
  'Discovery', 'Lateral Movement', 'Collection', 'C2', 'Exfiltration', 'Impact',
]

function MitreTab() {
  const { data: mapping, isLoading: loadingMapping } = useQuery({
    queryKey: ['mitre-mapping'],
    queryFn: threatApi.getMitreMapping,
    meta: { silent: true }, refetchInterval: 120000,
  })
  const { data: coverage, isLoading: loadingCoverage } = useQuery({
    queryKey: ['mitre-coverage'],
    queryFn: threatApi.getMitreCoverage,
    meta: { silent: true }, refetchInterval: 120000,
  })

  const [selectedCell, setSelectedCell] = useState(null)

  if (loadingMapping || loadingCoverage) return <Spinner />

  // Build matrix from mapping data
  const mappingData = mapping?.mapping || mapping?.techniques || mapping || {}
  const coverageData = coverage || {}
  const totalTechniques = coverageData.totalTechniques || coverageData.total || 0
  const coveredCount = coverageData.coveredTechniques || coverageData.covered || 0
  const partiallyCovered = coverageData.partiallyCovered || 0
  const coveragePct = totalTechniques > 0 ? Math.round((coveredCount / totalTechniques) * 100) : 0

  // Build tactic -> techniques map
  const tacticMap = {}
  MITRE_TACTICS.forEach(t => { tacticMap[t] = [] })

  if (Array.isArray(mappingData)) {
    mappingData.forEach(item => {
      const tactic = item.tactic || item.tacticName || ''
      const matched = MITRE_TACTICS.find(t => t.toLowerCase() === tactic.toLowerCase() || tactic.includes(t))
      if (matched) {
        tacticMap[matched].push(item)
      }
    })
  } else if (typeof mappingData === 'object') {
    Object.entries(mappingData).forEach(([tactic, techniques]) => {
      const matched = MITRE_TACTICS.find(t => t.toLowerCase() === tactic.toLowerCase() || tactic.toLowerCase().includes(t.toLowerCase()))
      if (matched) {
        const techArr = Array.isArray(techniques) ? techniques : [techniques]
        tacticMap[matched].push(...techArr.map(tech => typeof tech === 'string' ? { id: tech, name: tech, coverage: 'covered' } : tech))
      }
    })
  }

  const maxTechniques = Math.max(1, ...MITRE_TACTICS.map(t => tacticMap[t]?.length || 0))

  const getCellColor = (tech) => {
    const cov = tech?.coverage || tech?.status || ''
    if (cov === 'covered' || cov === 'FULL' || cov === 'detected') return 'bg-green-600/40 border-green-500/30 hover:bg-green-600/50'
    if (cov === 'partial' || cov === 'PARTIAL') return 'bg-yellow-600/30 border-yellow-500/30 hover:bg-yellow-600/40'
    return 'bg-gray-700/30 border-gray-600/30 hover:bg-gray-700/50'
  }

  return (
    <div className="space-y-6">
      {/* Coverage stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Total Techniques" value={totalTechniques} color="text-gray-300" icon={MapIcon} />
        <StatCard label="Covered" value={coveredCount} color="text-green-400" icon={ShieldExclamationIcon} />
        <StatCard label="Partially Covered" value={partiallyCovered} color="text-yellow-400" icon={ExclamationTriangleIcon} />
        <div className="card-sm">
          <div className="flex items-center justify-between mb-1">
            <EyeIcon className="w-4 h-4 text-muted" />
            <span className="text-muted text-xs">Coverage</span>
          </div>
          <div className={`text-2xl font-bold ${coveragePct >= 70 ? 'text-green-400' : coveragePct >= 40 ? 'text-yellow-400' : 'text-red-400'}`}>
            {coveragePct}%
          </div>
          <div className="w-full bg-gray-700 rounded-full h-1.5 mt-2">
            <div className={`h-1.5 rounded-full transition-all ${coveragePct >= 70 ? 'bg-green-500' : coveragePct >= 40 ? 'bg-yellow-500' : 'bg-red-500'}`}
                 style={{ width: `${coveragePct}%` }} />
          </div>
        </div>
      </div>

      {/* Legend */}
      <div className="flex items-center gap-6 text-xs text-muted">
        <div className="flex items-center gap-2">
          <div className="w-4 h-3 rounded bg-green-600/40 border border-green-500/30" />
          <span>Covered</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="w-4 h-3 rounded bg-yellow-600/30 border border-yellow-500/30" />
          <span>Partial</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="w-4 h-3 rounded bg-gray-700/30 border border-gray-600/30" />
          <span>Not covered</span>
        </div>
      </div>

      {/* MITRE matrix grid */}
      <div className="card p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <div className="inline-flex min-w-full">
            {MITRE_TACTICS.map(tactic => {
              const techniques = tacticMap[tactic] || []
              return (
                <div key={tactic} className="flex-1 min-w-[110px] border-r border-gray-700/40 last:border-r-0">
                  {/* Tactic header */}
                  <div className="px-2 py-2.5 bg-gray-800/80 border-b border-gray-700/40 text-center">
                    <div className="text-xs font-medium text-gray-300 leading-tight">{tactic}</div>
                    <div className="text-[10px] text-muted mt-0.5">{techniques.length} techniques</div>
                  </div>
                  {/* Technique cells */}
                  <div className="p-1.5 space-y-1">
                    {techniques.length > 0 ? techniques.map((tech, j) => (
                      <button key={tech.id || j}
                              onClick={() => setSelectedCell(selectedCell?.id === (tech.id || j) && selectedCell?.tactic === tactic ? null : { ...tech, tactic })}
                              className={`w-full px-2 py-1.5 rounded border text-[10px] text-left transition-colors cursor-pointer ${getCellColor(tech)} ${
                                selectedCell?.id === (tech.id || j) && selectedCell?.tactic === tactic ? 'ring-1 ring-blue-500' : ''
                              }`}>
                        <div className="font-mono text-gray-300 truncate">{tech.id || tech.techniqueId || ''}</div>
                        <div className="text-muted truncate">{tech.name || tech.techniqueName || ''}</div>
                      </button>
                    )) : (
                      <div className="px-2 py-3 text-center text-[10px] text-muted">No data</div>
                    )}
                    {/* Filler to equalize heights visually */}
                    {techniques.length < maxTechniques && techniques.length > 0 && (
                      <div style={{ height: `${(maxTechniques - techniques.length) * 0.25}rem` }} />
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      </div>

      {/* Selected technique detail */}
      {selectedCell && (
        <div className="card">
          <div className="flex items-center justify-between mb-3">
            <div>
              <h3 className="text-gray-200 font-medium">
                {selectedCell.id || selectedCell.techniqueId} &mdash; {selectedCell.name || selectedCell.techniqueName}
              </h3>
              <p className="text-muted text-xs mt-0.5">Tactic: {selectedCell.tactic}</p>
            </div>
            <button onClick={() => setSelectedCell(null)} className="text-muted hover:text-white">
              <XMarkIcon className="w-5 h-5" />
            </button>
          </div>
          {selectedCell.description && <p className="text-muted text-sm mb-3">{selectedCell.description}</p>}
          {(selectedCell.detectionRules || selectedCell.rules) && (
            <div>
              <h4 className="text-gray-300 text-xs font-medium mb-2">Detection Rules</h4>
              <div className="space-y-1.5">
                {(selectedCell.detectionRules || selectedCell.rules || []).map((rule, k) => (
                  <div key={k} className="flex items-center gap-2 text-sm pl-3 border-l-2 border-blue-500/40">
                    <ShieldExclamationIcon className="w-3.5 h-3.5 text-blue-400 flex-shrink-0" />
                    <span className="text-gray-300">{typeof rule === 'string' ? rule : rule.name || rule.title || JSON.stringify(rule)}</span>
                    {rule.severity && <span className={SEV_BADGE[rule.severity] || 'badge badge-green'}>{rule.severity}</span>}
                  </div>
                ))}
              </div>
            </div>
          )}
          {(selectedCell.mitigations || []).length > 0 && (
            <div className="mt-3">
              <h4 className="text-gray-300 text-xs font-medium mb-2">Mitigations</h4>
              <ul className="list-disc list-inside text-muted text-sm space-y-1">
                {selectedCell.mitigations.map((m, k) => (
                  <li key={k}>{typeof m === 'string' ? m : m.name || m.description}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ─── Tab 5: Attack Chains ────────────────────────────────────────────────────

function AttackChainsTab() {
  const { data: chains, isLoading } = useQuery({
    queryKey: ['threat-chains'],
    queryFn: threatApi.getAttackChains,
    meta: { silent: true }, refetchInterval: 30000,
  })

  if (isLoading) return <Spinner />

  const chainList = Array.isArray(chains) ? chains : chains?.chains || []

  if (chainList.length === 0) {
    return (
      <EmptyState icon={LinkIcon} title="No attack chains detected"
                  subtitle="Attack chains appear when correlated threat indicators form a kill chain pattern" />
    )
  }

  return (
    <div className="space-y-4">
      {chainList.map((chain, i) => {
        const severity = chain.severity || chain.risk || 'MEDIUM'
        const sevCfg = THREAT_LEVEL_CONFIG[severity] || THREAT_LEVEL_CONFIG.MEDIUM
        const steps = chain.steps || chain.stages || chain.chain || []
        const assets = chain.affectedAssets || chain.targets || []
        return (
          <div key={chain.id || i} className={`card border ${sevCfg.border}`}>
            {/* Chain header */}
            <div className="flex items-start justify-between mb-4">
              <div>
                <h3 className="text-gray-200 font-medium">
                  {chain.name || chain.title || `Attack Chain #${i + 1}`}
                </h3>
                {chain.description && <p className="text-muted text-sm mt-1">{chain.description}</p>}
              </div>
              <div className="flex items-center gap-2">
                <span className={SEV_BADGE[severity] || 'badge badge-yellow'}>{severity}</span>
                {chain.status && <span className="badge badge-green">{chain.status}</span>}
              </div>
            </div>

            {/* Chain flow visualization */}
            {steps.length > 0 && (
              <div className="mb-4">
                <div className="flex items-center overflow-x-auto pb-2">
                  {steps.map((step, j) => (
                    <div key={j} className="flex items-center flex-shrink-0">
                      <div className="bg-gray-700/60 border border-gray-600/40 rounded-lg px-4 py-3 min-w-[140px]">
                        {step.tactic && (
                          <div className="text-[10px] text-blue-400 uppercase font-medium tracking-wide mb-1">{step.tactic}</div>
                        )}
                        <div className="text-gray-200 text-sm font-medium">{step.technique || step.name || step.action || `Step ${j + 1}`}</div>
                        {step.techniqueId && (
                          <div className="text-muted text-xs font-mono mt-0.5">{step.techniqueId}</div>
                        )}
                        {step.source && (
                          <div className="text-muted text-xs mt-1">Source: {step.source}</div>
                        )}
                        {step.target && (
                          <div className="text-muted text-xs">Target: {step.target}</div>
                        )}
                        {step.timestamp && (
                          <div className="text-muted text-[10px] mt-1">
                            {format(new Date(step.timestamp), 'MMM d, HH:mm')}
                          </div>
                        )}
                      </div>
                      {j < steps.length - 1 && (
                        <ChevronRightIcon className="w-5 h-5 text-muted mx-1 flex-shrink-0" />
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Metadata row */}
            <div className="flex flex-wrap items-center gap-4 text-xs text-muted">
              {chain.source && (
                <span className="flex items-center gap-1">
                  <GlobeAltIcon className="w-3.5 h-3.5" /> Source: {chain.source}
                </span>
              )}
              {assets.length > 0 && (
                <span className="flex items-center gap-1">
                  <BoltIcon className="w-3.5 h-3.5" /> Affected: {assets.join(', ')}
                </span>
              )}
              {chain.firstSeen && (
                <span className="flex items-center gap-1">
                  <ClockIcon className="w-3.5 h-3.5" />
                  {format(new Date(chain.firstSeen), 'MMM d, HH:mm')} &mdash; {chain.lastSeen ? format(new Date(chain.lastSeen), 'MMM d, HH:mm') : 'ongoing'}
                </span>
              )}
              {chain.confidence && (
                <span>Confidence: {chain.confidence}%</span>
              )}
            </div>

            {/* Investigate button */}
            <div className="flex justify-end mt-3">
              <a href="/sentinel" className="btn-secondary text-xs flex items-center gap-1.5">
                <EyeIcon className="w-3.5 h-3.5" /> Investigate in Sentinel
              </a>
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ─── Main Component ──────────────────────────────────────────────────────────

export default function ThreatIntelligence() {
  const [activeTab, setActiveTab] = useState('overview')

  // Top-level service health probe — drives the "service unavailable" banner.
  // Uses the dashboard endpoint as a lightweight liveness check for the AI engine.
  const {
    isError: serviceDown,
    refetch: refetchHealth,
    isFetching: healthFetching,
  } = useQuery({
    queryKey: ['threat-intel-health'],
    queryFn: threatApi.getThreatDashboard,
    meta: { silent: true }, refetchInterval: 60000,
    retry: 1,
  })
  const queryClient = useQueryClient()
  const handleRetry = () => {
    refetchHealth()
    queryClient.invalidateQueries({ queryKey: ['threat-dashboard'] })
    queryClient.invalidateQueries({ queryKey: ['threat-incidents'] })
    queryClient.invalidateQueries({ queryKey: ['threat-actors'] })
    queryClient.invalidateQueries({ queryKey: ['threat-chains-overview'] })
  }

  return (
    <div className="space-y-6">
      {serviceDown && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">
              AI engine unavailable — threat intelligence data may be stale or missing
            </span>
          </div>
          <button
            onClick={handleRetry}
            disabled={healthFetching}
            className="text-xs text-red-400 hover:text-red-300 underline disabled:opacity-50"
          >
            {healthFetching ? 'Retrying...' : 'Retry'}
          </button>
        </div>
      )}

      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold text-primary">Threat Intelligence</h1>
        <p className="text-secondary text-sm">Real-time threat monitoring with MITRE ATT&CK mapping and automated hunting</p>
      </div>

      {/* Tab bar */}
      <div className="flex gap-1 border-b border-gray-700/40 overflow-x-auto">
        {TABS.map(tab => {
          const Icon = tab.icon
          const active = activeTab === tab.key
          return (
            <button key={tab.key} onClick={() => setActiveTab(tab.key)}
                    className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors whitespace-nowrap ${
                      active
                        ? 'border-blue-500 text-blue-400'
                        : 'border-transparent text-muted hover:text-gray-300'
                    }`}>
              <Icon className="w-4 h-4" />
              {tab.label}
            </button>
          )
        })}
      </div>

      {/* Tab content */}
      {activeTab === 'overview'   && <OverviewTab />}
      {activeTab === 'indicators' && <IndicatorsTab />}
      {activeTab === 'hunting'    && <HuntingTab />}
      {activeTab === 'mitre'      && <MitreTab />}
      {activeTab === 'chains'     && <AttackChainsTab />}
    </div>
  )
}
