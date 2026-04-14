import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import { format } from 'date-fns'
import toast from 'react-hot-toast'
import {
  UserGroupIcon,
  ServerStackIcon,
  GlobeAltIcon,
  Cog6ToothIcon,
  BoltIcon,
  KeyIcon,
  ArrowUpTrayIcon,
  ShieldCheckIcon,
  ChartBarIcon,
  CpuChipIcon,
  DocumentTextIcon,
  ArrowsRightLeftIcon,
  CircleStackIcon,
  ArrowPathIcon,
  XMarkIcon,
  SignalIcon,
  ExclamationTriangleIcon,
  Square3Stack3DIcon,
  TagIcon,
} from '@heroicons/react/24/outline'
import LoadingSpinner from '../components/LoadingSpinner'
import { useServices } from '../context/ServiceContext'

const iconMap = {
  UserGroupIcon,
  ServerStackIcon,
  GlobeAltIcon,
  Cog6ToothIcon,
  BoltIcon,
  KeyIcon,
  ArrowUpTrayIcon,
  ShieldCheckIcon,
  ChartBarIcon,
  CpuChipIcon,
  DocumentTextIcon,
  ArrowsRightLeftIcon,
  CircleStackIcon,
}

// overrideKey maps each row to the key ServiceContext uses in its
// SERVICE_HEALTH_ENDPOINTS map + PAGE_SERVICE_MAP. When you toggle the
// override here, the rest of the UI (sidebar, route guards, Edi page banner)
// will honour the forced value immediately.
const SERVICES = [
  { id: 'onboarding-api', overrideKey: 'onboarding', name: 'Onboarding API', description: 'Authentication, accounts, partner management', port: 8080, category: 'Core', icon: 'UserGroupIcon' },
  { id: 'sftp-service', overrideKey: 'sftp', name: 'SFTP Service', description: 'Secure file transfer over SSH', port: 8081, category: 'Protocol', icon: 'ServerStackIcon' },
  { id: 'ftp-service', overrideKey: 'ftp', name: 'FTP Service', description: 'Classic file transfer protocol', port: 8082, category: 'Protocol', icon: 'ServerStackIcon' },
  { id: 'ftp-web-service', overrideKey: 'ftpWeb', name: 'FTP Web Service', description: 'HTTP-based file upload/download', port: 8083, category: 'Protocol', icon: 'GlobeAltIcon' },
  { id: 'config-service', overrideKey: 'config', name: 'Config Service', description: 'Flows, endpoints, connectors', port: 8084, category: 'Core', icon: 'Cog6ToothIcon' },
  { id: 'gateway-service', overrideKey: 'gateway', name: 'Gateway Service', description: 'Protocol gateway and routing', port: 8085, category: 'Infrastructure', icon: 'BoltIcon' },
  { id: 'encryption-service', overrideKey: 'encryption', name: 'Encryption Service', description: 'AES/PGP file encryption', port: 8086, category: 'Security', icon: 'KeyIcon' },
  { id: 'external-forwarder', overrideKey: 'forwarder', name: 'External Forwarder', description: 'File delivery to external destinations', port: 8087, category: 'Core', icon: 'ArrowUpTrayIcon' },
  { id: 'dmz-proxy', overrideKey: 'dmz', name: 'DMZ Proxy', description: 'AI-powered security proxy', port: 8088, category: 'Security', icon: 'ShieldCheckIcon' },
  { id: 'license-service', overrideKey: 'license', name: 'License Service', description: 'License management and validation', port: 8089, category: 'Administration', icon: 'KeyIcon' },
  { id: 'analytics-service', overrideKey: 'analytics', name: 'Analytics Service', description: 'Metrics, dashboards, predictions', port: 8090, category: 'Observability', icon: 'ChartBarIcon' },
  { id: 'ai-engine', overrideKey: 'aiEngine', name: 'AI Engine', description: 'Data classification, threat detection, routing AI', port: 8091, category: 'Intelligence', icon: 'CpuChipIcon' },
  { id: 'screening-service', overrideKey: 'screening', name: 'Screening Service', description: 'OFAC/AML sanctions screening', port: 8092, category: 'Compliance', icon: 'ShieldCheckIcon' },
  { id: 'keystore-manager', overrideKey: 'keystore', name: 'Keystore Manager', description: 'Cryptographic key and certificate management', port: 8093, category: 'Security', icon: 'KeyIcon' },
  { id: 'as2-service', overrideKey: 'as2', name: 'AS2 Service', description: 'AS2/AS4 B2B messaging protocol', port: 8094, category: 'Protocol', icon: 'ArrowsRightLeftIcon' },
  { id: 'edi-converter', overrideKey: 'ediConverter', name: 'EDI Converter', description: 'EDI format detection and conversion', port: 8095, category: 'Intelligence', icon: 'DocumentTextIcon' },
  { id: 'storage-manager', overrideKey: 'storage', name: 'Storage Manager', description: 'Tiered storage with lifecycle management', port: 8096, category: 'Infrastructure', icon: 'CircleStackIcon' },
]

