import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import Modal from '../components/Modal'
import StatCard from '../components/StatCard'
import ListenerCard from '../components/dmz/ListenerCard'
import IpRulePanel from '../components/dmz/IpRulePanel'
import IpDetailCard from '../components/dmz/IpDetailCard'
import BackendHealthCard from '../components/dmz/BackendHealthCard'
import QosBar from '../components/dmz/QosBar'
import ReachabilityTester from '../components/dmz/ReachabilityTester'
import ZoneCheckForm from '../components/dmz/ZoneCheckForm'
import DmzTerminal from '../components/dmz/DmzTerminal'
import {
  ShieldCheckIcon, GlobeAltIcon, ServerIcon, BoltIcon,
  ArrowsRightLeftIcon, ExclamationTriangleIcon, SignalIcon,
  PlusIcon, TrashIcon, EyeIcon,
  KeyIcon, ChartBarIcon, CommandLineIcon,
  ShieldExclamationIcon, DocumentTextIcon,
  MagnifyingGlassIcon, CheckCircleIcon, XCircleIcon,
} from '@heroicons/react/24/outline'
import * as dmzApi from '../api/dmz'

/* ─── Tabs ─── */
const TABS = [
  { key: 'overview', label: 'Overview', icon: GlobeAltIcon },
  { key: 'security', label: 'Security', icon: ShieldCheckIcon },
  { key: 'mappings', label: 'Port Mappings', icon: ArrowsRightLeftIcon },
  { key: 'ip', label: 'IP Management', icon: ShieldExclamationIcon },
  { key: 'health', label: 'Backend Health', icon: ServerIcon },
  { key: 'audit', label: 'Audit & Egress', icon: DocumentTextIcon },
  { key: 'diagnostics', label: 'Diagnostics', icon: CommandLineIcon },
]

