import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import Modal from '../components/Modal'
import * as api from '../api/proxyIntel'
import {
  ShieldCheckIcon, ShieldExclamationIcon, GlobeAltIcon,
  MagnifyingGlassIcon, PlusIcon, TrashIcon, XMarkIcon,
  EyeIcon, NoSymbolIcon, CheckCircleIcon, ChartBarIcon,
  ArrowPathIcon, ExclamationTriangleIcon, MapPinIcon,
  ClockIcon, LockClosedIcon, LockOpenIcon, SignalIcon,
  FunnelIcon, ChevronDownIcon, ChevronUpIcon
} from '@heroicons/react/24/outline'

// ── Constants ──────────────────────────────────────────────────────────────

const TABS = [
  { key: 'overview', label: 'Overview', icon: ChartBarIcon },
  { key: 'ip-intel', label: 'IP Intelligence', icon: MagnifyingGlassIcon },
  { key: 'lists', label: 'Block/Allow Lists', icon: ShieldCheckIcon },
  { key: 'geo', label: 'Geo Analysis', icon: GlobeAltIcon },
]

const VERDICT_BADGE = {
  ALLOW: 'badge badge-green', BLOCK: 'badge badge-red', CHALLENGE: 'badge badge-yellow',
  SUSPICIOUS: 'badge badge-orange', UNKNOWN: 'badge badge-gray',
}

const RISK_COLOR = (score) => {
  if (score >= 80) return 'text-red-400'
  if (score >= 60) return 'text-orange-400'
  if (score >= 40) return 'text-yellow-400'
  return 'text-green-400'
}

const RISK_BG = (score) => {
  if (score >= 80) return 'bg-red-500'
  if (score >= 60) return 'bg-orange-500'
  if (score >= 40) return 'bg-yellow-500'
  return 'bg-green-500'
}

const RISK_LABEL = (score) => {
  if (score >= 80) return 'Critical'
  if (score >= 60) return 'High'
  if (score >= 40) return 'Medium'
  if (score >= 20) return 'Low'
  return 'Clean'
}

const REPUTATION_BADGE = {
  CLEAN: 'badge badge-green', SUSPICIOUS: 'badge badge-yellow', MALICIOUS: 'badge badge-red',
}

// ── Helpers ────────────────────────────────────────────────────────────────

function Spinner() {
  return <div className="flex justify-center py-12"><div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" /></div>
}

function EmptyState({ icon: Icon, message }) {
  return (
    <div className="text-center py-12 text-secondary">
      <Icon className="w-10 h-10 mx-auto mb-3 text-muted" />
      <p>{message}</p>
    </div>
  )
}

function StatCard({ label, value, color = 'text-white', icon: Icon, sub }) {
  return (
    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
      <div className="flex items-center gap-2 mb-1">
        {Icon && <Icon className="w-4 h-4 text-muted" />}
        <span className="text-muted text-xs">{label}</span>
      </div>
      <div className={`text-2xl font-bold ${color}`}>{value ?? '—'}</div>
      {sub && <div className="text-muted text-xs mt-1">{sub}</div>}
    </div>
  )
}

