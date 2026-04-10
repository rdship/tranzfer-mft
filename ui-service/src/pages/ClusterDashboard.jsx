import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getClusters, getCluster, updateCluster,
  getCommunicationMode, setCommunicationMode,
  getTopology, getLiveRegistry,
} from '../api/cluster'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { format, formatDistanceToNow } from 'date-fns'
import {
  ServerStackIcon, SignalIcon, CpuChipIcon, GlobeAltIcon,
  ArrowPathIcon, Cog6ToothIcon, ShieldCheckIcon, BoltIcon,
  ExclamationTriangleIcon, CheckCircleIcon, ClockIcon,
  ArrowsPointingOutIcon, XMarkIcon, CloudIcon,
  CircleStackIcon, WrenchScrewdriverIcon,
} from '@heroicons/react/24/outline'

/* ── Helpers ── */
const STATUS_CONFIG = {
  UP:       { badge: 'badge-green',  label: 'UP',       dot: 'status-dot-green' },
  DOWN:     { badge: 'badge-red',    label: 'DOWN',     dot: 'status-dot-red' },
  DEGRADED: { badge: 'badge-yellow', label: 'DEGRADED', dot: 'status-dot-yellow' },
  UNKNOWN:  { badge: 'badge-gray',   label: 'UNKNOWN',  dot: 'status-dot-gray' },
}

const SERVICE_ICONS = {
  ONBOARDING:     ServerStackIcon,
  SFTP_SERVER:    CpuChipIcon,
  FTP_SERVER:     CpuChipIcon,
  FTP_WEB:        GlobeAltIcon,
  CONFIG:         Cog6ToothIcon,
  GATEWAY:        GlobeAltIcon,
  ENCRYPTION:     ShieldCheckIcon,
  FORWARDER:      BoltIcon,
  DMZ_PROXY:      ShieldCheckIcon,
  LICENSE:        CheckCircleIcon,
  ANALYTICS:      CircleStackIcon,
  AI_ENGINE:      CpuChipIcon,
  SCREENING:      ShieldCheckIcon,
  KEYSTORE:       ShieldCheckIcon,
  AS2:            ArrowsPointingOutIcon,
  EDI_CONVERTER:  WrenchScrewdriverIcon,
  STORAGE:        CircleStackIcon,
  NOTIFICATION:   BoltIcon,
  SENTINEL:       ShieldCheckIcon,
}

function getServiceIcon(type) {
  return SERVICE_ICONS[type] || ServerStackIcon
}

function statusOf(status) {
  return STATUS_CONFIG[status] || STATUS_CONFIG.UNKNOWN
}