export default function DmzProxy() {
  const qc = useQueryClient()
  const [activeTab, setActiveTab] = useState('overview')
  const [showAddMapping, setShowAddMapping] = useState(false)
  const [showSecurityPolicy, setShowSecurityPolicy] = useState(null)
  const [ipSearch, setIpSearch] = useState('')
  const [selectedMapping, setSelectedMapping] = useState('')
  const [controlKey, setControlKey] = useState(() => localStorage.getItem('controlKey') || '')
  const [mappingForm, setMappingForm] = useState({
    name: '', listenPort: '', targetHost: '', targetPort: '',
    qosEnabled: false, qosMaxBytesPerSecond: '', qosPerConnectionMaxBytesPerSecond: '',
    qosPriority: 5, qosBurstAllowancePercent: 20,
  })
  const [policyForm, setPolicyForm] = useState({
    securityTier: 'AI', rateLimitPerMinute: 60, maxConcurrent: 20,
  })

  // ─── Queries ───
  const { data: health } = useQuery({
    queryKey: ['dmz-health'], queryFn: dmzApi.getDmzHealth,
    refetchInterval: 15000, retry: false, placeholderData: null,
  })
  const { data: mappings = [] } = useQuery({
    queryKey: ['dmz-mappings'], queryFn: dmzApi.listMappings,
    refetchInterval: 15000, retry: false, placeholderData: [],
  })
  const { data: listeners = [] } = useQuery({
    queryKey: ['dmz-listeners'], queryFn: dmzApi.getListeners,
    refetchInterval: 10000, retry: false, placeholderData: [],
  })
  const { data: securityStats } = useQuery({
    queryKey: ['dmz-security-stats'], queryFn: dmzApi.getSecurityStats,
    refetchInterval: 10000, retry: false,
    enabled: activeTab === 'security' || activeTab === 'overview',
    placeholderData: null,
  })
  const { data: securitySummary } = useQuery({
    queryKey: ['dmz-security-summary'], queryFn: dmzApi.getSecuritySummary,
    refetchInterval: 10000, retry: false, placeholderData: null,
  })
  const { data: backendHealth } = useQuery({
    queryKey: ['dmz-backend-health'], queryFn: dmzApi.getBackendHealth,
    refetchInterval: 15000, retry: false,
    enabled: activeTab === 'health' || activeTab === 'overview',
    placeholderData: null,
  })
  const { data: qosStats } = useQuery({
    queryKey: ['dmz-qos'], queryFn: dmzApi.getQosStats,
    refetchInterval: 10000, retry: false,
    enabled: activeTab === 'health',
    placeholderData: null,
  })
  const { data: auditStats } = useQuery({
    queryKey: ['dmz-audit'], queryFn: dmzApi.getAuditStats,
    refetchInterval: 30000, retry: false,
    enabled: activeTab === 'audit',
    placeholderData: null,
  })
  const { data: egressStats } = useQuery({
    queryKey: ['dmz-egress'], queryFn: dmzApi.getEgressStats,
    refetchInterval: 30000, retry: false,
    enabled: activeTab === 'audit',
    placeholderData: null,
  })
  const { data: zoneRules } = useQuery({
    queryKey: ['dmz-zones'], queryFn: dmzApi.getZoneRules,
    refetchInterval: 30000, retry: false,
    enabled: activeTab === 'audit',
    placeholderData: null,
  })
  const { data: connectionStats } = useQuery({
    queryKey: ['dmz-connections'], queryFn: dmzApi.getConnectionStats,
    refetchInterval: 10000, retry: false,
    enabled: activeTab === 'security',
    placeholderData: null,
  })
  const { data: ipDetail } = useQuery({
    queryKey: ['dmz-ip', ipSearch], queryFn: () => dmzApi.getIpIntelligence(ipSearch),
    enabled: !!ipSearch && ipSearch.length >= 7,
    retry: false, placeholderData: null,
  })

  // ─── Mutations ───
  const addMappingMut = useMutation({
    mutationFn: dmzApi.addMapping,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dmz-mappings'] })
      qc.invalidateQueries({ queryKey: ['dmz-listeners'] })
      setShowAddMapping(false)
      setMappingForm({ name: '', listenPort: '', targetHost: '', targetPort: '', qosEnabled: false, qosMaxBytesPerSecond: '', qosPerConnectionMaxBytesPerSecond: '', qosPriority: 5, qosBurstAllowancePercent: 20 })
      toast.success('Port mapping added')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed to add mapping'),
  })
  const removeMappingMut = useMutation({
    mutationFn: dmzApi.removeMapping,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dmz-mappings'] })
      qc.invalidateQueries({ queryKey: ['dmz-listeners'] })
      toast.success('Port mapping removed')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed to remove mapping'),
  })
  const updatePolicyMut = useMutation({
    mutationFn: ({ name, policy }) => dmzApi.updateSecurityPolicy(name, policy),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['dmz-mappings'] })
      setShowSecurityPolicy(null)
      toast.success('Security policy updated')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed to update policy'),
  })
  const addBlacklistMut = useMutation({
    mutationFn: ({ name, ip }) => dmzApi.addBlacklistIp(name, ip),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['dmz-mappings'] }); toast.success('IP blacklisted') },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed'),
  })
  const removeBlacklistMut = useMutation({
    mutationFn: ({ name, ip }) => dmzApi.removeBlacklistIp(name, ip),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['dmz-mappings'] }); toast.success('IP removed from blacklist') },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed'),
  })
  const addWhitelistMut = useMutation({
    mutationFn: ({ name, ip }) => dmzApi.addWhitelistIp(name, ip),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['dmz-mappings'] }); toast.success('IP whitelisted') },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed'),
  })
  const removeWhitelistMut = useMutation({
    mutationFn: ({ name, ip }) => dmzApi.removeWhitelistIp(name, ip),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['dmz-mappings'] }); toast.success('IP removed from whitelist') },
    onError: (err) => toast.error(err.response?.data?.message || 'Failed'),
  })
  const flushAuditMut = useMutation({
    mutationFn: dmzApi.flushAudit,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['dmz-audit'] }); toast.success('Audit log flushed') },
    onError: (err) => toast.error(err.response?.data?.message || 'Flush failed'),
  })

  // ─── Derived ───
  const dmzUp = health?.status === 'UP' || health?.status === 'DEGRADED'
  const degraded = health?.status === 'DEGRADED'
  const totalConns = securitySummary?.activeConnections || 0
  const blockedCount = securityStats?.metrics?.connectionSummary?.blocked || 0
  const unhealthyCount = backendHealth?.unhealthy?.length || 0
  const features = health?.features || []

  const handleSaveKey = () => {
    localStorage.setItem('controlKey', controlKey)
    qc.invalidateQueries()
    toast.success('Control key saved')
  }

  const handleAddMapping = (e) => {
    e.preventDefault()
    const { name, listenPort, targetHost, targetPort } = mappingForm
    if (!name || !listenPort || !targetHost || !targetPort) { toast.error('All fields required'); return }
    const payload = { name, listenPort: Number(listenPort), targetHost, targetPort: Number(targetPort) }
    if (mappingForm.qosEnabled) {
      payload.qosPolicy = {
        enabled: true,
        maxBytesPerSecond: mappingForm.qosMaxBytesPerSecond ? Number(mappingForm.qosMaxBytesPerSecond) * 1048576 : 0,
        perConnectionMaxBytesPerSecond: mappingForm.qosPerConnectionMaxBytesPerSecond ? Number(mappingForm.qosPerConnectionMaxBytesPerSecond) * 1048576 : 0,
        priority: Number(mappingForm.qosPriority) || 5,
        burstAllowancePercent: Number(mappingForm.qosBurstAllowancePercent) || 20,
      }
    }
    addMappingMut.mutate(payload)
  }

  // Get selected mapping data for IP management tab
  const selectedMappingData = mappings.find(m => m.name === selectedMapping)

  return (
    <div className="p-6 space-y-6">
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">DMZ Proxy Dashboard</h1>
          <p className="text-sm text-gray-500 mt-0.5">Full visibility into the DMZ zone — security, mappings, and diagnostics</p>
        </div>
        <div className="flex items-center gap-3">
          <span className={`px-3 py-1 rounded-full text-xs font-medium ${
            dmzUp ? (degraded ? 'bg-amber-100 text-amber-700' : 'bg-green-100 text-green-700') : 'bg-red-100 text-red-700'
          }`}>
            <span className={`inline-block w-2 h-2 rounded-full mr-1.5 ${
              dmzUp ? (degraded ? 'bg-amber-500' : 'bg-green-500 animate-pulse') : 'bg-red-500'
            }`} />
            {dmzUp ? (degraded ? 'Degraded' : 'Healthy') : 'Offline'}
          </span>
          {health?.securityEnabled && (
            <span className="px-2 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-700">
              AI Security ON
            </span>
          )}
        </div>
      </div>

      {/* ─── Control Key ─── */}
      <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
        <div className="flex items-center gap-3">
          <KeyIcon className="w-5 h-5 text-amber-600 flex-shrink-0" />
          <div className="flex-1 flex items-center gap-2">
            <span className="text-sm font-medium text-amber-800">X-Internal-Key</span>
            <input
              type="password"
              value={controlKey}
              onChange={(e) => setControlKey(e.target.value)}
              placeholder="Enter control key"
              className="flex-1 px-3 py-1.5 border border-amber-300 rounded-lg text-sm font-mono bg-white focus:outline-none focus:ring-2 focus:ring-amber-400"
            />
            <button onClick={handleSaveKey}
              className="px-3 py-1.5 bg-amber-600 text-white rounded-lg text-sm font-medium hover:bg-amber-700 transition-colors">
              Save
            </button>
          </div>
        </div>
      </div>

      {/* ─── Stat Cards ─── */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        <StatCard title="Proxy Status" value={dmzUp ? (degraded ? 'Degraded' : 'Healthy') : 'Offline'}
          icon={SignalIcon} color={dmzUp ? (degraded ? 'amber' : 'green') : 'red'}
          subtitle={health?.service || 'dmz-proxy'} />
        <StatCard title="Port Mappings" value={mappings.length}
          icon={ArrowsRightLeftIcon} color="blue"
          subtitle={`${listeners.filter(l => l.bound).length} listening`} />
        <StatCard title="Active Connections" value={totalConns}
          icon={BoltIcon} color="purple"
          subtitle={securitySummary?.trackedIps ? `${securitySummary.trackedIps} tracked IPs` : ''} />
        <StatCard title="Blocked" value={blockedCount}
          icon={ShieldExclamationIcon} color="red"
          subtitle="threats blocked" />
        <StatCard title="Unhealthy Backends" value={unhealthyCount}
          icon={ExclamationTriangleIcon} color={unhealthyCount > 0 ? 'red' : 'green'}
          subtitle={unhealthyCount > 0 ? backendHealth?.unhealthy?.join(', ') : 'All healthy'} />
      </div>

      {/* ─── Tab Bar ─── */}
      <div className="border-b border-gray-200 overflow-x-auto">
        <nav className="flex gap-1 -mb-px">
          {TABS.map(tab => (
            <button key={tab.key} onClick={() => setActiveTab(tab.key)}
              className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors whitespace-nowrap ${
                activeTab === tab.key
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}>
              <tab.icon className="w-4 h-4" />
              {tab.label}
            </button>
          ))}
        </nav>
      </div>

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 1 — Overview */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'overview' && (
        <div className="space-y-6">
          {/* Listeners Grid */}
          <div>
            <h2 className="text-lg font-semibold text-gray-900 mb-3">Listeners</h2>
            {listeners.length === 0 ? (
              <p className="text-sm text-gray-400 py-8 text-center">No listeners active — add a port mapping to get started</p>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {listeners.map(l => <ListenerCard key={l.name} listener={l} />)}
              </div>
            )}
          </div>

          {/* Features */}
          {features.length > 0 && (
            <div>
              <h2 className="text-lg font-semibold text-gray-900 mb-3">Active Features</h2>
              <div className="flex flex-wrap gap-2">
                {features.map(f => (
                  <span key={f} className="px-3 py-1.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700 border border-blue-100">
                    {f.replace(/_/g, ' ')}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Quick Mapping Summary */}
          {mappings.length > 0 && (
            <div>
              <h2 className="text-lg font-semibold text-gray-900 mb-3">Mapping Summary</h2>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                {mappings.map(m => (
                  <div key={m.name} className="rounded-lg border border-gray-200 bg-white p-3 flex items-center justify-between">
                    <div>
                      <p className="text-sm font-medium text-gray-900">{m.name}</p>
                      <p className="text-xs font-mono text-gray-500">:{m.listenPort} → {m.targetHost}:{m.targetPort}</p>
                    </div>
                    <div className="text-right">
                      <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium ${
                        m.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                      }`}>
                        {m.active ? 'Active' : 'Down'}
                      </span>
                      <p className="text-xs text-gray-400 mt-0.5">{m.activeConnections || 0} conns</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 2 — Security */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'security' && (
        <div className="space-y-6">
          {!securityStats?.securityEnabled ? (
            <div className="text-center py-12 text-gray-400">
              <ShieldCheckIcon className="w-12 h-12 mx-auto mb-3 text-gray-300" />
              <p className="text-sm">Security layer is disabled on this proxy</p>
            </div>
          ) : (
            <>
              {/* Security Stat Cards */}
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                <StatCard title="Total Requests" value={securityStats?.metrics?.connectionSummary?.total || 0}
                  icon={ChartBarIcon} color="blue" />
                <StatCard title="Allowed" value={securityStats?.metrics?.connectionSummary?.allowed || 0}
                  icon={CheckCircleIcon} color="green" />
                <StatCard title="Throttled" value={securityStats?.metrics?.connectionSummary?.throttled || 0}
                  icon={ExclamationTriangleIcon} color="amber" />
                <StatCard title="Blocked" value={securityStats?.metrics?.connectionSummary?.blocked || 0}
                  icon={XCircleIcon} color="red" />
              </div>

              {/* AI Engine Status */}
              <div className="bg-white rounded-xl border border-gray-200 p-5">
                <h3 className="text-sm font-semibold text-gray-900 mb-3">AI Engine</h3>
                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm">
                  <div>
                    <span className="text-gray-500 text-xs">Status</span>
                    <p className={`font-medium ${securityStats?.aiEngine?.available ? 'text-green-600' : 'text-red-600'}`}>
                      {securityStats?.aiEngine?.available ? 'Available' : 'Unavailable'}
                    </p>
                  </div>
                  <div>
                    <span className="text-gray-500 text-xs">Verdict Cache</span>
                    <p className="font-medium text-gray-900">{securityStats?.aiEngine?.verdictCacheSize || 0}</p>
                  </div>
                  <div>
                    <span className="text-gray-500 text-xs">Pending Events</span>
                    <p className="font-medium text-gray-900">{securityStats?.aiEngine?.pendingEvents || 0}</p>
                  </div>
                  <div>
                    <span className="text-gray-500 text-xs">Tracked IPs</span>
                    <p className="font-medium text-gray-900">{securitySummary?.trackedIps || 0}</p>
                  </div>
                </div>
              </div>

              {/* Connection Stats */}
              {connectionStats && !connectionStats.securityEnabled === false && (
                <div className="bg-white rounded-xl border border-gray-200 p-5">
                  <h3 className="text-sm font-semibold text-gray-900 mb-3">Connection Tracker</h3>
                  <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm">
                    {connectionStats.activeConnections !== undefined && (
                      <div>
                        <span className="text-gray-500 text-xs">Active</span>
                        <p className="font-bold text-gray-900">{connectionStats.activeConnections}</p>
                      </div>
                    )}
                    {connectionStats.totalConnections !== undefined && (
                      <div>
                        <span className="text-gray-500 text-xs">Total</span>
                        <p className="font-bold text-gray-900">{connectionStats.totalConnections}</p>
                      </div>
                    )}
                    {connectionStats.trackedIps !== undefined && (
                      <div>
                        <span className="text-gray-500 text-xs">Tracked IPs</span>
                        <p className="font-bold text-gray-900">{connectionStats.trackedIps}</p>
                      </div>
                    )}
                  </div>

                  {/* Top IPs */}
                  {connectionStats.topIps && connectionStats.topIps.length > 0 && (
                    <div className="mt-4">
                      <h4 className="text-xs font-semibold text-gray-500 mb-2 uppercase tracking-wider">Top Connected IPs</h4>
                      <div className="overflow-x-auto">
                        <table className="w-full text-sm text-left">
                          <thead>
                            <tr className="border-b border-gray-100">
                              <th className="py-2 text-xs font-medium text-gray-500">IP</th>
                              <th className="py-2 text-xs font-medium text-gray-500">Connections</th>
                              <th className="py-2 text-xs font-medium text-gray-500">Bytes</th>
                              <th className="py-2 text-xs font-medium text-gray-500">Actions</th>
                            </tr>
                          </thead>
                          <tbody>
                            {connectionStats.topIps.map(ip => (
                              <tr key={ip.ip} className="border-b border-gray-50 hover:bg-gray-50">
                                <td className="py-2 font-mono text-blue-700">{ip.ip}</td>
                                <td className="py-2">{ip.connections}</td>
                                <td className="py-2">{((ip.bytes || 0) / 1048576).toFixed(1)} MB</td>
                                <td className="py-2">
                                  <button onClick={() => { setIpSearch(ip.ip); setActiveTab('ip') }}
                                    className="text-blue-600 hover:text-blue-800 text-xs">
                                    <EyeIcon className="w-4 h-4 inline" /> Details
                                  </button>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Rate Limiter Stats */}
              {securityStats?.rateLimiter && (
                <div className="bg-white rounded-xl border border-gray-200 p-5">
                  <h3 className="text-sm font-semibold text-gray-900 mb-3">Rate Limiter</h3>
                  <div className="grid grid-cols-2 lg:grid-cols-3 gap-4 text-sm">
                    {Object.entries(securityStats.rateLimiter).map(([key, val]) => (
                      <div key={key}>
                        <span className="text-gray-500 text-xs">{key}</span>
                        <p className="font-medium text-gray-900">{typeof val === 'object' ? JSON.stringify(val) : String(val)}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 3 — Port Mappings */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'mappings' && (
        <div className="space-y-4">
          <div className="flex justify-end">
            <button onClick={() => setShowAddMapping(true)}
              className="flex items-center gap-1.5 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors">
              <PlusIcon className="w-4 h-4" /> Add Mapping
            </button>
          </div>

          <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
            {mappings.length === 0 ? (
              <div className="text-center py-16 text-gray-400">
                <ArrowsRightLeftIcon className="w-12 h-12 mx-auto mb-3 text-gray-300" />
                <p className="text-sm">No port mappings configured</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left">
                  <thead>
                    <tr className="bg-gray-50 border-b border-gray-200">
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Name</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Listen</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Target</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Tier</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">QoS</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Connections</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Bytes</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Status</th>
                      <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {mappings.map(m => (
                      <tr key={m.name} className="border-b border-gray-100 hover:bg-gray-50">
                        <td className="px-4 py-3 text-sm font-medium text-gray-900">{m.name}</td>
                        <td className="px-4 py-3">
                          <span className="px-2 py-0.5 rounded-md text-xs font-mono font-medium bg-blue-50 text-blue-700">:{m.listenPort}</span>
                        </td>
                        <td className="px-4 py-3 text-sm font-mono text-gray-600">{m.targetHost}:{m.targetPort}</td>
                        <td className="px-4 py-3">
                          <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                            m.securityTier === 'AI_LLM' ? 'bg-purple-100 text-purple-700'
                              : m.securityTier === 'AI' ? 'bg-blue-100 text-blue-700'
                              : m.securityTier === 'RULES' ? 'bg-green-100 text-green-700'
                              : 'bg-gray-100 text-gray-500'
                          }`}>
                            {m.securityTier || 'NONE'}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          {m.qosPolicy?.enabled ? (
                            <span className="text-xs text-blue-600">P{m.qosPolicy.priority || 5}</span>
                          ) : (
                            <span className="text-xs text-gray-400">Off</span>
                          )}
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">{m.activeConnections || 0}</td>
                        <td className="px-4 py-3 text-sm font-mono text-gray-500">
                          {((m.bytesForwarded || 0) / 1048576).toFixed(1)} MB
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${
                            m.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                          }`}>
                            <span className={`w-1.5 h-1.5 rounded-full ${m.active ? 'bg-green-500' : 'bg-gray-400'}`} />
                            {m.active ? 'Active' : 'Down'}
                          </span>
                        </td>
                        <td className="px-4 py-3 flex gap-2">
                          <button onClick={() => { setShowSecurityPolicy(m.name); setPolicyForm({ securityTier: m.securityTier || 'AI', rateLimitPerMinute: 60, maxConcurrent: 20 }) }}
                            className="px-2 py-1 rounded text-xs font-medium bg-blue-50 text-blue-600 hover:bg-blue-100 transition-colors">
                            Policy
                          </button>
                          <button onClick={() => { if (window.confirm(`Remove "${m.name}"?`)) removeMappingMut.mutate(m.name) }}
                            disabled={removeMappingMut.isPending}
                            className="px-2 py-1 rounded text-xs font-medium bg-red-50 text-red-600 hover:bg-red-100 transition-colors disabled:opacity-50">
                            Remove
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 4 — IP Management */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'ip' && (
        <div className="space-y-6">
          {/* Mapping Selector */}
          <div className="flex items-center gap-3">
            <label className="text-sm font-medium text-gray-700">Mapping:</label>
            <select value={selectedMapping} onChange={e => setSelectedMapping(e.target.value)}
              className="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-400">
              <option value="">Select a mapping</option>
              {mappings.map(m => <option key={m.name} value={m.name}>{m.name} (:{m.listenPort})</option>)}
            </select>
          </div>

          {selectedMapping ? (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <IpRulePanel
                title="Blacklist"
                type="blacklist"
                ips={selectedMappingData?.securityPolicy?.ipBlacklist || []}
                onAdd={(ip) => addBlacklistMut.mutate({ name: selectedMapping, ip })}
                onRemove={(ip) => removeBlacklistMut.mutate({ name: selectedMapping, ip })}
                loading={addBlacklistMut.isPending || removeBlacklistMut.isPending}
              />
              <IpRulePanel
                title="Whitelist"
                type="whitelist"
                ips={selectedMappingData?.securityPolicy?.ipWhitelist || []}
                onAdd={(ip) => addWhitelistMut.mutate({ name: selectedMapping, ip })}
                onRemove={(ip) => removeWhitelistMut.mutate({ name: selectedMapping, ip })}
                loading={addWhitelistMut.isPending || removeWhitelistMut.isPending}
              />
            </div>
          ) : (
            <p className="text-center py-8 text-sm text-gray-400">Select a mapping above to manage its IP rules</p>
          )}

          {/* IP Search */}
          <div className="space-y-3">
            <h3 className="text-sm font-semibold text-gray-900">IP Lookup</h3>
            <div className="flex gap-2">
              <MagnifyingGlassIcon className="w-5 h-5 text-gray-400 mt-2" />
              <input type="text" value={ipSearch} onChange={e => setIpSearch(e.target.value)}
                placeholder="Enter IP to lookup (e.g. 192.168.1.100)"
                className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-400" />
            </div>
            {ipDetail && <IpDetailCard data={ipDetail} />}
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 5 — Backend Health */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'health' && (
        <div className="space-y-6">
          {!backendHealth?.healthCheckEnabled ? (
            <div className="text-center py-12 text-gray-400">
              <ServerIcon className="w-12 h-12 mx-auto mb-3 text-gray-300" />
              <p className="text-sm">Backend health checks are disabled</p>
            </div>
          ) : (
            <>
              <div>
                <h2 className="text-lg font-semibold text-gray-900 mb-3">Backend Health</h2>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                  {backendHealth?.backends && Object.entries(backendHealth.backends).map(([name, h]) => (
                    <BackendHealthCard key={name} name={name} health={h} />
                  ))}
                </div>
              </div>

              {/* QoS */}
              {qosStats && qosStats.qosEnabled !== false && (
                <div>
                  <h2 className="text-lg font-semibold text-gray-900 mb-3">QoS Utilization</h2>
                  {qosStats.mappings ? (
                    <div className="space-y-3">
                      {Object.entries(qosStats.mappings).map(([name, stats]) => (
                        <QosBar key={name} name={name} stats={stats} />
                      ))}
                    </div>
                  ) : (
                    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm bg-white rounded-xl border border-gray-200 p-5">
                      {qosStats.globalCurrentBps !== undefined && (
                        <div>
                          <span className="text-gray-500 text-xs">Global BPS</span>
                          <p className="font-mono font-bold text-gray-900">{(qosStats.globalCurrentBps / 1048576).toFixed(2)} MB/s</p>
                        </div>
                      )}
                      {qosStats.globalLimitBps !== undefined && (
                        <div>
                          <span className="text-gray-500 text-xs">Global Limit</span>
                          <p className="font-mono font-bold text-gray-900">{(qosStats.globalLimitBps / 1048576).toFixed(2)} MB/s</p>
                        </div>
                      )}
                      {qosStats.activeConnections !== undefined && (
                        <div>
                          <span className="text-gray-500 text-xs">Active Connections</span>
                          <p className="font-bold text-gray-900">{qosStats.activeConnections}</p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 6 — Audit & Egress */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'audit' && (
        <div className="space-y-6">
          {/* Audit Stats */}
          <div className="bg-white rounded-xl border border-gray-200 p-5">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-semibold text-gray-900">Audit Logger</h3>
              <button onClick={() => flushAuditMut.mutate()} disabled={flushAuditMut.isPending}
                className="px-3 py-1.5 bg-amber-50 text-amber-700 rounded-lg text-xs font-medium hover:bg-amber-100 transition-colors disabled:opacity-50">
                {flushAuditMut.isPending ? 'Flushing...' : 'Flush Now'}
              </button>
            </div>
            {auditStats && auditStats.auditEnabled !== false ? (
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm">
                {Object.entries(auditStats).filter(([k]) => k !== 'auditEnabled').map(([key, val]) => (
                  <div key={key}>
                    <span className="text-gray-500 text-xs">{key}</span>
                    <p className="font-medium text-gray-900">{typeof val === 'object' ? JSON.stringify(val) : String(val)}</p>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-400">Audit logging disabled</p>
            )}
          </div>

          {/* Egress Filter */}
          <div className="bg-white rounded-xl border border-gray-200 p-5">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">Egress Filter</h3>
            {egressStats && egressStats.egressFilterEnabled !== false ? (
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm">
                {Object.entries(egressStats).filter(([k]) => k !== 'egressFilterEnabled').map(([key, val]) => (
                  <div key={key}>
                    <span className="text-gray-500 text-xs">{key}</span>
                    <p className="font-medium text-gray-900">{typeof val === 'object' ? JSON.stringify(val) : String(val)}</p>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-400">Egress filter disabled</p>
            )}
          </div>

          {/* Zone Rules */}
          <div className="bg-white rounded-xl border border-gray-200 p-5">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">Zone Enforcement</h3>
            {zoneRules && zoneRules.zoneEnforcementEnabled ? (
              <>
                {zoneRules.rules && (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm text-left">
                      <thead>
                        <tr className="border-b border-gray-200">
                          <th className="py-2 text-xs font-medium text-gray-500">Source Zone</th>
                          <th className="py-2 text-xs font-medium text-gray-500">Target Zone</th>
                          <th className="py-2 text-xs font-medium text-gray-500">Allowed</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(Array.isArray(zoneRules.rules) ? zoneRules.rules : Object.entries(zoneRules.rules).map(([k, v]) => ({ from: k, ...v }))).map((rule, i) => (
                          <tr key={i} className="border-b border-gray-50">
                            <td className="py-2 font-medium">{rule.sourceZone || rule.from}</td>
                            <td className="py-2">{rule.targetZone || rule.to}</td>
                            <td className="py-2">
                              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                                rule.allowed ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                              }`}>
                                {rule.allowed ? 'Yes' : 'No'}
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {zoneRules.stats && (
                  <div className="mt-3 grid grid-cols-2 lg:grid-cols-4 gap-4 text-sm">
                    {Object.entries(zoneRules.stats).map(([key, val]) => (
                      <div key={key}>
                        <span className="text-gray-500 text-xs">{key}</span>
                        <p className="font-medium text-gray-900">{String(val)}</p>
                      </div>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <p className="text-sm text-gray-400">Zone enforcement disabled</p>
            )}
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* TAB 7 — Diagnostics */}
      {/* ═══════════════════════════════════════════════════════════════ */}
      {activeTab === 'diagnostics' && (
        <div className="space-y-6">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <ReachabilityTester />
            <ZoneCheckForm />
          </div>
          <DmzTerminal />
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════ */}
      {/* MODALS */}
      {/* ═══════════════════════════════════════════════════════════════ */}

      {/* Add Mapping Modal */}
      {showAddMapping && (
        <Modal title="Add Port Mapping" onClose={() => setShowAddMapping(false)}>
          <form onSubmit={handleAddMapping} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
              <input type="text" value={mappingForm.name} onChange={e => setMappingForm(p => ({ ...p, name: e.target.value }))}
                placeholder="sftp-prod" className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Listen Port</label>
                <input type="number" value={mappingForm.listenPort} onChange={e => setMappingForm(p => ({ ...p, listenPort: e.target.value }))}
                  placeholder="2222" min="1" max="65535" className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Target Host</label>
                <input type="text" value={mappingForm.targetHost} onChange={e => setMappingForm(p => ({ ...p, targetHost: e.target.value }))}
                  placeholder="sftp-server" className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Target Port</label>
                <input type="number" value={mappingForm.targetPort} onChange={e => setMappingForm(p => ({ ...p, targetPort: e.target.value }))}
                  placeholder="22" min="1" max="65535" className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
            </div>

            {/* QoS */}
            <div className="border-t pt-3">
              <div className="flex items-center gap-2 mb-2">
                <input type="checkbox" id="qos" checked={mappingForm.qosEnabled}
                  onChange={e => setMappingForm(p => ({ ...p, qosEnabled: e.target.checked }))}
                  className="w-4 h-4 text-blue-600 rounded border-gray-300" />
                <label htmlFor="qos" className="text-sm font-medium text-gray-700">Enable QoS</label>
              </div>
              {mappingForm.qosEnabled && (
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-gray-500 mb-1">Max MB/s</label>
                    <input type="number" min="0" value={mappingForm.qosMaxBytesPerSecond}
                      onChange={e => setMappingForm(p => ({ ...p, qosMaxBytesPerSecond: e.target.value }))}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                  </div>
                  <div>
                    <label className="block text-xs text-gray-500 mb-1">Per-Conn MB/s</label>
                    <input type="number" min="0" value={mappingForm.qosPerConnectionMaxBytesPerSecond}
                      onChange={e => setMappingForm(p => ({ ...p, qosPerConnectionMaxBytesPerSecond: e.target.value }))}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                  </div>
                  <div>
                    <label className="block text-xs text-gray-500 mb-1">Priority (1-10)</label>
                    <input type="number" min="1" max="10" value={mappingForm.qosPriority}
                      onChange={e => setMappingForm(p => ({ ...p, qosPriority: e.target.value }))}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                  </div>
                  <div>
                    <label className="block text-xs text-gray-500 mb-1">Burst %</label>
                    <input type="number" min="0" max="100" value={mappingForm.qosBurstAllowancePercent}
                      onChange={e => setMappingForm(p => ({ ...p, qosBurstAllowancePercent: e.target.value }))}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                  </div>
                </div>
              )}
            </div>

            <div className="flex gap-3 pt-2">
              <button type="button" onClick={() => setShowAddMapping(false)}
                className="flex-1 px-4 py-2 border border-gray-300 text-gray-700 rounded-lg text-sm font-medium hover:bg-gray-50">
                Cancel
              </button>
              <button type="submit" disabled={addMappingMut.isPending}
                className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:bg-blue-400">
                {addMappingMut.isPending ? 'Adding...' : 'Add Mapping'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Security Policy Modal */}
      {showSecurityPolicy && (
        <Modal title={`Security Policy — ${showSecurityPolicy}`} onClose={() => setShowSecurityPolicy(null)}>
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Security Tier</label>
              <select value={policyForm.securityTier} onChange={e => setPolicyForm(p => ({ ...p, securityTier: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500">
                <option value="NONE">NONE — Pass-through</option>
                <option value="RULES">RULES — Manual rules only</option>
                <option value="AI">AI — AI-powered verdicts</option>
                <option value="AI_LLM">AI_LLM — AI + LLM analysis</option>
              </select>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs text-gray-500 mb-1">Rate Limit/min</label>
                <input type="number" value={policyForm.rateLimitPerMinute}
                  onChange={e => setPolicyForm(p => ({ ...p, rateLimitPerMinute: Number(e.target.value) }))}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="block text-xs text-gray-500 mb-1">Max Concurrent</label>
                <input type="number" value={policyForm.maxConcurrent}
                  onChange={e => setPolicyForm(p => ({ ...p, maxConcurrent: Number(e.target.value) }))}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
            </div>
            <div className="flex gap-3 pt-2">
              <button onClick={() => setShowSecurityPolicy(null)}
                className="flex-1 px-4 py-2 border border-gray-300 text-gray-700 rounded-lg text-sm font-medium hover:bg-gray-50">
                Cancel
              </button>
              <button onClick={() => updatePolicyMut.mutate({ name: showSecurityPolicy, policy: policyForm })}
                disabled={updatePolicyMut.isPending}
                className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:bg-blue-400">
                {updatePolicyMut.isPending ? 'Saving...' : 'Update Policy'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
