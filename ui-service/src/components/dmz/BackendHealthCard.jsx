import { CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/outline'

export default function BackendHealthCard({ name, health }) {
  const healthy = health?.healthy !== false
  const failures = health?.consecutiveFailures || 0
  const lastCheck = health?.lastCheckTime

  return (
    <div className={`rounded-xl border p-4 ${healthy ? 'bg-white border-gray-200' : 'bg-red-50 border-red-200'}`}>
      <div className="flex items-center gap-2 mb-2">
        {healthy
          ? <CheckCircleIcon className="w-5 h-5 text-green-500" />
          : <XCircleIcon className="w-5 h-5 text-red-500" />}
        <span className="text-sm font-semibold text-gray-900">{name}</span>
        <span className={`ml-auto px-2 py-0.5 rounded-full text-xs font-medium ${
          healthy ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
        }`}>
          {healthy ? 'Healthy' : 'Unhealthy'}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 text-xs">
        {health?.host && (
          <div>
            <span className="text-gray-500">Target</span>
            <p className="font-mono text-gray-700">{health.host}:{health.port}</p>
          </div>
        )}
        <div>
          <span className="text-gray-500">Failures</span>
          <p className={`font-bold ${failures > 0 ? 'text-red-600' : 'text-gray-600'}`}>{failures}</p>
        </div>
        {lastCheck && (
          <div className="col-span-2">
            <span className="text-gray-500">Last Check</span>
            <p className="text-gray-600">{new Date(lastCheck).toLocaleString()}</p>
          </div>
        )}
      </div>
    </div>
  )
}
