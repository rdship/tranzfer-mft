import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import toast from 'react-hot-toast'
import {
  SignalIcon, PlayIcon, PauseIcon, ShieldCheckIcon,
  GlobeAltIcon, ServerIcon,
} from '@heroicons/react/24/outline'

export default function Listeners() {
  const qc = useQueryClient()
  const [filter, setFilter] = useState('all')

  const { data: listeners = [], isLoading } = useQuery({
    queryKey: ['platform-listeners'],
    queryFn: () => onboardingApi.get('/api/platform/listeners').then(r => r.data),
    meta: { silent: true }, refetchInterval: 15000,
    meta: { errorMessage: 'Failed to load listeners' },
  })

  const controlMut = useMutation({
    mutationFn: ({ service, port, action }) =>
      onboardingApi.post(`/api/platform/listeners/${service}/${port}/${action}`).then(r => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries(['platform-listeners'])
      toast.success(`${data.scheme?.toUpperCase() || 'Listener'} on port ${data.port}: ${data.status}`)
    },
    onError: err => toast.error(err.response?.data?.error || 'Control failed'),
  })

  const filtered = filter === 'all' ? listeners
    : filter === 'https' ? listeners.filter(l => l.secure)
    : filter === 'http' ? listeners.filter(l => !l.secure && l.state !== 'OFFLINE')
    : listeners.filter(l => l.state === 'OFFLINE')

  const httpCount = listeners.filter(l => !l.secure && l.state !== 'OFFLINE').length
  const httpsCount = listeners.filter(l => l.secure).length
  const offlineCount = listeners.filter(l => l.state === 'OFFLINE').length

  if (isLoading) return <div className="p-8 text-center text-muted">Loading listeners...</div>

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-primary flex items-center gap-2">
            <SignalIcon className="w-6 h-6" /> Service Listeners
          </h1>
          <p className="text-sm text-muted mt-1">
            HTTP + HTTPS endpoints across all services — view, pause, resume
          </p>
        </div>
        <div className="flex gap-2 text-xs">
          <span className="badge badge-green">{httpsCount} HTTPS</span>
          <span className="badge badge-blue">{httpCount} HTTP</span>
          {offlineCount > 0 && <span className="badge badge-red">{offlineCount} Offline</span>}
        </div>
      </div>

      <div className="flex gap-2">
        {[
          { val: 'all', label: `All (${listeners.length})` },
          { val: 'https', label: `HTTPS (${httpsCount})` },
          { val: 'http', label: `HTTP (${httpCount})` },
          { val: 'offline', label: `Offline (${offlineCount})` },
        ].map(t => (
          <button key={t.val} onClick={() => setFilter(t.val)}
            className={`px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
              filter === t.val ? 'bg-blue-600 text-white' : 'bg-canvas text-secondary hover:text-primary border border-border'
            }`}>{t.label}</button>
        ))}
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
        {filtered.map((l, i) => (
          <div key={i} className={`card p-4 ${l.state === 'OFFLINE' ? 'opacity-50' : ''}`}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                {l.secure ? <ShieldCheckIcon className="w-5 h-5 text-green-500" /> : <GlobeAltIcon className="w-5 h-5 text-blue-400" />}
                <div>
                  <h3 className="font-semibold text-sm text-primary">{l.service}</h3>
                  <p className="text-xs font-mono text-muted">:{l.port} {l.scheme?.toUpperCase()}</p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${
                  l.state === 'STARTED' ? 'bg-green-500' : l.state === 'PAUSED' ? 'bg-yellow-500' : 'bg-red-500'
                }`} />
                <span className="text-xs text-secondary">{l.state || 'OFFLINE'}</span>
              </div>
            </div>

            {l.state !== 'OFFLINE' && (
              <div className="flex gap-2 mt-3">
                {l.state === 'STARTED' && (
                  <button onClick={() => controlMut.mutate({ service: l.service, port: l.port, action: 'pause' })}
                    className="btn-secondary text-xs flex items-center gap-1" disabled={controlMut.isPending}>
                    <PauseIcon className="w-3.5 h-3.5" /> Pause
                  </button>
                )}
                {l.state === 'PAUSED' && (
                  <button onClick={() => controlMut.mutate({ service: l.service, port: l.port, action: 'resume' })}
                    className="btn-primary text-xs flex items-center gap-1" disabled={controlMut.isPending}>
                    <PlayIcon className="w-3.5 h-3.5" /> Resume
                  </button>
                )}
              </div>
            )}

            {l.tlsProtocols && (
              <p className="text-[10px] text-muted mt-2">TLS: {l.tlsProtocols}</p>
            )}
            {l.error && (
              <p className="text-[10px] text-red-400 mt-2">{l.error}</p>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
