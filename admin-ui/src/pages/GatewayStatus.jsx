import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import {
  ShieldCheckIcon, GlobeAltIcon, ServerIcon, BoltIcon,
  ArrowsRightLeftIcon, ExclamationTriangleIcon, SignalIcon,
  PlusIcon, TrashIcon, ArrowPathIcon, EyeIcon,
  WifiIcon, LockClosedIcon, ClockIcon, CpuChipIcon,
  MagnifyingGlassIcon, ChartBarIcon, PlayIcon,
  ExclamationCircleIcon, CheckCircleIcon, XCircleIcon, NoSymbolIcon
} from '@heroicons/react/24/outline'
import { getGatewayStatus, getGatewayRoutes, getGatewayStats } from '../api/gateway'
import { getDmzHealth, getSecurityStats, listMappings, addMapping, removeMapping } from '../api/dmz'
import { getActiveTransfers, getForwarderHealth } from '../api/forwarder'
import { onboardingApi } from '../api/client'

/* ─── Tabs ─── */
const TABS = [
  { key: 'overview', label: 'Overview', icon: GlobeAltIcon },
  { key: 'security', label: 'DMZ Security', icon: ShieldCheckIcon },
  { key: 'mappings', label: 'Port Mappings', icon: ArrowsRightLeftIcon },
  { key: 'transfers', label: 'Active Transfers', icon: BoltIcon },
]

/* ─── Route colors by type ─── */
const ROUTE_TYPE_COLORS = {
  INTERNAL: { bg: 'bg-blue-50', border: 'border-blue-200', text: 'text-blue-700', badge: 'badge-blue' },
  INSTANCE: { bg: 'bg-green-50', border: 'border-green-200', text: 'text-green-700', badge: 'badge-green' },
  LEGACY: { bg: 'bg-amber-50', border: 'border-amber-200', text: 'text-amber-700', badge: 'badge-yellow' },
}

/* ─── Protocol icons ─── */
const PROTOCOL_COLORS = {
  SFTP: 'blue', FTP: 'amber', FTP_WEB: 'green', HTTPS: 'purple', AS2: 'indigo', AS4: 'pink',
  SSH: 'blue', HTTP: 'gray', TLS: 'green', UNKNOWN: 'gray',
}

