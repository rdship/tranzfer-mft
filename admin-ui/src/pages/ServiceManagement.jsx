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

const SERVICES = [
  { id: 'onboarding-api', name: 'Onboarding API', description: 'Authentication, accounts, partner management', port: 8080, category: 'Core', icon: 'UserGroupIcon' },
  { id: 'sftp-service', name: 'SFTP Service', description: 'Secure file transfer over SSH', port: 8081, category: 'Protocol', icon: 'ServerStackIcon' },
  { id: 'ftp-service', name: 'FTP Service', description: 'Classic file transfer protocol', port: 8082, category: 'Protocol', icon: 'ServerStackIcon' },
  { id: 'ftp-web-service', name: 'FTP Web Service', description: 'HTTP-based file upload/download', port: 8083, category: 'Protocol', icon: 'GlobeAltIcon' },
  { id: 'config-service', name: 'Config Service', description: 'Flows, endpoints, connectors', port: 8084, category: 'Core', icon: 'Cog6ToothIcon' },
  { id: 'gateway-service', name: 'Gateway Service', description: 'Protocol gateway and routing', port: 8085, category: 'Infrastructure', icon: 'BoltIcon' },
  { id: 'encryption-service', name: 'Encryption Service', description: 'AES/PGP file encryption', port: 8086, category: 'Security', icon: 'KeyIcon' },
  { id: 'external-forwarder', name: 'External Forwarder', description: 'File delivery to external destinations', port: 8087, category: 'Core', icon: 'ArrowUpTrayIcon' },
  { id: 'dmz-proxy', name: 'DMZ Proxy', description: 'AI-powered security proxy', port: 8088, category: 'Security', icon: 'ShieldCheckIcon' },
  { id: 'license-service', name: 'License Service', description: 'License management and validation', port: 8089, category: 'Administration', icon: 'KeyIcon' },
  { id: 'analytics-service', name: 'Analytics Service', description: 'Metrics, dashboards, predictions', port: 8090, category: 'Observability', icon: 'ChartBarIcon' },
  { id: 'ai-engine', name: 'AI Engine', description: 'Data classification, threat detection, routing AI', port: 8091, category: 'Intelligence', icon: 'CpuChipIcon' },
  { id: 'screening-service', name: 'Screening Service', description: 'OFAC/AML sanctions screening', port: 8092, category: 'Compliance', icon: 'ShieldCheckIcon' },
  { id: 'keystore-manager', name: 'Keystore Manager', description: 'Cryptographic key and certificate management', port: 8093, category: 'Security', icon: 'KeyIcon' },
  { id: 'as2-service', name: 'AS2 Service', description: 'AS2/AS4 B2B messaging protocol', port: 8094, category: 'Protocol', icon: 'ArrowsRightLeftIcon' },
  { id: 'edi-converter', name: 'EDI Converter', description: 'EDI format detection and conversion', port: 8095, category: 'Intelligence', icon: 'DocumentTextIcon' },
  { id: 'storage-manager', name: 'Storage Manager', description: 'Tiered storage with lifecycle management', port: 8096, category: 'Infrastructure', icon: 'CircleStackIcon' },
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
    const res = await axios.get(`http://localhost:${port}/actuator/health`, { timeout: 3000 })
    return { status: 'healthy', details: res.data }
  } catch {
    return { status: 'unreachable', details: null }
  }
}