const CATEGORIES = ['All', 'Core', 'Protocol', 'Security', 'Infrastructure', 'Compliance', 'Intelligence', 'Observability', 'Administration']

const categoryColors = {
  Core: 'badge-blue',
  Protocol: 'badge-purple',
  Security: 'badge-red',
  Infrastructure: 'badge-yellow',
  Compliance: 'badge-green',
  Intelligence: 'badge-purple',
  Observability: 'badge-blue',
  Administration: 'badge-gray',
}

const checkServiceHealth = async (port) => {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 3000)
    const res = await fetch(`http://localhost:${port}/actuator/health`, {
      signal: controller.signal,
      mode: 'no-cors', // avoid CORS blocks from cross-origin health probes
    }).catch(() => null)
    clearTimeout(timeout)
    // no-cors returns opaque response (status 0) but confirms server is reachable
    return res !== null ? { status: 'healthy', details: null } : { status: 'unreachable', details: null }
  } catch {
    return { status: 'unreachable', details: null }
  }
}

export default function ServiceManagement() {
  const queryClient = useQueryClient()
  const { overrides, toggleOverride, clearOverrides } = useServices()
  const [categoryFilter, setCategoryFilter] = useState('All')
  const [detailModal, setDetailModal] = useState(null)
  // Count only keys whose value is explicitly true/false — Auto clears by
  // setting undefined, but the key may still be present in the state object.
  const overrideCount = Object.values(overrides || {}).filter(v => v === true || v === false).length

  const { data: healthMap = {}, isLoading, dataUpdatedAt } = useQuery({
    queryKey: ['service-health'],
    queryFn: async () => {
      const results = {}
      await Promise.allSettled(
        SERVICES.map(async (svc) => {
          results[svc.id] = await checkServiceHealth(svc.port)
        })
      )
      return results
    },
    meta: { silent: true }, refetchInterval: 30000,
  })

  const handleRefresh = () => {
    queryClient.invalidateQueries({ queryKey: ['service-health'] })
    toast.success('Refreshing service health...')
  }

  const filteredServices = categoryFilter === 'All'
    ? SERVICES
    : SERVICES.filter((svc) => svc.category === categoryFilter)

  const healthyCount = Object.values(healthMap).filter((h) => h.status === 'healthy').length
  const unreachableCount = Object.values(healthMap).filter((h) => h.status !== 'healthy').length
  const uniqueCategories = [...new Set(SERVICES.map((s) => s.category))].length

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">Service Management</h1>
          <p className="text-secondary text-sm">Monitor and manage all platform microservices</p>
        </div>
        <div className="flex items-center gap-3">
          {dataUpdatedAt && (
            <span className="text-xs text-muted">
              Last checked: {format(new Date(dataUpdatedAt), 'HH:mm:ss')}
            </span>
          )}
          {overrideCount > 0 && (
            <button
              className="btn-secondary flex items-center gap-2 text-xs"
              onClick={() => { clearOverrides(); toast.success('Cleared all service overrides') }}
              title="Remove all manual overrides and fall back to detected health state"
            >
              <XMarkIcon className="h-4 w-4" />
              Clear {overrideCount} override{overrideCount === 1 ? '' : 's'}
            </button>
          )}
          <button className="btn-primary flex items-center gap-2" onClick={handleRefresh}>
            <ArrowPathIcon className="h-4 w-4" />
            Refresh
          </button>
        </div>
      </div>

      {/* Override explainer — only shown when overrides are active so the
          admin immediately knows why the sidebar looks different from reality. */}
      {overrideCount > 0 && (
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
            <div className="font-semibold mb-0.5">
              {overrideCount} manual override{overrideCount === 1 ? '' : 's'} active
            </div>
            <div style={{ color: 'rgb(148, 163, 184)' }}>
              Page visibility is being forced instead of following detected health.
              Use per-card Visibility controls to change them, or Clear to revert.
            </div>
          </div>
        </div>
      )}

      {/* Summary Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-blue-50 rounded-lg">
            <Square3Stack3DIcon className="h-6 w-6 text-blue-600" />
          </div>
          <div>
            <p className="text-sm text-secondary">Total Services</p>
            <p className="text-2xl font-bold text-primary">{SERVICES.length}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-green-50 rounded-lg">
            <SignalIcon className="h-6 w-6 text-green-600" />
          </div>
          <div>
            <p className="text-sm text-secondary">Healthy</p>
            <p className="text-2xl font-bold text-green-600">{healthyCount}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-red-50 rounded-lg">
            <ExclamationTriangleIcon className="h-6 w-6 text-red-600" />
          </div>
          <div>
            <p className="text-sm text-secondary">Unreachable</p>
            <p className="text-2xl font-bold text-red-600">{unreachableCount}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-purple-50 rounded-lg">
            <TagIcon className="h-6 w-6 text-purple-600" />
          </div>
          <div>
            <p className="text-sm text-secondary">Categories</p>
            <p className="text-2xl font-bold text-primary">{uniqueCategories}</p>
          </div>
        </div>
      </div>

      {/* Category Filter */}
      <div className="flex flex-wrap gap-2">
        {CATEGORIES.map((cat) => (
          <button
            key={cat}
            onClick={() => setCategoryFilter(cat)}
            className={`px-3 py-1.5 text-sm font-medium rounded-full transition-colors ${
              categoryFilter === cat
                ? 'bg-blue-600 text-white'
                : 'bg-hover text-secondary hover:bg-gray-200'
            }`}
          >
            {cat}
            {cat === 'All' && ` (${SERVICES.length})`}
            {cat !== 'All' && ` (${SERVICES.filter((s) => s.category === cat).length})`}
          </button>
        ))}
      </div>

      {/* Service Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {filteredServices.map((svc) => {
          const health = healthMap[svc.id] || { status: 'unknown', details: null }
          const IconComponent = iconMap[svc.icon] || Cog6ToothIcon
          const isHealthy = health.status === 'healthy'

          return (
            <div key={svc.id} className="card hover:shadow-md transition-shadow">
              {/* Top row: icon, name, status dot */}
              <div className="flex items-start justify-between mb-2">
                <div className="flex items-center gap-3">
                  <div className={`p-2 rounded-lg ${isHealthy ? 'bg-green-50' : 'bg-red-50'}`}>
                    <IconComponent className={`h-5 w-5 ${isHealthy ? 'text-green-600' : 'text-red-500'}`} />
                  </div>
                  <div>
                    <h3 className="font-semibold text-primary">{svc.name}</h3>
                    <p className="text-sm text-secondary">{svc.description}</p>
                  </div>
                </div>
                <span className="relative flex h-3 w-3 mt-1 flex-shrink-0">
                  {isHealthy ? (
                    <>
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75" />
                      <span className="relative inline-flex rounded-full h-3 w-3 bg-green-500" />
                    </>
                  ) : (
                    <span className="relative inline-flex rounded-full h-3 w-3 bg-red-500" />
                  )}
                </span>
              </div>

              {/* Port and category badges */}
              <div className="flex items-center gap-2 mt-3 mb-3">
                <span className="badge badge-gray">Port: {svc.port}</span>
                <span className={`badge ${categoryColors[svc.category] || 'badge-gray'}`}>
                  {svc.category}
                </span>
              </div>

              {/* Status section */}
              <div className={`rounded-lg p-3 mb-3 ${isHealthy ? 'bg-green-50' : 'bg-red-50'}`}>
                <div className="flex items-center gap-2">
                  <span className={`text-sm font-medium ${isHealthy ? 'text-green-700' : 'text-red-700'}`}>
                    Status: {isHealthy ? 'Healthy' : 'Unreachable'}
                  </span>
                </div>
                {isHealthy && health.details?.components?.diskSpace && (
                  <p className="text-xs text-green-600 mt-1">
                    Disk: {health.details.components.diskSpace.status || 'OK'}
                  </p>
                )}
                {isHealthy && health.details?.status && (
                  <p className="text-xs text-green-600 mt-1">
                    Actuator: {health.details.status}
                  </p>
                )}
                {!isHealthy && (
                  <p className="text-xs text-red-600 mt-1">
                    Service is not responding on port {svc.port}
                  </p>
                )}
              </div>

              {/* Sidebar visibility override — forces pages for this service
                  visible (true) or hidden (false) regardless of health probe,
                  or Auto to fall back to detected state. */}
              <div className="mb-3 flex items-center gap-2 text-xs">
                <span className="text-muted flex-shrink-0">Sidebar visibility:</span>
                <div className="flex rounded-lg overflow-hidden border border-border">
                  {[
                    { value: 'auto', label: 'Auto', active: overrides[svc.overrideKey] === undefined },
                    { value: 'on',   label: 'Force on', active: overrides[svc.overrideKey] === true },
                    { value: 'off',  label: 'Force off', active: overrides[svc.overrideKey] === false },
                  ].map(opt => (
                    <button
                      key={opt.value}
                      onClick={() => {
                        // toggleOverride stores { key: undefined } in state;
                        // JSON.stringify drops undefined keys, and the
                        // isServiceRunning check treats it as "no override"
                        // (neither === true nor === false), so Auto just
                        // falls back to detected health.
                        if (opt.value === 'auto') {
                          toggleOverride(svc.overrideKey, undefined)
                          toast.success(`${svc.name} follows detected health`)
                        } else {
                          toggleOverride(svc.overrideKey, opt.value === 'on')
                          toast.success(`${svc.name} forced ${opt.value === 'on' ? 'visible' : 'hidden'}`)
                        }
                      }}
                      className={`px-2 py-1 text-[11px] transition-colors ${
                        opt.active
                          ? 'bg-blue-600 text-white font-semibold'
                          : 'bg-hover text-secondary hover:bg-gray-200'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Action buttons */}
              <div className="flex items-center gap-2">
                <a href="/logs" className="btn-secondary text-xs px-3 py-1.5">
                  View Logs
                </a>
                <button
                  className="btn-secondary text-xs px-3 py-1.5"
                  onClick={() => setDetailModal(svc)}
                >
                  Health Details
                </button>
              </div>
            </div>
          )
        })}
      </div>

      {filteredServices.length === 0 && (
        <div className="card text-center py-12">
          <p className="text-secondary">No services found in the "{categoryFilter}" category.</p>
        </div>
      )}

      {/* Architecture Overview */}
      <div className="card">
        <h3 className="font-semibold text-primary mb-4">Architecture Overview</h3>
        <p className="text-sm text-secondary mb-6">
          High-level service dependency topology showing how data flows through the platform.
        </p>
        <div className="overflow-x-auto">
          <div className="flex items-center justify-center gap-0 min-w-[900px] py-4">
            {/* External */}
            <div className="flex flex-col items-center">
              <div className="bg-hover border-2 border-gray-300 rounded-lg px-4 py-3 text-center min-w-[120px]">
                <GlobeAltIcon className="h-5 w-5 text-secondary mx-auto mb-1" />
                <p className="text-xs font-semibold text-primary">External</p>
                <p className="text-[10px] text-muted">Partners / Clients</p>
              </div>
            </div>

            {/* Arrow */}
            <div className="flex items-center px-2">
              <div className="w-8 h-0.5 bg-gray-300" />
              <div className="w-0 h-0 border-t-4 border-b-4 border-l-6 border-t-transparent border-b-transparent border-l-gray-300" />
            </div>

            {/* DMZ Proxy */}
            <div className="flex flex-col items-center">
              <div className="bg-red-50 border-2 border-red-200 rounded-lg px-4 py-3 text-center min-w-[120px]">
                <ShieldCheckIcon className="h-5 w-5 text-red-500 mx-auto mb-1" />
                <p className="text-xs font-semibold text-red-700">DMZ Proxy</p>
                <p className="text-[10px] text-red-400">:8088</p>
              </div>
            </div>

            {/* Arrow */}
            <div className="flex items-center px-2">
              <div className="w-8 h-0.5 bg-gray-300" />
              <div className="w-0 h-0 border-t-4 border-b-4 border-l-6 border-t-transparent border-b-transparent border-l-gray-300" />
            </div>

            {/* Gateway */}
            <div className="flex flex-col items-center">
              <div className="bg-yellow-50 border-2 border-yellow-200 rounded-lg px-4 py-3 text-center min-w-[120px]">
                <BoltIcon className="h-5 w-5 text-yellow-600 mx-auto mb-1" />
                <p className="text-xs font-semibold text-yellow-700">Gateway</p>
                <p className="text-[10px] text-yellow-500">:8085</p>
              </div>
            </div>

            {/* Arrow */}
            <div className="flex items-center px-2">
              <div className="w-8 h-0.5 bg-gray-300" />
              <div className="w-0 h-0 border-t-4 border-b-4 border-l-6 border-t-transparent border-b-transparent border-l-gray-300" />
            </div>

            {/* Protocol Services */}
            <div className="flex flex-col items-center">
              <div className="bg-purple-50 border-2 border-purple-200 rounded-lg px-4 py-3 text-center min-w-[130px]">
                <ServerStackIcon className="h-5 w-5 text-purple-500 mx-auto mb-1" />
                <p className="text-xs font-semibold text-purple-700">Protocol Services</p>
                <p className="text-[10px] text-purple-400">SFTP, FTP, AS2, HTTP</p>
                <p className="text-[10px] text-purple-400">:8081-8083, :8094</p>
              </div>
            </div>

            {/* Arrow */}
            <div className="flex items-center px-2">
              <div className="w-8 h-0.5 bg-gray-300" />
              <div className="w-0 h-0 border-t-4 border-b-4 border-l-6 border-t-transparent border-b-transparent border-l-gray-300" />
            </div>

            {/* Core Services */}
            <div className="flex flex-col items-center">
              <div className="bg-blue-50 border-2 border-blue-200 rounded-lg px-4 py-3 text-center min-w-[130px]">
                <Cog6ToothIcon className="h-5 w-5 text-blue-500 mx-auto mb-1" />
                <p className="text-xs font-semibold text-blue-700">Core Services</p>
                <p className="text-[10px] text-blue-400">Config, Onboarding, Forwarder</p>
                <p className="text-[10px] text-blue-400">:8080, :8084, :8087</p>
              </div>
            </div>

            {/* Arrow */}
            <div className="flex items-center px-2">
              <div className="w-8 h-0.5 bg-gray-300" />
              <div className="w-0 h-0 border-t-4 border-b-4 border-l-6 border-t-transparent border-b-transparent border-l-gray-300" />
            </div>

            {/* Storage */}
            <div className="flex flex-col items-center">
              <div className="bg-green-50 border-2 border-green-200 rounded-lg px-4 py-3 text-center min-w-[120px]">
                <CircleStackIcon className="h-5 w-5 text-green-500 mx-auto mb-1" />
                <p className="text-xs font-semibold text-green-700">Storage</p>
                <p className="text-[10px] text-green-400">:8096</p>
              </div>
            </div>
          </div>

          {/* Supporting services row */}
          <div className="flex items-center justify-center gap-4 mt-4 pt-4 border-t border-border">
            <span className="text-xs text-muted mr-2">Supporting:</span>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <KeyIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">Encryption :8086</span>
            </div>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <KeyIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">Keystore :8093</span>
            </div>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <CpuChipIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">AI Engine :8091</span>
            </div>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <ShieldCheckIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">Screening :8092</span>
            </div>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <ChartBarIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">Analytics :8090</span>
            </div>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <DocumentTextIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">EDI :8095</span>
            </div>
            <div className="flex items-center gap-1 bg-canvas rounded px-2 py-1">
              <KeyIcon className="h-3 w-3 text-muted" />
              <span className="text-[10px] text-secondary">License :8089</span>
            </div>
          </div>
        </div>
      </div>

      {/* Health Detail Modal */}
      {detailModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setDetailModal(null)}>
          <div className="bg-surface rounded-xl shadow-2xl w-full max-w-lg mx-4" onClick={(e) => e.stopPropagation()}>
            {/* Modal Header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-border">
              <div>
                <h3 className="font-semibold text-primary">{detailModal.name}</h3>
                <p className="text-sm text-secondary">Health Details</p>
              </div>
              <button
                onClick={() => setDetailModal(null)}
                className="p-1 rounded-lg hover:bg-hover transition-colors"
              >
                <XMarkIcon className="h-5 w-5 text-muted" />
              </button>
            </div>

            {/* Modal Body */}
            <div className="px-6 py-4 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-muted uppercase tracking-wider">Service ID</p>
                  <p className="text-sm font-mono text-primary">{detailModal.id}</p>
                </div>
                <div>
                  <p className="text-xs text-muted uppercase tracking-wider">Port</p>
                  <p className="text-sm font-mono text-primary">{detailModal.port}</p>
                </div>
                <div>
                  <p className="text-xs text-muted uppercase tracking-wider">Category</p>
                  <p className="text-sm text-primary">{detailModal.category}</p>
                </div>
                <div>
                  <p className="text-xs text-muted uppercase tracking-wider">Status</p>
                  <span className={`badge ${healthMap[detailModal.id]?.status === 'healthy' ? 'badge-green' : 'badge-red'}`}>
                    {healthMap[detailModal.id]?.status === 'healthy' ? 'Healthy' : 'Unreachable'}
                  </span>
                </div>
              </div>

              <div>
                <p className="text-xs text-muted uppercase tracking-wider mb-1">Health Endpoint</p>
                <p className="text-sm font-mono text-secondary bg-canvas rounded px-2 py-1">
                  http://localhost:{detailModal.port}/actuator/health
                </p>
              </div>

              {dataUpdatedAt && (
                <div>
                  <p className="text-xs text-muted uppercase tracking-wider mb-1">Last Checked</p>
                  <p className="text-sm text-primary">
                    {format(new Date(dataUpdatedAt), 'yyyy-MM-dd HH:mm:ss')}
                  </p>
                </div>
              )}

              <div>
                <p className="text-xs text-muted uppercase tracking-wider mb-1">Raw Response</p>
                <pre className="text-xs bg-canvas rounded-lg p-3 overflow-auto max-h-60 text-secondary border border-border">
                  {healthMap[detailModal.id]?.details
                    ? JSON.stringify(healthMap[detailModal.id].details, null, 2)
                    : 'No response — service is unreachable.'}
                </pre>
              </div>
            </div>

            {/* Modal Footer */}
            <div className="flex justify-end px-6 py-4 border-t border-border">
              <button className="btn-secondary" onClick={() => setDetailModal(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