export default function GatewayStatus() {
  const qc = useQueryClient()
  const [activeTab, setActiveTab] = useState('overview')
  const [showAddMapping, setShowAddMapping] = useState(false)
  const [mappingForm, setMappingForm] = useState({ name: '', listenPort: '', targetHost: '', targetPort: '' })
  const [showIpDetail, setShowIpDetail] = useState(null)
  const [ipSearch, setIpSearch] = useState('')

  // ─── Queries ───
  const { data: gwStatus } = useQuery({
    queryKey: ['gateway-status'], queryFn: getGatewayStatus, refetchInterval: 15000,
    retry: false, placeholderData: {}
  })
  const { data: gwRoutes } = useQuery({
    queryKey: ['gateway-routes'], queryFn: getGatewayRoutes, refetchInterval: 30000,
    retry: false, placeholderData: { defaultRoutes: [], instanceRoutes: [], legacyRoutes: [], totalRoutes: 0 }
  })
  const { data: gwStats } = useQuery({
    queryKey: ['gateway-stats'], queryFn: getGatewayStats, refetchInterval: 15000,
    retry: false, placeholderData: {}
  })
  const { data: dmzHealth } = useQuery({
    queryKey: ['dmz-health'], queryFn: getDmzHealth, refetchInterval: 15000,
    retry: false, placeholderData: null
  })
  const { data: securityStats } = useQuery({
    queryKey: ['dmz-security'], queryFn: getSecurityStats, refetchInterval: 10000,
    retry: false, enabled: activeTab === 'security', placeholderData: null
  })
  const { data: dmzMappings = [] } = useQuery({
    queryKey: ['dmz-mappings'], queryFn: listMappings, refetchInterval: 15000,
    retry: false, placeholderData: []
  })
  const { data: transferData } = useQuery({
    queryKey: ['active-transfers'], queryFn: getActiveTransfers, refetchInterval: 5000,
    retry: false, enabled: activeTab === 'transfers' || activeTab === 'overview',
    placeholderData: { activeCount: 0, transfers: [] }
  })
  const { data: forwarderHealth } = useQuery({
    queryKey: ['forwarder-health'], queryFn: getForwarderHealth, refetchInterval: 15000,
    retry: false, placeholderData: null
  })
  const { data: services = [] } = useQuery({
    queryKey: ['service-registry-gw'],
    queryFn: () => onboardingApi.get('/api/service-registry').then(r => r.data).catch(() => []),
    refetchInterval: 30000
  })

  // ─── Mutations ───
  const addMappingMut = useMutation({
    mutationFn: addMapping,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dmz-mappings'] })
      setShowAddMapping(false)
      setMappingForm({ name: '', listenPort: '', targetHost: '', targetPort: '' })
      toast.success('Port mapping added')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed to add mapping')
  })

  const removeMappingMut = useMutation({
    mutationFn: removeMapping,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dmz-mappings'] })
      toast.success('Port mapping removed')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed to remove mapping')
  })

  // ─── Derived data ───
  const gwUp = gwStatus?.sftpGatewayRunning !== undefined
  const dmzUp = dmzHealth?.status === 'UP'
  const fwdUp = forwarderHealth?.status === 'UP'
  const allRoutes = [
    ...(gwRoutes?.defaultRoutes || []),
    ...(gwRoutes?.instanceRoutes || []),
    ...(gwRoutes?.legacyRoutes || []),
  ]
  const metrics = securityStats?.metrics || {}
  const connections = metrics?.connections || {}
  const throughput = metrics?.throughput || {}
  const aiEngine = metrics?.aiEngine || {}

  return (
    <div className="space-y-6">
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Network & Proxy</h1>
          <p className="text-gray-500 text-sm mt-0.5">Gateway routing, DMZ security, and transfer monitoring</p>
        </div>
        <div className="flex items-center gap-3">
          {/* Service status pills */}
          {[
            { label: 'Gateway', up: gwUp, port: 8085 },
            { label: 'DMZ Proxy', up: dmzUp, port: 8088 },
            { label: 'Forwarder', up: fwdUp, port: 8087 },
          ].map(svc => (
            <div key={svc.label} className={`flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-medium ${
              svc.up ? 'bg-green-50 text-green-700 border border-green-200' : 'bg-gray-100 text-gray-500 border border-gray-200'
            }`}>
              <span className={`w-2 h-2 rounded-full ${svc.up ? 'bg-green-500 animate-pulse' : 'bg-gray-400'}`} />
              {svc.label} :{svc.port}
            </div>
          ))}
        </div>
      </div>

      {/* ─── Stats Cards ─── */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        <div className="card !p-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-blue-50"><GlobeAltIcon className="w-5 h-5 text-blue-600" /></div>
            <div>
              <p className="text-2xl font-bold text-gray-900">{gwRoutes?.totalRoutes || 0}</p>
              <p className="text-xs text-gray-500">Total Routes</p>
            </div>
          </div>
        </div>
        <div className="card !p-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-green-50"><ServerIcon className="w-5 h-5 text-green-600" /></div>
            <div>
              <p className="text-2xl font-bold text-gray-900">{gwStats?.activeInstances || 0}</p>
              <p className="text-xs text-gray-500">Server Instances</p>
            </div>
          </div>
        </div>
        <div className="card !p-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-purple-50"><ArrowsRightLeftIcon className="w-5 h-5 text-purple-600" /></div>
            <div>
              <p className="text-2xl font-bold text-gray-900">{Array.isArray(dmzMappings) ? dmzMappings.length : 0}</p>
              <p className="text-xs text-gray-500">Port Mappings</p>
            </div>
          </div>
        </div>
        <div className="card !p-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-amber-50"><BoltIcon className="w-5 h-5 text-amber-600" /></div>
            <div>
              <p className="text-2xl font-bold text-gray-900">{transferData?.activeCount || 0}</p>
              <p className="text-xs text-gray-500">Active Transfers</p>
            </div>
          </div>
        </div>
        <div className="card !p-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-red-50"><ShieldCheckIcon className="w-5 h-5 text-red-600" /></div>
            <div>
              <p className="text-2xl font-bold text-gray-900">{connections?.blocked || 0}</p>
              <p className="text-xs text-gray-500">Blocked Connections</p>
            </div>
          </div>
        </div>
      </div>

      {/* ─── Tabs ─── */}
      <div className="border-b border-gray-200">
        <div className="flex gap-6">
          {TABS.map(tab => (
            <button key={tab.key} onClick={() => setActiveTab(tab.key)}
              className={`pb-3 text-sm font-medium border-b-2 flex items-center gap-2 transition-colors ${
                activeTab === tab.key
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}>
              <tab.icon className="w-4 h-4" />
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* ═══════════════════════════════════════════════════════════
          TAB: Overview
         ═══════════════════════════════════════════════════════════ */}
      {activeTab === 'overview' && (
        <div className="space-y-6">
          {/* Architecture Diagram */}
          <div className="card">
            <h3 className="font-semibold text-gray-900 mb-4">Network Architecture</h3>
            <div className="flex items-center justify-center gap-3 text-sm overflow-x-auto py-2">
              <div className="flex flex-col items-center gap-1 min-w-[100px]">
                <div className="p-3 bg-gray-100 rounded-xl"><GlobeAltIcon className="w-6 h-6 text-gray-600" /></div>
                <span className="font-medium text-gray-700">External</span>
                <span className="text-xs text-gray-400">Internet</span>
              </div>
              <ArrowsRightLeftIcon className="w-5 h-5 text-gray-300 flex-shrink-0" />
              <div className={`flex flex-col items-center gap-1 min-w-[100px] ${dmzUp ? '' : 'opacity-40'}`}>
                <div className={`p-3 rounded-xl ${dmzUp ? 'bg-red-50' : 'bg-gray-100'}`}>
                  <ShieldCheckIcon className={`w-6 h-6 ${dmzUp ? 'text-red-600' : 'text-gray-400'}`} />
                </div>
                <span className="font-medium text-gray-700">DMZ Proxy</span>
                <span className="text-xs text-gray-400">:8088</span>
              </div>
              <ArrowsRightLeftIcon className="w-5 h-5 text-gray-300 flex-shrink-0" />
              <div className={`flex flex-col items-center gap-1 min-w-[100px] ${gwUp ? '' : 'opacity-40'}`}>
                <div className={`p-3 rounded-xl ${gwUp ? 'bg-blue-50' : 'bg-gray-100'}`}>
                  <BoltIcon className={`w-6 h-6 ${gwUp ? 'text-blue-600' : 'text-gray-400'}`} />
                </div>
                <span className="font-medium text-gray-700">Gateway</span>
                <span className="text-xs text-gray-400">:8085</span>
              </div>
              <ArrowsRightLeftIcon className="w-5 h-5 text-gray-300 flex-shrink-0" />
              <div className="flex flex-col items-center gap-1 min-w-[100px]">
                <div className="p-3 bg-green-50 rounded-xl"><ServerIcon className="w-6 h-6 text-green-600" /></div>
                <span className="font-medium text-gray-700">Protocol Services</span>
                <span className="text-xs text-gray-400">SFTP/FTP</span>
              </div>
              <ArrowsRightLeftIcon className="w-5 h-5 text-gray-300 flex-shrink-0" />
              <div className={`flex flex-col items-center gap-1 min-w-[100px] ${fwdUp ? '' : 'opacity-40'}`}>
                <div className={`p-3 rounded-xl ${fwdUp ? 'bg-amber-50' : 'bg-gray-100'}`}>
                  <BoltIcon className={`w-6 h-6 ${fwdUp ? 'text-amber-600' : 'text-gray-400'}`} />
                </div>
                <span className="font-medium text-gray-700">Forwarder</span>
                <span className="text-xs text-gray-400">:8087</span>
              </div>
              <ArrowsRightLeftIcon className="w-5 h-5 text-gray-300 flex-shrink-0" />
              <div className="flex flex-col items-center gap-1 min-w-[100px]">
                <div className="p-3 bg-purple-50 rounded-xl"><GlobeAltIcon className="w-6 h-6 text-purple-600" /></div>
                <span className="font-medium text-gray-700">External</span>
                <span className="text-xs text-gray-400">Partners</span>
              </div>
            </div>
          </div>

          {/* Route Table */}
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-semibold text-gray-900">Route Table</h3>
              <span className="text-xs text-gray-400">{allRoutes.length} routes</span>
            </div>
            {allRoutes.length === 0 ? (
              <p className="text-sm text-gray-400 text-center py-6">No routes configured — gateway may be unreachable</p>
            ) : (
              <div className="space-y-2">
                {allRoutes.map((route, i) => {
                  const colors = ROUTE_TYPE_COLORS[route.type] || ROUTE_TYPE_COLORS.INTERNAL
                  return (
                    <div key={i} className={`flex items-center gap-3 p-3 rounded-lg border ${colors.bg} ${colors.border}`}>
                      <ShieldCheckIcon className={`w-5 h-5 ${colors.text} flex-shrink-0`} />
                      <span className="font-medium text-sm text-gray-900 min-w-[140px]">{route.name}</span>
                      <span className={`badge ${colors.badge}`}>{route.protocol}</span>
                      <span className="text-gray-400 text-sm">→</span>
                      <span className="font-mono text-xs text-gray-600">{route.targetHost}:{route.targetPort}</span>
                      {route.instanceId && <span className="text-xs text-gray-400">({route.instanceId})</span>}
                      <span className={`ml-auto badge ${route.active ? 'badge-green' : 'badge-red'}`}>
                        {route.active ? 'Active' : 'Inactive'}
                      </span>
                      <span className={`badge ${colors.badge}`}>{route.type}</span>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          {/* DMZ Port Mappings Quick View */}
          {Array.isArray(dmzMappings) && dmzMappings.length > 0 && (
            <div className="card">
              <div className="flex items-center justify-between mb-4">
                <h3 className="font-semibold text-gray-900">DMZ Port Mappings</h3>
                <button onClick={() => setActiveTab('mappings')} className="text-xs text-blue-600 hover:text-blue-800">
                  View All →
                </button>
              </div>
              <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
                {dmzMappings.slice(0, 6).map((m, i) => (
                  <div key={m.name || i} className="flex items-center gap-3 p-3 bg-gray-50 rounded-lg">
                    <div className="p-2 bg-purple-100 rounded-lg"><ArrowsRightLeftIcon className="w-4 h-4 text-purple-600" /></div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-gray-900 truncate">{m.name}</p>
                      <p className="text-xs text-gray-500 font-mono">:{m.listenPort} → {m.targetHost}:{m.targetPort}</p>
                    </div>
                    <span className={`w-2 h-2 rounded-full ${m.active !== false ? 'bg-green-500' : 'bg-gray-400'}`} />
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════
          TAB: DMZ Security
         ═══════════════════════════════════════════════════════════ */}
      {activeTab === 'security' && (
        <div className="space-y-6">
          {!securityStats ? (
            <div className="card text-center py-12">
              <ShieldCheckIcon className="w-10 h-10 text-gray-300 mx-auto mb-3" />
              <p className="text-gray-500 text-sm">DMZ security stats unavailable</p>
              <p className="text-gray-400 text-xs mt-1">Ensure dmz-proxy is running on port 8088</p>
            </div>
          ) : (
            <>
              {/* Connection Stats */}
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                {[
                  { label: 'Total Connections', value: connections.total || 0, icon: SignalIcon, color: 'blue' },
                  { label: 'Allowed', value: connections.allowed || 0, icon: CheckCircleIcon, color: 'green' },
                  { label: 'Throttled', value: connections.throttled || 0, icon: ClockIcon, color: 'amber' },
                  { label: 'Blocked', value: connections.blocked || 0, icon: XCircleIcon, color: 'red' },
                ].map(stat => (
                  <div key={stat.label} className="card !p-4">
                    <div className="flex items-center gap-3">
                      <div className={`p-2 rounded-lg bg-${stat.color}-50`}>
                        <stat.icon className={`w-5 h-5 text-${stat.color}-600`} />
                      </div>
                      <div>
                        <p className="text-2xl font-bold text-gray-900">{stat.value.toLocaleString()}</p>
                        <p className="text-xs text-gray-500">{stat.label}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              {/* Security Details Grid */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Verdict Actions */}
                <div className="card">
                  <h3 className="font-semibold text-gray-900 mb-4">AI Verdict Summary</h3>
                  <div className="space-y-3">
                    {[
                      { action: 'ALLOW', count: connections.allowed, color: 'green', icon: CheckCircleIcon },
                      { action: 'THROTTLE', count: connections.throttled, color: 'amber', icon: ClockIcon },
                      { action: 'BLOCK', count: connections.blocked, color: 'red', icon: XCircleIcon },
                      { action: 'BLACKHOLE', count: connections.blackholed, color: 'gray', icon: NoSymbolIcon },
                      { action: 'RATE_LIMITED', count: connections.rateLimited, color: 'purple', icon: ExclamationCircleIcon },
                    ].map(item => {
                      const total = connections.total || 1
                      const pct = ((item.count || 0) / total * 100).toFixed(1)
                      return (
                        <div key={item.action} className="flex items-center gap-3">
                          <item.icon className={`w-4 h-4 text-${item.color}-500 flex-shrink-0`} />
                          <span className="text-sm text-gray-700 w-24">{item.action}</span>
                          <div className="flex-1 bg-gray-100 rounded-full h-2">
                            <div className={`bg-${item.color}-500 h-2 rounded-full`}
                              style={{ width: `${Math.min(parseFloat(pct), 100)}%` }} />
                          </div>
                          <span className="text-xs text-gray-500 w-20 text-right">{(item.count || 0).toLocaleString()} ({pct}%)</span>
                        </div>
                      )
                    })}
                  </div>
                </div>

                {/* AI Engine Cache */}
                <div className="card">
                  <h3 className="font-semibold text-gray-900 mb-4">AI Engine Performance</h3>
                  <div className="space-y-4">
                    <div className="grid grid-cols-3 gap-4">
                      <div className="text-center">
                        <p className="text-2xl font-bold text-gray-900">{(aiEngine.verdictRequests || 0).toLocaleString()}</p>
                        <p className="text-xs text-gray-500">Verdict Requests</p>
                      </div>
                      <div className="text-center">
                        <p className="text-2xl font-bold text-green-600">{aiEngine.cacheHitRate || '0%'}</p>
                        <p className="text-xs text-gray-500">Cache Hit Rate</p>
                      </div>
                      <div className="text-center">
                        <p className="text-2xl font-bold text-amber-600">{aiEngine.fallbacks || 0}</p>
                        <p className="text-xs text-gray-500">Fallbacks</p>
                      </div>
                    </div>
                    <div className="bg-gray-50 rounded-lg p-3">
                      <p className="text-xs text-gray-500 mb-1">DMZ Features</p>
                      <div className="flex flex-wrap gap-1.5">
                        {(dmzHealth?.features || []).map(f => (
                          <span key={f} className="badge badge-blue">{f.replace(/_/g, ' ')}</span>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>

                {/* Throughput */}
                <div className="card">
                  <h3 className="font-semibold text-gray-900 mb-4">Throughput</h3>
                  <div className="grid grid-cols-3 gap-4">
                    <div className="text-center p-3 bg-blue-50 rounded-lg">
                      <p className="text-lg font-bold text-blue-700">
                        {formatBytes(throughput.totalBytesIn || 0)}
                      </p>
                      <p className="text-xs text-blue-500">Bytes In</p>
                    </div>
                    <div className="text-center p-3 bg-green-50 rounded-lg">
                      <p className="text-lg font-bold text-green-700">
                        {formatBytes(throughput.totalBytesOut || 0)}
                      </p>
                      <p className="text-xs text-green-500">Bytes Out</p>
                    </div>
                    <div className="text-center p-3 bg-purple-50 rounded-lg">
                      <p className="text-lg font-bold text-purple-700">
                        {formatBytes(throughput.totalBytes || 0)}
                      </p>
                      <p className="text-xs text-purple-500">Total</p>
                    </div>
                  </div>
                </div>

                {/* Protocol Distribution */}
                <div className="card">
                  <h3 className="font-semibold text-gray-900 mb-4">Protocol Distribution</h3>
                  {metrics.protocols && Object.keys(metrics.protocols).length > 0 ? (
                    <div className="space-y-2">
                      {Object.entries(metrics.protocols).map(([proto, count]) => {
                        const color = PROTOCOL_COLORS[proto] || 'gray'
                        return (
                          <div key={proto} className="flex items-center gap-3">
                            <span className={`badge badge-${color} min-w-[60px] text-center`}>{proto}</span>
                            <div className="flex-1 bg-gray-100 rounded-full h-2">
                              <div className={`bg-${color}-500 h-2 rounded-full`}
                                style={{ width: `${Math.min((count / (connections.total || 1)) * 100, 100)}%` }} />
                            </div>
                            <span className="text-xs text-gray-500 w-16 text-right">{count.toLocaleString()}</span>
                          </div>
                        )
                      })}
                    </div>
                  ) : (
                    <p className="text-sm text-gray-400 text-center py-4">No protocol data available</p>
                  )}
                </div>
              </div>
            </>
          )}
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════
          TAB: Port Mappings
         ═══════════════════════════════════════════════════════════ */}
      {activeTab === 'mappings' && (
        <div className="space-y-6">
          <div className="flex items-center justify-between">
            <p className="text-sm text-gray-500">{Array.isArray(dmzMappings) ? dmzMappings.length : 0} active port mapping{dmzMappings?.length !== 1 ? 's' : ''} on DMZ proxy</p>
            <button className="btn-primary" onClick={() => setShowAddMapping(true)}>
              <PlusIcon className="w-4 h-4" /> Add Mapping
            </button>
          </div>

          {!dmzUp && (
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-800">
              <ExclamationTriangleIcon className="w-4 h-4 inline mr-2" />
              DMZ Proxy is not reachable. Port mappings cannot be managed.
            </div>
          )}

          {!Array.isArray(dmzMappings) || dmzMappings.length === 0 ? (
            <EmptyState
              emoji="🔌"
              title="No port mappings"
              description="DMZ proxy has no port forwarding rules configured."
              actionLabel="Add Mapping"
              onAction={() => setShowAddMapping(true)}
            />
          ) : (
            <div className="card !p-0 overflow-hidden">
              <table className="w-full">
                <thead>
                  <tr className="border-b bg-gray-50/50">
                    <th className="table-header">Name</th>
                    <th className="table-header">Listen Port</th>
                    <th className="table-header">Target</th>
                    <th className="table-header">Connections</th>
                    <th className="table-header">Bytes Forwarded</th>
                    <th className="table-header">Status</th>
                    <th className="table-header text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {dmzMappings.map((m, i) => (
                    <tr key={m.name || i} className="table-row">
                      <td className="table-cell font-medium text-gray-900">{m.name}</td>
                      <td className="table-cell">
                        <span className="font-mono text-xs px-2 py-1 bg-blue-50 text-blue-700 rounded-md">:{m.listenPort}</span>
                      </td>
                      <td className="table-cell font-mono text-xs text-gray-600">{m.targetHost}:{m.targetPort}</td>
                      <td className="table-cell">
                        <span className="text-sm font-medium">{m.activeConnections ?? 0}</span>
                      </td>
                      <td className="table-cell text-xs text-gray-500">{formatBytes(m.bytesForwarded || 0)}</td>
                      <td className="table-cell">
                        <span className={`flex items-center gap-1.5 text-xs font-medium ${
                          m.active !== false ? 'text-green-700' : 'text-gray-500'
                        }`}>
                          <span className={`w-2 h-2 rounded-full ${m.active !== false ? 'bg-green-500 animate-pulse' : 'bg-gray-400'}`} />
                          {m.active !== false ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td className="table-cell text-right">
                        <button onClick={() => {
                          if (confirm(`Remove mapping "${m.name}"?`)) removeMappingMut.mutate(m.name)
                        }} className="p-1.5 rounded-lg hover:bg-red-50 text-gray-400 hover:text-red-600" title="Remove">
                          <TrashIcon className="w-4 h-4" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Add Mapping Modal */}
          {showAddMapping && (
            <Modal title="Add Port Mapping" onClose={() => setShowAddMapping(false)}>
              <form onSubmit={e => {
                e.preventDefault()
                addMappingMut.mutate({
                  name: mappingForm.name,
                  listenPort: Number(mappingForm.listenPort),
                  targetHost: mappingForm.targetHost,
                  targetPort: Number(mappingForm.targetPort),
                })
              }} className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Mapping Name <span className="text-red-500">*</span></label>
                  <input value={mappingForm.name} onChange={e => setMappingForm(p => ({ ...p, name: e.target.value }))}
                    placeholder="sftp-partner-acme" required />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Listen Port <span className="text-red-500">*</span></label>
                    <input type="number" value={mappingForm.listenPort} onChange={e => setMappingForm(p => ({ ...p, listenPort: e.target.value }))}
                      placeholder="2222" min="1" max="65535" required />
                    <p className="text-xs text-gray-400 mt-1">External-facing port on DMZ</p>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Target Port <span className="text-red-500">*</span></label>
                    <input type="number" value={mappingForm.targetPort} onChange={e => setMappingForm(p => ({ ...p, targetPort: e.target.value }))}
                      placeholder="2220" min="1" max="65535" required />
                  </div>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Target Host <span className="text-red-500">*</span></label>
                  <input value={mappingForm.targetHost} onChange={e => setMappingForm(p => ({ ...p, targetHost: e.target.value }))}
                    placeholder="gateway-service" required />
                  <p className="text-xs text-gray-400 mt-1">Internal hostname or IP</p>
                </div>
                <div className="flex gap-3 justify-end pt-3 border-t">
                  <button type="button" className="btn-secondary" onClick={() => setShowAddMapping(false)}>Cancel</button>
                  <button type="submit" className="btn-primary" disabled={addMappingMut.isPending}>
                    {addMappingMut.isPending ? 'Adding...' : 'Add Mapping'}
                  </button>
                </div>
              </form>
            </Modal>
          )}
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════
          TAB: Active Transfers
         ═══════════════════════════════════════════════════════════ */}
      {activeTab === 'transfers' && (
        <div className="space-y-6">
          <div className="flex items-center justify-between">
            <p className="text-sm text-gray-500">
              {transferData?.activeCount || 0} active transfer{transferData?.activeCount !== 1 ? 's' : ''} — stall timeout: {transferData?.stallTimeoutSeconds || 30}s
            </p>
            <button className="btn-secondary" onClick={() => qc.invalidateQueries({ queryKey: ['active-transfers'] })}>
              <ArrowPathIcon className="w-4 h-4" /> Refresh
            </button>
          </div>

          {!fwdUp && (
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-800">
              <ExclamationTriangleIcon className="w-4 h-4 inline mr-2" />
              External Forwarder is not reachable. Transfer monitoring unavailable.
            </div>
          )}

          {!transferData?.transfers?.length ? (
            <EmptyState
              emoji="⚡"
              title="No active transfers"
              description="File transfers will appear here in real-time while in progress."
            />
          ) : (
            <div className="space-y-3">
              {transferData.transfers.map(t => {
                const pct = t.progressPercent || 0
                const isStalled = t.stalled
                return (
                  <div key={t.transferId} className={`card !p-4 border-l-4 ${
                    isStalled ? 'border-l-red-500 bg-red-50/30' : 'border-l-blue-500'
                  }`}>
                    <div className="flex items-start justify-between mb-3">
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-xs font-bold text-gray-900">{t.transferId}</span>
                          {isStalled && <span className="badge badge-red">STALLED</span>}
                          {!isStalled && <span className="badge badge-blue">IN PROGRESS</span>}
                        </div>
                        <p className="text-sm text-gray-600 mt-1">
                          <span className="font-medium">{t.filename}</span> → {t.endpoint}
                        </p>
                      </div>
                      <div className="text-right text-xs text-gray-500">
                        <p>{formatBytes(t.bytesTransferred)} / {formatBytes(t.totalBytes)}</p>
                        <p className="mt-0.5">Elapsed: {t.elapsedSeconds}s | Idle: {t.idleSeconds}s</p>
                      </div>
                    </div>
                    {/* Progress bar */}
                    <div className="w-full bg-gray-200 rounded-full h-2.5">
                      <div className={`h-2.5 rounded-full transition-all ${
                        isStalled ? 'bg-red-500' : pct >= 100 ? 'bg-green-500' : 'bg-blue-500'
                      }`} style={{ width: `${Math.min(pct, 100)}%` }} />
                    </div>
                    <p className="text-xs text-gray-500 mt-1">{pct}% complete</p>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/* ─── Helpers ─── */
function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}