export default function ServiceManagement() {
  const queryClient = useQueryClient()
  const [categoryFilter, setCategoryFilter] = useState('All')
  const [detailModal, setDetailModal] = useState(null)

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
    refetchInterval: 30000,
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
          <h1 className="text-2xl font-bold text-gray-900">Service Management</h1>
          <p className="text-gray-500 text-sm">Monitor and manage all platform microservices</p>
        </div>
        <div className="flex items-center gap-3">
          {dataUpdatedAt && (
            <span className="text-xs text-gray-400">
              Last checked: {format(new Date(dataUpdatedAt), 'HH:mm:ss')}
            </span>
          )}
          <button className="btn-primary flex items-center gap-2" onClick={handleRefresh}>
            <ArrowPathIcon className="h-4 w-4" />
            Refresh
          </button>
        </div>
      </div>

      {/* Summary Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-blue-50 rounded-lg">
            <Square3Stack3DIcon className="h-6 w-6 text-blue-600" />
          </div>
          <div>
            <p className="text-sm text-gray-500">Total Services</p>
            <p className="text-2xl font-bold text-gray-900">{SERVICES.length}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-green-50 rounded-lg">
            <SignalIcon className="h-6 w-6 text-green-600" />
          </div>
          <div>
            <p className="text-sm text-gray-500">Healthy</p>
            <p className="text-2xl font-bold text-green-600">{healthyCount}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-red-50 rounded-lg">
            <ExclamationTriangleIcon className="h-6 w-6 text-red-600" />
          </div>
          <div>
            <p className="text-sm text-gray-500">Unreachable</p>
            <p className="text-2xl font-bold text-red-600">{unreachableCount}</p>
          </div>
        </div>
        <div className="card flex items-center gap-3">
          <div className="p-2 bg-purple-50 rounded-lg">
            <TagIcon className="h-6 w-6 text-purple-600" />
          </div>
          <div>
            <p className="text-sm text-gray-500">Categories</p>
            <p className="text-2xl font-bold text-gray-900">{uniqueCategories}</p>
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
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
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
                    <h3 className="font-semibold text-gray-900">{svc.name}</h3>
                    <p className="text-sm text-gray-500">{svc.description}</p>
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
          <p className="text-gray-500">No services found in the "{categoryFilter}" category.</p>
        </div>
      )}

      {/* Architecture Overview */}
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-4">Architecture Overview</h3>
        <p className="text-sm text-gray-500 mb-6">
          High-level service dependency topology showing how data flows through the platform.
        </p>
        <div className="overflow-x-auto">
          <div className="flex items-center justify-center gap-0 min-w-[900px] py-4">
            {/* External */}
            <div className="flex flex-col items-center">
              <div className="bg-gray-100 border-2 border-gray-300 rounded-lg px-4 py-3 text-center min-w-[120px]">
                <GlobeAltIcon className="h-5 w-5 text-gray-500 mx-auto mb-1" />
                <p className="text-xs font-semibold text-gray-700">External</p>
                <p className="text-[10px] text-gray-400">Partners / Clients</p>
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
          <div className="flex items-center justify-center gap-4 mt-4 pt-4 border-t border-gray-100">
            <span className="text-xs text-gray-400 mr-2">Supporting:</span>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <KeyIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">Encryption :8086</span>
            </div>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <KeyIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">Keystore :8093</span>
            </div>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <CpuChipIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">AI Engine :8091</span>
            </div>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <ShieldCheckIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">Screening :8092</span>
            </div>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <ChartBarIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">Analytics :8090</span>
            </div>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <DocumentTextIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">EDI :8095</span>
            </div>
            <div className="flex items-center gap-1 bg-gray-50 rounded px-2 py-1">
              <KeyIcon className="h-3 w-3 text-gray-400" />
              <span className="text-[10px] text-gray-500">License :8089</span>
            </div>
          </div>
        </div>
      </div>

      {/* Health Detail Modal */}
      {detailModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setDetailModal(null)}>
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg mx-4" onClick={(e) => e.stopPropagation()}>
            {/* Modal Header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
              <div>
                <h3 className="font-semibold text-gray-900">{detailModal.name}</h3>
                <p className="text-sm text-gray-500">Health Details</p>
              </div>
              <button
                onClick={() => setDetailModal(null)}
                className="p-1 rounded-lg hover:bg-gray-100 transition-colors"
              >
                <XMarkIcon className="h-5 w-5 text-gray-400" />
              </button>
            </div>

            {/* Modal Body */}
            <div className="px-6 py-4 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-gray-400 uppercase tracking-wider">Service ID</p>
                  <p className="text-sm font-mono text-gray-700">{detailModal.id}</p>
                </div>
                <div>
                  <p className="text-xs text-gray-400 uppercase tracking-wider">Port</p>
                  <p className="text-sm font-mono text-gray-700">{detailModal.port}</p>
                </div>
                <div>
                  <p className="text-xs text-gray-400 uppercase tracking-wider">Category</p>
                  <p className="text-sm text-gray-700">{detailModal.category}</p>
                </div>
                <div>
                  <p className="text-xs text-gray-400 uppercase tracking-wider">Status</p>
                  <span className={`badge ${healthMap[detailModal.id]?.status === 'healthy' ? 'badge-green' : 'badge-red'}`}>
                    {healthMap[detailModal.id]?.status === 'healthy' ? 'Healthy' : 'Unreachable'}
                  </span>
                </div>
              </div>

              <div>
                <p className="text-xs text-gray-400 uppercase tracking-wider mb-1">Health Endpoint</p>
                <p className="text-sm font-mono text-gray-500 bg-gray-50 rounded px-2 py-1">
                  http://localhost:{detailModal.port}/actuator/health
                </p>
              </div>

              {dataUpdatedAt && (
                <div>
                  <p className="text-xs text-gray-400 uppercase tracking-wider mb-1">Last Checked</p>
                  <p className="text-sm text-gray-700">
                    {format(new Date(dataUpdatedAt), 'yyyy-MM-dd HH:mm:ss')}
                  </p>
                </div>
              )}

              <div>
                <p className="text-xs text-gray-400 uppercase tracking-wider mb-1">Raw Response</p>
                <pre className="text-xs bg-gray-50 rounded-lg p-3 overflow-auto max-h-60 text-gray-600 border border-gray-100">
                  {healthMap[detailModal.id]?.details
                    ? JSON.stringify(healthMap[detailModal.id].details, null, 2)
                    : 'No response — service is unreachable.'}
                </pre>
              </div>
            </div>

            {/* Modal Footer */}
            <div className="flex justify-end px-6 py-4 border-t border-gray-100">
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
