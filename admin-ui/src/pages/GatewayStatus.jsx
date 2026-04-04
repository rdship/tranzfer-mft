import { useQuery } from '@tanstack/react-query'
import { onboardingApi, gatewayApi, dmzApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import { WifiIcon, ShieldCheckIcon } from '@heroicons/react/24/outline'

export default function GatewayStatus() {
  const { data: services = [] } = useQuery({
    queryKey: ['service-registry-gw'],
    queryFn: () => onboardingApi.get('/api/service-registry').then(r => r.data).catch(() => []),
    refetchInterval: 15000
  })

  const gatewayServices = services.filter(s => ['GATEWAY','DMZ'].includes(s.serviceType))
  const sftpServices = services.filter(s => s.serviceType === 'SFTP')
  const ftpServices = services.filter(s => s.serviceType === 'FTP')

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Gateway & DMZ</h1>
        <p className="text-gray-500 text-sm">Protocol gateway routing and DMZ proxy status</p></div>

      <div className="grid grid-cols-3 gap-4">
        <div className="card text-center">
          <WifiIcon className="w-8 h-8 text-blue-500 mx-auto mb-2" />
          <p className="text-2xl font-bold text-gray-900">{gatewayServices.length}</p>
          <p className="text-sm text-gray-500">Gateway/DMZ Nodes</p>
        </div>
        <div className="card text-center">
          <div className="w-8 h-8 bg-green-100 rounded-lg flex items-center justify-center mx-auto mb-2">
            <span className="text-green-600 font-bold">S</span>
          </div>
          <p className="text-2xl font-bold text-gray-900">{sftpServices.length}</p>
          <p className="text-sm text-gray-500">SFTP Instances</p>
        </div>
        <div className="card text-center">
          <div className="w-8 h-8 bg-amber-100 rounded-lg flex items-center justify-center mx-auto mb-2">
            <span className="text-amber-600 font-bold">F</span>
          </div>
          <p className="text-2xl font-bold text-gray-900">{ftpServices.length}</p>
          <p className="text-sm text-gray-500">FTP Instances</p>
        </div>
      </div>

      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-3">Route Table</h3>
        <div className="space-y-2 text-sm">
          <div className="flex items-center gap-3 p-3 bg-blue-50 rounded-lg">
            <ShieldCheckIcon className="w-5 h-5 text-blue-600" />
            <span className="font-medium">DMZ:2222</span>
            <span className="text-gray-400">→</span>
            <span>Gateway SFTP:2220</span>
            <span className="text-gray-400">→</span>
            <span>SFTP Service:2222</span>
            <span className="ml-auto badge badge-green">Active</span>
          </div>
          <div className="flex items-center gap-3 p-3 bg-amber-50 rounded-lg">
            <ShieldCheckIcon className="w-5 h-5 text-amber-600" />
            <span className="font-medium">DMZ:2121</span>
            <span className="text-gray-400">→</span>
            <span>Gateway FTP:2121</span>
            <span className="text-gray-400">→</span>
            <span>FTP Service:21</span>
            <span className="ml-auto badge badge-green">Active</span>
          </div>
          <div className="flex items-center gap-3 p-3 bg-green-50 rounded-lg">
            <ShieldCheckIcon className="w-5 h-5 text-green-600" />
            <span className="font-medium">DMZ:443</span>
            <span className="text-gray-400">→</span>
            <span>FTP-Web Service:8083</span>
            <span className="ml-auto badge badge-green">Active</span>
          </div>
        </div>
      </div>

      {gatewayServices.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-3">Service Instances</h3>
          <table className="w-full">
            <thead><tr className="border-b"><th className="table-header">Type</th><th className="table-header">Host</th><th className="table-header">Cluster</th><th className="table-header">Status</th></tr></thead>
            <tbody>
              {[...gatewayServices, ...sftpServices, ...ftpServices].map(s => (
                <tr key={s.id} className="table-row">
                  <td className="table-cell"><span className="badge badge-blue">{s.serviceType}</span></td>
                  <td className="table-cell font-mono text-xs">{s.host}</td>
                  <td className="table-cell text-xs text-gray-500">{s.clusterId}</td>
                  <td className="table-cell"><span className={`badge ${s.active ? 'badge-green' : 'badge-red'}`}>{s.active ? 'UP' : 'DOWN'}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