export default function ClusterDashboard() {
  const qc = useQueryClient()
  const [showSettings, setShowSettings] = useState(null)
  const [settingsForm, setSettingsForm] = useState({})
  const [showModeConfirm, setShowModeConfirm] = useState(null)

  /* ── Queries ── */
  const { data: mode, isLoading: loadingMode } = useQuery({
    queryKey: ['cluster-mode'],
    queryFn: getCommunicationMode,
  })

  const { data: topology = [], isLoading: loadingTopology } = useQuery({
    queryKey: ['cluster-topology'],
    queryFn: getTopology,
    refetchInterval: 30000,
  })

  const { data: registry = [], isLoading: loadingRegistry } = useQuery({
    queryKey: ['cluster-registry'],
    queryFn: getLiveRegistry,
    refetchInterval: 10000,
  })

  const { data: clusters = [] } = useQuery({
    queryKey: ['clusters'],
    queryFn: getClusters,
  })

  /* ── Mutations ── */
  const modeMut = useMutation({
    mutationFn: (newMode) => setCommunicationMode(newMode),
    onSuccess: () => {
      qc.invalidateQueries(['cluster-mode'])
      qc.invalidateQueries(['cluster-topology'])
      qc.invalidateQueries(['cluster-registry'])
      qc.invalidateQueries(['clusters'])
      setShowModeConfirm(null)
      toast.success('Communication mode updated')
    },
    onError: (err) => toast.error(err?.response?.data?.message || 'Failed to update mode'),
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateCluster(id, data),
    onSuccess: () => {
      qc.invalidateQueries(['clusters'])
      qc.invalidateQueries(['cluster-topology'])
      setShowSettings(null)
      toast.success('Cluster settings updated')
    },
    onError: (err) => toast.error(err?.response?.data?.message || 'Failed to update settings'),
  })

  /* ── Derived data ── */
  const currentMode = mode?.mode || mode || 'STANDALONE'
  const isClusteredMode = currentMode === 'CLUSTERED'

  /* Group topology by service type */
  const serviceGroups = {}
  for (const svc of topology) {
    const type = svc.serviceType || svc.type || 'UNKNOWN'
    if (!serviceGroups[type]) serviceGroups[type] = []
    serviceGroups[type].push(svc)
  }
  const groupEntries = Object.entries(serviceGroups).sort(([a], [b]) => a.localeCompare(b))

  /* Aggregate stats */
  const totalInstances = topology.length
  const healthyCount = topology.filter(s => (s.status || '').toUpperCase() === 'UP').length
  const downCount = topology.filter(s => (s.status || '').toUpperCase() === 'DOWN').length
  const degradedCount = topology.filter(s => (s.status || '').toUpperCase() === 'DEGRADED').length

  const openEditSettings = (cluster) => {
    setShowSettings(cluster)
    setSettingsForm({
      name: cluster.name || '',
      description: cluster.description || '',
      maxNodes: cluster.maxNodes || '',
    })
  }

  const handleUpdateSettings = () => {
    if (!showSettings) return
    updateMut.mutate({ id: showSettings.id, data: settingsForm })
  }

  const handleModeSwitch = () => {
    const newMode = isClusteredMode ? 'STANDALONE' : 'CLUSTERED'
    setShowModeConfirm(newMode)
  }

  const loading = loadingMode || loadingTopology

  return (
    <div className="page-enter space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h1 className="page-title">Cluster Management</h1>
          <p className="text-sm text-secondary mt-1">Service topology, live registry, and cluster configuration</p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => {
              qc.invalidateQueries(['cluster-topology'])
              qc.invalidateQueries(['cluster-registry'])
            }}
            className="btn-ghost"
            title="Refresh"
            aria-label="Refresh"
          >
            <ArrowPathIcon className="w-4 h-4" />
          </button>
        </div>
      </div>

      {loading ? (
        <LoadingSpinner text="Loading cluster data..." />
      ) : (
        <>
          {/* ── Communication Mode + Stats ── */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            {/* Mode Card */}
            <div className="card md:col-span-2">
              <div className="flex items-center justify-between mb-4">
                <h2 className="section-title">Communication Mode</h2>
                <span className={`badge ${isClusteredMode ? 'badge-purple' : 'badge-blue'}`}>
                  {currentMode}
                </span>
              </div>
              <p className="text-sm text-secondary mb-4">
                {isClusteredMode
                  ? 'Services communicate via cluster coordination with leader election, distributed state, and automatic failover.'
                  : 'Each service instance operates independently. Suitable for single-node deployments and development.'}
              </p>
              <button onClick={handleModeSwitch} className="btn-secondary">
                <ArrowsPointingOutIcon className="w-4 h-4" />
                Switch to {isClusteredMode ? 'STANDALONE' : 'CLUSTERED'}
              </button>
            </div>

            {/* Stats */}
            <StatCard
              label="Total Instances"
              value={totalInstances}
              icon={ServerStackIcon}
              color="rgb(100,140,255)"
            />
            <StatCard
              label="Healthy"
              value={healthyCount}
              icon={CheckCircleIcon}
              color="rgb(72,199,174)"
              sub={downCount > 0 ? `${downCount} down` : degradedCount > 0 ? `${degradedCount} degraded` : 'All clear'}
              subColor={downCount > 0 ? 'rgb(240,120,120)' : degradedCount > 0 ? 'rgb(240,200,100)' : 'rgb(72,199,174)'}
            />
          </div>

          {/* ── Service Topology Grid ── */}
          <div>
            <h2 className="section-label mb-4">Service Topology</h2>
            {groupEntries.length === 0 ? (
              <div className="card text-center py-12">
                <ServerStackIcon className="w-12 h-12 text-muted mx-auto mb-3" />
                <p className="text-primary font-medium">No services registered</p>
                <p className="text-sm text-secondary mt-1">Start your platform services to see topology data.</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
                {groupEntries.map(([type, instances]) => {
                  const Icon = getServiceIcon(type)
                  const upCount = instances.filter(i => (i.status || '').toUpperCase() === 'UP').length
                  const totalCount = instances.length
                  const allUp = upCount === totalCount
                  const allDown = upCount === 0
                  const overallStatus = allUp ? 'UP' : allDown ? 'DOWN' : 'DEGRADED'
                  const sc = statusOf(overallStatus)

                  return (
                    <div
                      key={type}
                      className="card-sm hover:border-accent/20 transition-all"
                    >
                      <div className="flex items-start justify-between mb-3">
                        <div className="flex items-center gap-2.5">
                          <div
                            className="w-8 h-8 rounded-lg flex items-center justify-center"
                            style={{
                              background: allUp
                                ? 'rgb(20,60,40)'
                                : allDown
                                  ? 'rgb(60,20,20)'
                                  : 'rgb(60,50,20)',
                            }}
                          >
                            <Icon
                              className="w-4 h-4"
                              style={{
                                color: allUp
                                  ? 'rgb(120,220,160)'
                                  : allDown
                                    ? 'rgb(240,120,120)'
                                    : 'rgb(240,200,100)',
                              }}
                            />
                          </div>
                          <div>
                            <p className="text-sm font-semibold text-primary">
                              {type.replace(/_/g, ' ')}
                            </p>
                            <p className="text-xs text-muted">
                              {totalCount} instance{totalCount !== 1 ? 's' : ''}
                            </p>
                          </div>
                        </div>
                        <span className={`badge ${sc.badge}`}>{sc.label}</span>
                      </div>

                      {/* Instance list */}
                      <div className="space-y-1.5">
                        {instances.map((inst, idx) => {
                          const instStatus = statusOf((inst.status || '').toUpperCase())
                          return (
                            <div
                              key={inst.instanceId || idx}
                              className="flex items-center justify-between px-2.5 py-1.5 rounded-lg bg-canvas/60"
                            >
                              <div className="flex items-center gap-2 min-w-0">
                                <div className={`status-dot ${instStatus.dot}`} />
                                <span className="text-xs text-secondary truncate">
                                  {inst.host || inst.instanceId || `#${idx + 1}`}
                                </span>
                              </div>
                              <span className="text-xs font-mono text-muted flex-shrink-0">
                                :{inst.port || '--'}
                              </span>
                            </div>
                          )
                        })}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          {/* ── Live Registry ── */}
          <div>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-3">
                <h2 className="section-label">Live Service Registry</h2>
                <div className="flex items-center gap-1.5">
                  <div className="w-1.5 h-1.5 rounded-full bg-green-500 animate-pulse" />
                  <span className="text-xs text-muted">Auto-refresh 10s</span>
                </div>
              </div>
              <span className="text-xs text-muted">
                {registry.length} registered instance{registry.length !== 1 ? 's' : ''}
              </span>
            </div>

            {loadingRegistry ? (
              <LoadingSpinner text="Loading registry..." />
            ) : registry.length === 0 ? (
              <div className="card text-center py-10">
                <CloudIcon className="w-10 h-10 text-muted mx-auto mb-3" />
                <p className="text-primary font-medium">No registered instances</p>
                <p className="text-sm text-secondary mt-1">Services will appear here once they register with the cluster.</p>
              </div>
            ) : (
              <div className="card overflow-hidden p-0">
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-border">
                        <th className="table-header">Instance ID</th>
                        <th className="table-header">Service Type</th>
                        <th className="table-header">Host</th>
                        <th className="table-header">Port</th>
                        <th className="table-header">Status</th>
                        <th className="table-header">Registered At</th>
                        <th className="table-header">Heartbeat</th>
                      </tr>
                    </thead>
                    <tbody>
                      {registry.map((inst, idx) => {
                        const sc = statusOf((inst.status || '').toUpperCase())
                        return (
                          <tr key={inst.instanceId || idx} className="table-row">
                            <td className="table-cell">
                              <span className="font-mono text-xs text-primary">
                                {inst.instanceId || '--'}
                              </span>
                            </td>
                            <td className="table-cell">
                              <span className="badge badge-blue">
                                {(inst.serviceType || inst.type || '--').replace(/_/g, ' ')}
                              </span>
                            </td>
                            <td className="table-cell text-secondary text-sm">
                              {inst.host || '--'}
                            </td>
                            <td className="table-cell font-mono text-sm text-secondary">
                              {inst.port || '--'}
                            </td>
                            <td className="table-cell">
                              <div className="flex items-center gap-2">
                                <div className={`status-dot ${sc.dot}`} />
                                <span className={`badge ${sc.badge}`}>{sc.label}</span>
                              </div>
                            </td>
                            <td className="table-cell text-secondary text-sm">
                              {inst.registeredAt
                                ? format(new Date(inst.registeredAt), 'MMM d, HH:mm:ss')
                                : '--'}
                            </td>
                            <td className="table-cell">
                              {inst.lastHeartbeat ? (
                                <div className="flex items-center gap-1.5">
                                  <ClockIcon className="w-3.5 h-3.5 text-muted" />
                                  <span className="text-xs text-secondary">
                                    {formatDistanceToNow(new Date(inst.lastHeartbeat), { addSuffix: true })}
                                  </span>
                                </div>
                              ) : (
                                <span className="text-xs text-muted">--</span>
                              )}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>

          {/* ── Cluster Details (CLUSTERED mode only) ── */}
          {isClusteredMode && (
            <div>
              <h2 className="section-label mb-4">Cluster Nodes</h2>
              {clusters.length === 0 ? (
                <div className="card text-center py-10">
                  <CircleStackIcon className="w-10 h-10 text-muted mx-auto mb-3" />
                  <p className="text-primary font-medium">No clusters configured</p>
                  <p className="text-sm text-secondary mt-1">Cluster details will appear here in clustered mode.</p>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {clusters.map((cluster) => (
                    <div key={cluster.id} className="card">
                      <div className="flex items-start justify-between mb-4">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-accent/10">
                            <CircleStackIcon className="w-5 h-5 text-accent" />
                          </div>
                          <div>
                            <p className="text-primary font-semibold">{cluster.name || `Cluster ${cluster.id}`}</p>
                            <p className="text-xs text-muted font-mono">ID: {cluster.id}</p>
                          </div>
                        </div>
                        <button
                          onClick={() => openEditSettings(cluster)}
                          className="btn-ghost"
                          title="Settings"
                          aria-label="Settings"
                        >
                          <Cog6ToothIcon className="w-4 h-4" />
                        </button>
                      </div>

                      <div className="grid grid-cols-3 gap-3">
                        <div className="bg-canvas rounded-lg p-3 text-center">
                          <p className="text-xl font-bold font-mono text-primary">
                            {cluster.nodeCount ?? cluster.nodes?.length ?? '--'}
                          </p>
                          <p className="text-xs text-muted mt-0.5">Nodes</p>
                        </div>
                        <div className="bg-canvas rounded-lg p-3 text-center">
                          <p className="text-sm font-semibold text-primary truncate">
                            {cluster.leader || cluster.leaderNode || '--'}
                          </p>
                          <p className="text-xs text-muted mt-0.5">Leader</p>
                        </div>
                        <div className="bg-canvas rounded-lg p-3 text-center">
                          <span className={`badge ${cluster.leaderElected || cluster.leader ? 'badge-green' : 'badge-yellow'}`}>
                            {cluster.leaderElected || cluster.leader ? 'Elected' : 'Pending'}
                          </span>
                          <p className="text-xs text-muted mt-1.5">Election</p>
                        </div>
                      </div>

                      {cluster.description && (
                        <p className="text-xs text-secondary mt-3">{cluster.description}</p>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </>
      )}

      {/* ── Mode Switch Confirmation Modal ── */}
      {showModeConfirm && (
        <Modal title="Switch Communication Mode" onClose={() => setShowModeConfirm(null)} size="sm">
          <div className="space-y-4">
            <div className="flex items-start gap-3 p-3 rounded-lg bg-yellow-500/10 border border-yellow-500/20">
              <ExclamationTriangleIcon className="w-5 h-5 text-yellow-400 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm text-primary">
                  Switch to <span className="font-semibold">{showModeConfirm}</span> mode?
                </p>
                <p className="text-xs text-secondary mt-1">
                  {showModeConfirm === 'CLUSTERED'
                    ? 'This will enable cluster coordination, leader election, and distributed state synchronization. All service instances will begin registering with the cluster.'
                    : 'This will disable cluster coordination. Services will operate independently without distributed state management.'}
                </p>
              </div>
            </div>
            <div className="flex justify-end gap-3">
              <button onClick={() => setShowModeConfirm(null)} className="btn-secondary">
                Cancel
              </button>
              <button
                onClick={() => modeMut.mutate(showModeConfirm)}
                disabled={modeMut.isPending}
                className="btn-primary"
              >
                <ArrowsPointingOutIcon className="w-4 h-4" />
                {modeMut.isPending ? 'Switching...' : `Switch to ${showModeConfirm}`}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Cluster Settings Modal ── */}
      {showSettings && (
        <Modal title="Cluster Settings" onClose={() => setShowSettings(null)} size="md">
          <div className="space-y-4">
            <div>
              <label className="text-sm font-medium text-secondary mb-1 block">Cluster Name</label>
              <input
                value={settingsForm.name}
                onChange={(e) => setSettingsForm(f => ({ ...f, name: e.target.value }))}
                placeholder="production-cluster"
              />
            </div>
            <div>
              <label className="text-sm font-medium text-secondary mb-1 block">Description</label>
              <textarea
                value={settingsForm.description}
                onChange={(e) => setSettingsForm(f => ({ ...f, description: e.target.value }))}
                placeholder="Optional description..."
                rows={3}
              />
            </div>
            <div>
              <label className="text-sm font-medium text-secondary mb-1 block">Max Nodes</label>
              <input
                type="number"
                value={settingsForm.maxNodes}
                onChange={(e) => setSettingsForm(f => ({ ...f, maxNodes: e.target.value }))}
                placeholder="e.g. 10"
                min={1}
              />
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <button onClick={() => setShowSettings(null)} className="btn-secondary">
                Cancel
              </button>
              <button
                onClick={handleUpdateSettings}
                disabled={updateMut.isPending}
                className="btn-primary"
              >
                <Cog6ToothIcon className="w-4 h-4" />
                {updateMut.isPending ? 'Saving...' : 'Save Settings'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

/* ── Stat Card sub-component ── */
function StatCard({ label, value, icon: Icon, color, sub, subColor }) {
  return (
    <div className="card flex flex-col justify-between">
      <div className="flex items-center gap-3 mb-3">
        <div
          className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
          style={{ background: `${color}18` }}
        >
          <Icon className="w-5 h-5" style={{ color }} />
        </div>
        <p className="text-xs font-semibold uppercase tracking-wider text-muted">{label}</p>
      </div>
      <p className="text-3xl font-bold font-mono text-primary">{value}</p>
      {sub && (
        <p className="text-xs mt-1" style={{ color: subColor || 'rgb(160,165,175)' }}>{sub}</p>
      )}
    </div>
  )
}