function RiskGauge({ score }) {
  if (score == null) return <span className="text-muted text-sm">N/A</span>
  const pct = Math.min(score, 100)
  return (
    <div className="flex flex-col items-center">
      <div className={`text-4xl font-bold ${RISK_COLOR(score)}`}>{score}</div>
      <div className="text-muted text-xs mt-0.5">{RISK_LABEL(score)}</div>
      <div className="w-full bg-gray-700 rounded-full h-2 mt-2">
        <div className={`${RISK_BG(score)} h-2 rounded-full transition-all`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

function VerdictPie({ data }) {
  if (!data) return null
  const total = (data.allow || 0) + (data.block || 0) + (data.challenge || 0)
  if (total === 0) return <div className="text-muted text-sm text-center py-4">No verdict data</div>
  const segments = [
    { label: 'Allow', count: data.allow || 0, color: '#10b981' },
    { label: 'Block', count: data.block || 0, color: '#ef4444' },
    { label: 'Challenge', count: data.challenge || 0, color: '#f59e0b' },
  ]
  let cumPct = 0
  return (
    <div className="flex items-center gap-6">
      <svg viewBox="0 0 36 36" className="w-28 h-28">
        {segments.map(s => {
          const pct = (s.count / total) * 100
          const offset = cumPct
          cumPct += pct
          return pct > 0 ? (
            <circle key={s.label} r="15.915" cx="18" cy="18" fill="none"
                    stroke={s.color} strokeWidth="4"
                    strokeDasharray={`${pct} ${100 - pct}`}
                    strokeDashoffset={-offset}
                    className="transition-all" />
          ) : null
        })}
      </svg>
      <div className="space-y-1.5">
        {segments.map(s => (
          <div key={s.label} className="flex items-center gap-2 text-sm">
            <span className="w-3 h-3 rounded-full" style={{ background: s.color }} />
            <span className="text-gray-300">{s.label}</span>
            <span className="text-muted text-xs ml-auto">{s.count} ({total > 0 ? Math.round((s.count / total) * 100) : 0}%)</span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Tab 1: Overview ────────────────────────────────────────────────────────

function OverviewTab() {
  const { data: dashboard, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['proxy-dashboard'],
    queryFn: api.getProxyDashboard,
    refetchInterval: 15000,
    retry: 1,
  })

  const { data: events } = useQuery({
    queryKey: ['proxy-events'],
    queryFn: api.getProxyEvents,
    refetchInterval: 20000,
    retry: 1,
    // React Query v5 removed per-query onError — use meta.errorMessage
    // instead, which is read by the global QueryCache onError handler
    // in main.jsx.
    meta: { errorMessage: "Couldn't load proxy events" },
  })

  if (isLoading) return <Spinner />
  if (isError) {
    return (
      <div className="bg-gray-800 rounded-lg p-6 border border-red-800/40 text-center">
        <ExclamationTriangleIcon className="w-10 h-10 mx-auto mb-2 text-red-400" />
        <h3 className="text-lg font-semibold text-gray-200 mb-1">Proxy Intelligence unavailable</h3>
        <p className="text-sm text-gray-400 mb-3">
          Couldn't reach ai-engine (:8091) — {error?.message || 'unknown error'}
        </p>
        <button
          onClick={() => refetch()}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-blue-600 text-white hover:bg-blue-700"
        >
          <ArrowPathIcon className="w-3.5 h-3.5" /> Retry
        </button>
      </div>
    )
  }

  const stats = dashboard?.stats || dashboard || {}
  const topBlocked = dashboard?.topBlocked || []
  const distribution = dashboard?.verdictDistribution || stats.verdictDistribution || {}
  const eventList = Array.isArray(events) ? events : events?.content || []

  return (
    <div className="space-y-6">
      {/* Stats cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Total Verdicts" value={stats.totalVerdicts ?? 0} icon={ShieldCheckIcon} />
        <StatCard label="Blocked" value={stats.blocked ?? 0} color="text-red-400" icon={NoSymbolIcon} />
        <StatCard label="Allowed" value={stats.allowed ?? 0} color="text-green-400" icon={CheckCircleIcon} />
        <StatCard label="Suspicious" value={stats.suspicious ?? stats.challenged ?? 0} color="text-yellow-400" icon={ExclamationTriangleIcon} />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Verdict distribution */}
        <div className="bg-gray-800 rounded-lg p-5 border border-gray-700">
          <h3 className="text-gray-300 text-sm font-medium mb-4">Verdict Distribution</h3>
          <VerdictPie data={distribution} />
        </div>

        {/* Top blocked IPs */}
        <div className="bg-gray-800 rounded-lg border border-gray-700">
          <div className="px-4 py-3 border-b border-gray-700">
            <h3 className="text-gray-300 text-sm font-medium">Top Blocked IPs (24h)</h3>
          </div>
          <div className="divide-y divide-gray-700">
            {topBlocked.length === 0 ? (
              <div className="px-4 py-6 text-center text-muted text-sm">No blocked IPs in the last 24 hours</div>
            ) : (
              topBlocked.slice(0, 8).map((entry, i) => (
                <div key={i} className="px-4 py-2.5 flex items-center gap-3 text-sm">
                  <span className="text-muted text-xs w-5 text-right">{i + 1}.</span>
                  <span className="text-gray-200 font-mono text-xs flex-1">{entry.ip}</span>
                  {entry.country && (
                    <span className="text-muted text-xs flex items-center gap-1">
                      <GlobeAltIcon className="w-3 h-3" />{entry.country}
                    </span>
                  )}
                  <span className="badge badge-red text-xs">{entry.count ?? entry.hits} hits</span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* Recent events timeline */}
      <div className="bg-gray-800 rounded-lg border border-gray-700">
        <div className="px-4 py-3 border-b border-gray-700">
          <h3 className="text-gray-300 text-sm font-medium">Recent Events</h3>
        </div>
        <div className="divide-y divide-gray-700">
          {eventList.length === 0 ? (
            <div className="px-4 py-8 text-center text-secondary">
              <ClockIcon className="w-8 h-8 mx-auto mb-2 text-muted" />
              <div>No recent events</div>
            </div>
          ) : (
            eventList.slice(0, 10).map((evt, i) => (
              <div key={i} className="px-4 py-2.5 flex items-center gap-3 text-sm">
                <ClockIcon className="w-4 h-4 text-muted flex-shrink-0" />
                <span className={VERDICT_BADGE[evt.verdict] || 'badge badge-gray'}>{evt.verdict || evt.type || 'EVENT'}</span>
                <span className="text-gray-300 font-mono text-xs">{evt.ip || '—'}</span>
                <span className="text-gray-400 text-xs flex-1 truncate">{evt.message || evt.description || evt.reason || ''}</span>
                <span className="text-secondary text-xs flex-shrink-0">
                  {evt.timestamp ? format(new Date(evt.timestamp), 'HH:mm:ss') : '—'}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  )
}

// ── Tab 2: IP Intelligence ─────────────────────────────────────────────────

function IpIntelTab() {
  const queryClient = useQueryClient()
  const [searchIp, setSearchIp] = useState('')
  const [queriedIp, setQueriedIp] = useState(null)

  const { data: intel, isLoading, isError, error } = useQuery({
    queryKey: ['proxy-ip-intel', queriedIp],
    queryFn: () => api.getIpIntel(queriedIp),
    enabled: !!queriedIp,
    retry: 1,
  })

  const { data: verdict, isLoading: verdictLoading } = useQuery({
    queryKey: ['proxy-verdict', queriedIp],
    queryFn: () => api.getVerdict(queriedIp),
    enabled: !!queriedIp,
    retry: 1,
  })

  const blockMut = useMutation({
    mutationFn: (ip) => api.addToBlocklist({ ip, reason: 'Manual block from IP Intel' }),
    onSuccess: () => {
      toast.success('Added to blocklist')
      queryClient.invalidateQueries({ queryKey: ['proxy-blocklist'] })
    },
    onError: (e) => toast.error('Block failed: ' + (e.response?.data?.message || e.message)),
  })

  const allowMut = useMutation({
    mutationFn: (ip) => api.addToAllowlist({ ip, reason: 'Manual allow from IP Intel' }),
    onSuccess: () => {
      toast.success('Added to allowlist')
      queryClient.invalidateQueries({ queryKey: ['proxy-allowlist'] })
    },
    onError: (e) => toast.error('Allow failed: ' + (e.response?.data?.message || e.message)),
  })

  const handleSearch = (e) => {
    e.preventDefault()
    const ip = searchIp.trim()
    if (ip) setQueriedIp(ip)
  }

  const result = intel || verdict

  return (
    <div className="space-y-6">
      {/* Search */}
      <form onSubmit={handleSearch} className="flex gap-3">
        <div className="relative flex-1 max-w-md">
          <MagnifyingGlassIcon className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input type="text" value={searchIp} onChange={e => setSearchIp(e.target.value)}
                 placeholder="Enter IP address (e.g. 203.0.113.42)"
                 className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded pl-9 pr-3 py-2 text-sm font-mono" />
        </div>
        <button type="submit" disabled={!searchIp.trim()} className="btn-primary flex items-center gap-1.5">
          <EyeIcon className="w-4 h-4" /> Analyze
        </button>
      </form>

      {(isLoading || verdictLoading) && <Spinner />}

      {isError && (
        <div className="bg-red-900/20 border border-red-800 rounded-lg p-4 text-sm text-red-300">
          <ExclamationTriangleIcon className="w-5 h-5 inline mr-2" />
          Lookup failed: {error?.response?.data?.message || error?.message || 'Unknown error'}
        </div>
      )}

      {result && !isLoading && !verdictLoading && (
        <div className="space-y-4">
          {/* Result header */}
          <div className="bg-gray-800 rounded-lg border border-gray-700 p-5">
            <div className="flex items-start justify-between mb-4">
              <div>
                <div className="text-lg font-mono text-white">{queriedIp}</div>
                <div className="text-muted text-sm mt-0.5">{result.isp || result.org || '—'}</div>
              </div>
              <div className="flex gap-2">
                <button onClick={() => blockMut.mutate(queriedIp)} disabled={blockMut.isPending}
                        className="btn-secondary text-xs flex items-center gap-1 text-red-400 border-red-800 hover:bg-red-900/30">
                  <NoSymbolIcon className="w-3.5 h-3.5" /> Add to Blocklist
                </button>
                <button onClick={() => allowMut.mutate(queriedIp)} disabled={allowMut.isPending}
                        className="btn-secondary text-xs flex items-center gap-1 text-green-400 border-green-800 hover:bg-green-900/30">
                  <CheckCircleIcon className="w-3.5 h-3.5" /> Add to Allowlist
                </button>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
              {/* Verdict */}
              <div className="bg-gray-900 rounded-lg p-4 border border-gray-700 text-center">
                <div className="text-xs text-muted mb-2">Verdict</div>
                <span className={`text-lg ${VERDICT_BADGE[result.verdict || verdict?.verdict] || 'badge badge-gray'}`}>
                  {result.verdict || verdict?.verdict || 'UNKNOWN'}
                </span>
              </div>

              {/* Risk score */}
              <div className="bg-gray-900 rounded-lg p-4 border border-gray-700">
                <div className="text-xs text-muted mb-2 text-center">Risk Score</div>
                <RiskGauge score={result.riskScore ?? result.risk ?? verdict?.riskScore} />
              </div>

              {/* Geo */}
              <div className="bg-gray-900 rounded-lg p-4 border border-gray-700">
                <div className="text-xs text-muted mb-2">Location</div>
                <div className="space-y-1.5 text-sm">
                  <div className="flex items-center gap-2">
                    <GlobeAltIcon className="w-4 h-4 text-muted" />
                    <span className="text-gray-300">{result.country || '—'}</span>
                    {result.countryCode && <span className="text-muted text-xs">({result.countryCode})</span>}
                  </div>
                  <div className="flex items-center gap-2">
                    <MapPinIcon className="w-4 h-4 text-muted" />
                    <span className="text-gray-300">{result.city || '—'}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <SignalIcon className="w-4 h-4 text-muted" />
                    <span className="text-gray-300">{result.isp || result.org || '—'}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Reputation & Threat categories */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
              <h4 className="text-sm text-gray-300 font-medium mb-3">Reputation</h4>
              <span className={REPUTATION_BADGE[result.reputation?.toUpperCase()] || 'badge badge-gray'}>
                {result.reputation || 'UNKNOWN'}
              </span>
              {result.reputationDetails && (
                <p className="text-xs text-muted mt-2">{result.reputationDetails}</p>
              )}
            </div>
            <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
              <h4 className="text-sm text-gray-300 font-medium mb-3">Threat Categories</h4>
              <div className="flex flex-wrap gap-1.5">
                {(result.threatCategories || result.threats || []).length > 0 ? (
                  (result.threatCategories || result.threats).map((t, i) => (
                    <span key={i} className="badge badge-red">{t}</span>
                  ))
                ) : (
                  <span className="text-muted text-xs">No known threats</span>
                )}
              </div>
            </div>
          </div>

          {/* Verdict history */}
          {(result.history || verdict?.history || []).length > 0 && (
            <div className="bg-gray-800 rounded-lg border border-gray-700">
              <div className="px-4 py-3 border-b border-gray-700">
                <h4 className="text-sm text-gray-300 font-medium">Verdict History</h4>
              </div>
              <div className="divide-y divide-gray-700">
                {(result.history || verdict?.history).slice(0, 10).map((h, i) => (
                  <div key={i} className="px-4 py-2 flex items-center gap-3 text-sm">
                    <span className={VERDICT_BADGE[h.verdict] || 'badge badge-gray'}>{h.verdict}</span>
                    <span className="text-muted text-xs">Score: {h.riskScore ?? '—'}</span>
                    <span className="text-gray-400 text-xs flex-1">{h.reason || ''}</span>
                    <span className="text-secondary text-xs">
                      {h.timestamp ? format(new Date(h.timestamp), 'MMM d, HH:mm:ss') : '—'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {!queriedIp && !isLoading && (
        <EmptyState icon={MagnifyingGlassIcon} message="Enter an IP address above to view intelligence, verdict, and geo data." />
      )}
    </div>
  )
}

// ── Tab 3: Block/Allow Lists ───────────────────────────────────────────────

function ListsTab() {
  const queryClient = useQueryClient()
  const [blockSearch, setBlockSearch] = useState('')
  const [allowSearch, setAllowSearch] = useState('')
  const [showAddBlock, setShowAddBlock] = useState(false)
  const [showAddAllow, setShowAddAllow] = useState(false)
  const [entryForm, setEntryForm] = useState({ ip: '', reason: '', expiresAt: '' })
  const [confirmRemove, setConfirmRemove] = useState(null) // { ip, list: 'block'|'allow' }

  const { data: blocklist, isLoading: blockLoading } = useQuery({
    queryKey: ['proxy-blocklist'],
    queryFn: api.getBlocklist,
    refetchInterval: 15000,
  })

  const { data: allowlist, isLoading: allowLoading } = useQuery({
    queryKey: ['proxy-allowlist'],
    queryFn: api.getAllowlist,
    refetchInterval: 15000,
  })

  const addBlock = useMutation({
    mutationFn: api.addToBlocklist,
    onSuccess: () => {
      toast.success('Added to blocklist')
      setShowAddBlock(false)
      setEntryForm({ ip: '', reason: '', expiresAt: '' })
      queryClient.invalidateQueries({ queryKey: ['proxy-blocklist'] })
    },
    onError: (e) => toast.error('Failed: ' + (e.response?.data?.message || e.message)),
  })

  const addAllow = useMutation({
    mutationFn: api.addToAllowlist,
    onSuccess: () => {
      toast.success('Added to allowlist')
      setShowAddAllow(false)
      setEntryForm({ ip: '', reason: '', expiresAt: '' })
      queryClient.invalidateQueries({ queryKey: ['proxy-allowlist'] })
    },
    onError: (e) => toast.error('Failed: ' + (e.response?.data?.message || e.message)),
  })

  const removeBlock = useMutation({
    mutationFn: api.removeFromBlocklist,
    onSuccess: () => {
      toast.success('Removed from blocklist')
      setConfirmRemove(null)
      queryClient.invalidateQueries({ queryKey: ['proxy-blocklist'] })
    },
    onError: (e) => toast.error('Remove failed: ' + (e.response?.data?.message || e.message)),
  })

  const removeAllow = useMutation({
    mutationFn: api.removeFromAllowlist,
    onSuccess: () => {
      toast.success('Removed from allowlist')
      setConfirmRemove(null)
      queryClient.invalidateQueries({ queryKey: ['proxy-allowlist'] })
    },
    onError: (e) => toast.error('Remove failed: ' + (e.response?.data?.message || e.message)),
  })

  const blockItems = (Array.isArray(blocklist) ? blocklist : blocklist?.content || [])
    .filter(e => !blockSearch || (e.ip || e.cidr || '').toLowerCase().includes(blockSearch.toLowerCase()))
  const allowItems = (Array.isArray(allowlist) ? allowlist : allowlist?.content || [])
    .filter(e => !allowSearch || (e.ip || e.cidr || '').toLowerCase().includes(allowSearch.toLowerCase()))

  const resetForm = () => setEntryForm({ ip: '', reason: '', expiresAt: '' })

  const renderList = (items, loading, search, setSearch, listType, onAdd) => (
    <div className="bg-gray-800 rounded-lg border border-gray-700 flex-1">
      <div className="px-4 py-3 border-b border-gray-700 flex items-center justify-between">
        <h3 className="text-gray-300 text-sm font-medium flex items-center gap-2">
          {listType === 'block' ? <NoSymbolIcon className="w-4 h-4 text-red-400" /> : <CheckCircleIcon className="w-4 h-4 text-green-400" />}
          {listType === 'block' ? 'Blocklist' : 'Allowlist'}
          <span className="badge badge-gray text-xs">{items.length}</span>
        </h3>
        <button onClick={onAdd} className="text-xs btn-primary flex items-center gap-1">
          <PlusIcon className="w-3.5 h-3.5" /> Add
        </button>
      </div>
      <div className="px-4 py-2 border-b border-gray-700">
        <div className="relative">
          <MagnifyingGlassIcon className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-muted" />
          <input type="text" value={search} onChange={e => setSearch(e.target.value)}
                 placeholder="Filter..."
                 className="w-full bg-gray-900 text-gray-300 text-xs border border-gray-700 rounded pl-8 pr-3 py-1.5" />
        </div>
      </div>
      {loading ? (
        <div className="p-4"><Spinner /></div>
      ) : items.length === 0 ? (
        <div className="px-4 py-8 text-center text-muted text-sm">
          {search ? 'No matches' : `${listType === 'block' ? 'Blocklist' : 'Allowlist'} is empty`}
        </div>
      ) : (
        <div className="divide-y divide-gray-700 max-h-96 overflow-y-auto">
          {items.map((entry, i) => {
            const entryIp = entry.ip || entry.cidr || '—'
            const isConfirming = confirmRemove?.ip === entryIp && confirmRemove?.list === listType
            return (
              <div key={i} className="px-4 py-2.5 flex items-center gap-3 text-sm hover:bg-gray-750">
                <span className="text-gray-200 font-mono text-xs flex-1">{entryIp}</span>
                <span className="text-gray-400 text-xs max-w-[120px] truncate" title={entry.reason}>{entry.reason || '—'}</span>
                <span className="text-muted text-xs">{entry.addedBy || '—'}</span>
                <span className="text-secondary text-xs">
                  {entry.addedAt || entry.createdAt ? format(new Date(entry.addedAt || entry.createdAt), 'MMM d') : '—'}
                </span>
                {isConfirming ? (
                  <span className="flex gap-1">
                    <button onClick={() => (listType === 'block' ? removeBlock : removeAllow).mutate(entryIp)}
                            className="text-xs px-2 py-0.5 bg-red-600/20 text-red-400 rounded hover:bg-red-600/30">Remove</button>
                    <button onClick={() => setConfirmRemove(null)} className="text-xs text-muted hover:text-gray-300">
                      <XMarkIcon className="w-3.5 h-3.5" />
                    </button>
                  </span>
                ) : (
                  <button onClick={() => setConfirmRemove({ ip: entryIp, list: listType })}
                          className="text-xs px-1.5 py-0.5 bg-red-600/10 text-red-400 rounded hover:bg-red-600/20">
                    <TrashIcon className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {renderList(blockItems, blockLoading, blockSearch, setBlockSearch, 'block', () => { resetForm(); setShowAddBlock(true) })}
        {renderList(allowItems, allowLoading, allowSearch, setAllowSearch, 'allow', () => { resetForm(); setShowAddAllow(true) })}
      </div>

      {/* Add to Blocklist Modal */}
      {showAddBlock && (
        <Modal title="Add to Blocklist" onClose={() => setShowAddBlock(false)}>
          <div className="space-y-4">
            <div>
              <label className="block text-sm text-gray-300 mb-1">IP / CIDR</label>
              <input type="text" value={entryForm.ip}
                     onChange={e => setEntryForm(f => ({ ...f, ip: e.target.value }))}
                     placeholder="e.g. 203.0.113.0/24"
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm font-mono" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Reason</label>
              <input type="text" value={entryForm.reason}
                     onChange={e => setEntryForm(f => ({ ...f, reason: e.target.value }))}
                     placeholder="Why is this being blocked?"
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Expiry (optional)</label>
              <input type="datetime-local" value={entryForm.expiresAt}
                     onChange={e => setEntryForm(f => ({ ...f, expiresAt: e.target.value }))}
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <button onClick={() => setShowAddBlock(false)} className="btn-secondary">Cancel</button>
              <button onClick={() => addBlock.mutate(entryForm)} disabled={addBlock.isPending || !entryForm.ip}
                      className="btn-primary flex items-center gap-1.5">
                {addBlock.isPending && <ArrowPathIcon className="w-4 h-4 animate-spin" />}
                Block IP
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* Add to Allowlist Modal */}
      {showAddAllow && (
        <Modal title="Add to Allowlist" onClose={() => setShowAddAllow(false)}>
          <div className="space-y-4">
            <div>
              <label className="block text-sm text-gray-300 mb-1">IP / CIDR</label>
              <input type="text" value={entryForm.ip}
                     onChange={e => setEntryForm(f => ({ ...f, ip: e.target.value }))}
                     placeholder="e.g. 10.0.0.0/8"
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm font-mono" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Reason</label>
              <input type="text" value={entryForm.reason}
                     onChange={e => setEntryForm(f => ({ ...f, reason: e.target.value }))}
                     placeholder="Why should this be allowed?"
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Expiry (optional)</label>
              <input type="datetime-local" value={entryForm.expiresAt}
                     onChange={e => setEntryForm(f => ({ ...f, expiresAt: e.target.value }))}
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <button onClick={() => setShowAddAllow(false)} className="btn-secondary">Cancel</button>
              <button onClick={() => addAllow.mutate(entryForm)} disabled={addAllow.isPending || !entryForm.ip}
                      className="btn-primary flex items-center gap-1.5">
                {addAllow.isPending && <ArrowPathIcon className="w-4 h-4 animate-spin" />}
                Allow IP
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

// ── Tab 4: Geo Analysis ────────────────────────────────────────────────────

function GeoTab() {
  const [expandedCountry, setExpandedCountry] = useState(null)
  const [sortField, setSortField] = useState('risk')

  const { data: geoStats, isLoading } = useQuery({
    queryKey: ['proxy-geo-stats'],
    queryFn: api.getGeoStats,
    refetchInterval: 30000,
  })

  const { data: verdicts } = useQuery({
    queryKey: ['proxy-verdicts-geo', expandedCountry],
    queryFn: () => api.getVerdicts({ country: expandedCountry, size: 20 }),
    enabled: !!expandedCountry,
  })

  const countries = Array.isArray(geoStats) ? geoStats : geoStats?.countries || geoStats?.content || []

  const sorted = [...countries].sort((a, b) => {
    if (sortField === 'risk') return (b.riskLevel ?? b.riskScore ?? 0) - (a.riskLevel ?? a.riskScore ?? 0)
    if (sortField === 'connections') return (b.connections ?? b.totalConnections ?? 0) - (a.connections ?? a.totalConnections ?? 0)
    if (sortField === 'blocked') return (b.blocked ?? b.blockedCount ?? 0) - (a.blocked ?? a.blockedCount ?? 0)
    return 0
  })

  const countryIps = Array.isArray(verdicts) ? verdicts : verdicts?.content || []

  if (isLoading) return <Spinner />

  const riskLevel = (score) => {
    const s = typeof score === 'string' ? score : null
    if (s) return s
    const n = score ?? 0
    if (n >= 80) return 'CRITICAL'
    if (n >= 60) return 'HIGH'
    if (n >= 40) return 'MEDIUM'
    return 'LOW'
  }

  const riskBadge = (level) => {
    const l = typeof level === 'string' ? level.toUpperCase() : riskLevel(level)
    switch (l) {
      case 'CRITICAL': return 'badge badge-red'
      case 'HIGH': return 'badge badge-orange'
      case 'MEDIUM': return 'badge badge-yellow'
      default: return 'badge badge-green'
    }
  }

  return (
    <div className="space-y-6">
      {/* Sort controls */}
      <div className="flex items-center gap-3">
        <FunnelIcon className="w-4 h-4 text-muted" />
        <span className="text-muted text-sm">Sort by:</span>
        {['risk', 'connections', 'blocked'].map(field => (
          <button key={field} onClick={() => setSortField(field)}
                  className={`text-xs px-3 py-1 rounded ${sortField === field ? 'bg-blue-600/20 text-blue-400 border border-blue-600' : 'bg-gray-800 text-gray-400 border border-gray-700 hover:text-gray-200'}`}>
            {field.charAt(0).toUpperCase() + field.slice(1)}
          </button>
        ))}
      </div>

      {sorted.length === 0 ? (
        <EmptyState icon={GlobeAltIcon} message="No geo data available yet. Verdicts with geo info will appear here." />
      ) : (
        <div className="bg-gray-800 rounded-lg border border-gray-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-700 text-muted text-xs">
                <th className="px-4 py-2 text-left w-8" />
                <th className="px-4 py-2 text-left">Country</th>
                <th className="px-4 py-2 text-left">Connections</th>
                <th className="px-4 py-2 text-left">Blocked</th>
                <th className="px-4 py-2 text-left">Allowed</th>
                <th className="px-4 py-2 text-left">Risk Level</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-700">
              {sorted.map(c => {
                const countryName = c.country || c.name || c.countryCode || '—'
                const isExpanded = expandedCountry === countryName
                const connections = c.connections ?? c.totalConnections ?? 0
                const blocked = c.blocked ?? c.blockedCount ?? 0
                const allowed = c.allowed ?? c.allowedCount ?? (connections - blocked)
                const risk = c.riskLevel ?? c.riskScore ?? 0
                return (
                  <CountryRow key={countryName} countryName={countryName} connections={connections}
                              blocked={blocked} allowed={allowed} risk={risk}
                              expanded={isExpanded}
                              onToggle={() => setExpandedCountry(isExpanded ? null : countryName)}
                              riskBadge={riskBadge} riskLevel={riskLevel}
                              countryIps={isExpanded ? countryIps : []} />
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function CountryRow({ countryName, connections, blocked, allowed, risk, expanded, onToggle, riskBadge, riskLevel, countryIps }) {
  return (
    <>
      <tr className="hover:bg-gray-750 cursor-pointer" onClick={onToggle}>
        <td className="px-4 py-2">
          {expanded ? <ChevronUpIcon className="w-4 h-4 text-muted" /> : <ChevronDownIcon className="w-4 h-4 text-muted" />}
        </td>
        <td className="px-4 py-2">
          <span className="text-gray-200 flex items-center gap-2">
            <GlobeAltIcon className="w-4 h-4 text-muted" />
            {countryName}
          </span>
        </td>
        <td className="px-4 py-2 text-gray-300">{connections.toLocaleString()}</td>
        <td className="px-4 py-2 text-red-400">{blocked.toLocaleString()}</td>
        <td className="px-4 py-2 text-green-400">{allowed >= 0 ? allowed.toLocaleString() : '—'}</td>
        <td className="px-4 py-2">
          <span className={riskBadge(risk)}>{typeof risk === 'string' ? risk : riskLevel(risk)}</span>
        </td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={6} className="px-6 py-4 bg-gray-850 border-t border-gray-700">
            {countryIps.length === 0 ? (
              <div className="text-center text-muted text-sm py-3">Loading IPs from {countryName}...</div>
            ) : (
              <div className="space-y-1">
                <div className="text-xs text-muted mb-2">IPs from {countryName}</div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {countryIps.slice(0, 12).map((v, i) => (
                    <div key={i} className="flex items-center gap-2 text-xs bg-gray-900 rounded px-3 py-2 border border-gray-700">
                      <span className="font-mono text-gray-300 flex-1">{v.ip || '—'}</span>
                      <span className={VERDICT_BADGE[v.verdict] || 'badge badge-gray'}>{v.verdict || '—'}</span>
                      <span className="text-muted">Score: {v.riskScore ?? '—'}</span>
                    </div>
                  ))}
                </div>
                {countryIps.length > 12 && (
                  <div className="text-xs text-muted mt-2">...and {countryIps.length - 12} more</div>
                )}
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

// ── Main Page ──────────────────────────────────────────────────────────────

export default function ProxyIntelligence() {
  const [activeTab, setActiveTab] = useState('overview')
  const queryClient = useQueryClient()

  const {
    data: dashboard,
    isError: serviceDown,
    refetch: refetchDashboard,
    isFetching: dashboardFetching,
  } = useQuery({
    queryKey: ['proxy-dashboard-header'],
    queryFn: api.getProxyDashboard,
    refetchInterval: 30000,
    retry: 1,
  })

  const stats = dashboard?.stats || dashboard || {}

  const handleRetry = () => {
    refetchDashboard()
    queryClient.invalidateQueries({ queryKey: ['proxy-dashboard'] })
    queryClient.invalidateQueries({ queryKey: ['proxy-verdicts'] })
    queryClient.invalidateQueries({ queryKey: ['proxy-blocklist'] })
    queryClient.invalidateQueries({ queryKey: ['proxy-allowlist'] })
    queryClient.invalidateQueries({ queryKey: ['proxy-geo-stats'] })
  }

  return (
    <div className="space-y-6">
      {serviceDown && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">
              Proxy intelligence service unavailable — verdicts and lists may be stale
            </span>
          </div>
          <button
            onClick={handleRetry}
            disabled={dashboardFetching}
            className="text-xs text-red-400 hover:text-red-300 underline disabled:opacity-50"
          >
            {dashboardFetching ? 'Retrying...' : 'Retry'}
          </button>
        </div>
      )}

      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ShieldExclamationIcon className="w-6 h-6 text-orange-400" />
          <div>
            <h1 className="text-xl font-bold text-white">Proxy Intelligence</h1>
            <p className="text-muted text-sm">AI-powered proxy security — verdicts, IP analysis, and geo threat intelligence</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className={`badge ${(stats.blocked ?? 0) > 0 ? 'badge-red' : 'badge-green'}`}>
            {(stats.blocked ?? 0) > 0 ? `${stats.blocked} BLOCKED` : 'ALL CLEAR'}
          </span>
          {stats.totalVerdicts != null && (
            <span className="text-secondary text-xs">{stats.totalVerdicts} verdicts today</span>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-700">
        {TABS.map(tab => (
          <button key={tab.key} onClick={() => setActiveTab(tab.key)}
                  className={`flex items-center gap-1.5 px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-orange-500 text-orange-400'
                      : 'border-transparent text-muted hover:text-gray-300'
                  }`}>
            <tab.icon className="w-4 h-4" />
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && <OverviewTab />}
      {activeTab === 'ip-intel' && <IpIntelTab />}
      {activeTab === 'lists' && <ListsTab />}
      {activeTab === 'geo' && <GeoTab />}
    </div>
  )
}
