import { useState } from 'react'
import { ShieldCheckIcon } from '@heroicons/react/24/outline'
import { checkZone } from '../../api/dmz'

export default function ZoneCheckForm() {
  const [sourceIp, setSourceIp] = useState('')
  const [targetHost, setTargetHost] = useState('')
  const [targetPort, setTargetPort] = useState('')
  const [result, setResult] = useState(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')

  const handleCheck = async () => {
    if (!sourceIp.trim() || !targetHost.trim() || !targetPort) return
    setChecking(true)
    setError('')
    setResult(null)
    try {
      const res = await checkZone(sourceIp.trim(), targetHost.trim(), parseInt(targetPort))
      setResult(res)
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Zone check failed')
    } finally {
      setChecking(false)
    }
  }

  return (
    <div className="rounded-xl border border-gray-200 bg-white overflow-hidden">
      <div className="px-4 py-3 bg-gray-50 border-b border-gray-200">
        <h3 className="text-sm font-semibold text-gray-900">Zone Transition Check</h3>
        <p className="text-xs text-gray-500">Test if a source IP can reach a target through zone enforcement</p>
      </div>

      <div className="p-4 space-y-3">
        <div className="grid grid-cols-3 gap-2">
          <input type="text" value={sourceIp} onChange={(e) => setSourceIp(e.target.value)}
            placeholder="Source IP" className="px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-400" />
          <input type="text" value={targetHost} onChange={(e) => setTargetHost(e.target.value)}
            placeholder="Target Host" className="px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-400" />
          <div className="flex gap-2">
            <input type="number" value={targetPort} onChange={(e) => setTargetPort(e.target.value)}
              placeholder="Port" min="1" max="65535"
              className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-400" />
            <button onClick={handleCheck} disabled={checking || !sourceIp.trim() || !targetHost.trim() || !targetPort}
              className="px-3 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-50 transition-colors">
              <ShieldCheckIcon className="w-4 h-4" />
            </button>
          </div>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-3 py-2 text-sm">{error}</div>
        )}

        {result && (
          <div className={`rounded-lg border p-3 ${result.allowed ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
            <div className="flex items-center gap-2 mb-2">
              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                result.allowed ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
              }`}>
                {result.allowed ? 'ALLOWED' : 'BLOCKED'}
              </span>
              <span className="text-xs text-gray-500">{result.reason}</span>
            </div>
            <div className="flex items-center gap-4 text-xs text-gray-600">
              <span><strong>{result.sourceZone}</strong> ({result.sourceIp})</span>
              <span>→</span>
              <span><strong>{result.targetZone}</strong> ({result.targetHost}:{result.targetPort})</span>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
