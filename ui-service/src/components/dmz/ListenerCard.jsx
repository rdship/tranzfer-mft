import { SignalIcon, SignalSlashIcon } from '@heroicons/react/24/outline'

export default function ListenerCard({ listener }) {
  const bound = listener.bound
  const healthy = listener.backendHealthy !== false

  return (
    <div className={`rounded-xl border p-4 ${bound ? 'bg-white border-gray-200' : 'bg-red-50 border-red-200'}`}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className={`w-2.5 h-2.5 rounded-full ${bound ? 'bg-green-500 animate-pulse' : 'bg-red-500'}`} />
          {bound
            ? <SignalIcon className="w-4 h-4 text-green-600" />
            : <SignalSlashIcon className="w-4 h-4 text-red-500" />}
          <span className="text-sm font-semibold text-gray-900">{listener.name}</span>
        </div>
        <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
          bound ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
        }`}>
          {bound ? 'Listening' : 'Down'}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 text-xs">
        <div>
          <span className="text-gray-500">Port</span>
          <p className="font-mono font-bold text-blue-700">:{listener.listenPort}</p>
        </div>
        <div>
          <span className="text-gray-500">Connections</span>
          <p className="font-bold text-gray-900">{listener.activeConnections || 0}</p>
        </div>
        <div>
          <span className="text-gray-500">Target</span>
          <p className="font-mono text-gray-700 truncate">{listener.targetHost}:{listener.targetPort}</p>
        </div>
        <div>
          <span className="text-gray-500">Backend</span>
          <p className={`font-medium ${healthy ? 'text-green-600' : 'text-red-600'}`}>
            {healthy ? 'Healthy' : 'Unhealthy'}
          </p>
        </div>
      </div>

      <div className="mt-2 flex items-center gap-2">
        <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
          listener.securityTier === 'AI_LLM' ? 'bg-purple-100 text-purple-700'
            : listener.securityTier === 'AI' ? 'bg-blue-100 text-blue-700'
            : 'bg-gray-100 text-gray-600'
        }`}>
          {listener.securityTier || 'NONE'}
        </span>
        {listener.bytesForwarded > 0 && (
          <span className="text-[10px] text-gray-400">
            {(listener.bytesForwarded / 1048576).toFixed(1)} MB forwarded
          </span>
        )}
      </div>
    </div>
  )
}
