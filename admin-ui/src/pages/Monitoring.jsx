import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { format } from 'date-fns'

const serviceUrls = {
  'onboarding-api': 'http://localhost:8080/actuator/health',
  'config-service': 'http://localhost:8084/actuator/health',
  'gateway-service': 'http://localhost:8085/actuator/health',
  'encryption-service': 'http://localhost:8086/actuator/health',
  'external-forwarder': 'http://localhost:8087/actuator/health',
  'license-service': 'http://localhost:8089/actuator/health',
  'analytics-service': 'http://localhost:8090/actuator/health',
}

export default function Monitoring() {
  const { data: services = [], isLoading, dataUpdatedAt } = useQuery({
    queryKey: ['service-registry'],
    queryFn: () => onboardingApi.get('/api/service-registry').then(r => r.data).catch(() => []),
    refetchInterval: 30000
  })

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Service Health</h1>
          <p className="text-gray-500 text-sm">Auto-refreshes every 30s — last updated {dataUpdatedAt ? format(new Date(dataUpdatedAt), 'HH:mm:ss') : '...'}</p>
        </div>
      </div>
      <div className="card">
        <table className="w-full">
          <thead><tr className="border-b border-gray-100">
            <th className="table-header">Service</th>
            <th className="table-header">Type</th>
            <th className="table-header">Host</th>
            <th className="table-header">Cluster</th>
            <th className="table-header">Status</th>
            <th className="table-header">Registered</th>
          </tr></thead>
          <tbody>
            {services.length === 0 ? (
              <tr><td colSpan={6} className="text-center py-8 text-gray-500 text-sm">No services registered</td></tr>
            ) : services.map(s => (
              <tr key={s.id} className="table-row">
                <td className="table-cell font-medium font-mono text-xs">{s.serviceType}</td>
                <td className="table-cell"><span className="badge badge-blue">{s.serviceType}</span></td>
                <td className="table-cell text-gray-500 text-xs">{s.host}</td>
                <td className="table-cell text-gray-500 text-xs">{s.clusterId}</td>
                <td className="table-cell"><span className={`badge ${s.active ? 'badge-green' : 'badge-red'}`}>{s.active ? 'UP' : 'DOWN'}</span></td>
                <td className="table-cell text-gray-500 text-xs">{s.registeredAt ? format(new Date(s.registeredAt), 'MMM d HH:mm') : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-4">Service Endpoints</h3>
        <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
          {Object.entries(serviceUrls).map(([name, url]) => (
            <a key={name} href={url} target="_blank" rel="noopener noreferrer"
              className="flex items-center gap-2 p-3 bg-gray-50 rounded-lg hover:bg-blue-50 transition-colors text-sm">
              <div className="w-2 h-2 bg-green-400 rounded-full" />
              <span className="font-medium text-gray-700">{name}</span>
            </a>
          ))}
        </div>
      </div>
    </div>
  )
}
