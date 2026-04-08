import { useState } from 'react'
import { PlayIcon, CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/outline'
import { testReachability } from '../../api/dmz'

export default function ReachabilityTester() {
  const [host, setHost] = useState('')
  const [port, setPort] = useState('')
  const [result, setResult] = useState(null)
  const [testing, setTesting] = useState(false)
  const [error, setError] = useState('')

  const handleTest = async () => {
    if (!host.trim() || !port) return
    setTesting(true)
    setError('')
    setResult(null)
    try {
      const res = await testReachability(host.trim(), parseInt(port))
      setResult(res)
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Test failed')
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="rounded-xl border border-gray-200 bg-white overflow-hidden">
      <div className="px-4 py-3 bg-gray-50 border-b border-gray-200">
        <h3 className="text-sm font-semibold text-gray-900">TCP Reachability Test</h3>
        <p className="text-xs text-gray-500">Test connectivity from DMZ proxy to a target host:port</p>
      </div>

      <div className="p-4 space-y-3">
        <div className="flex gap-2">
          <input
            type="text"
            value={host}
            onChange={(e) => setHost(e.target.value)}
            placeholder="Host (e.g. sftp-server.internal)"
            className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <input
            type="number"
            value={port}
            onChange={(e) => setPort(e.target.value)}
            placeholder="Port"
            min="1"
            max="65535"
            className="w-24 px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <button
            onClick={handleTest}
            disabled={testing || !host.trim() || !port}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-1.5"
          >
            <PlayIcon className="w-4 h-4" />
            {testing ? 'Testing...' : 'Test'}
          </button>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-3 py-2 text-sm">{error}</div>
        )}

        {result && (
          <div className={`rounded-lg border p-3 flex items-start gap-3 ${
            result.reachable ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'
          }`}>
            {result.reachable
              ? <CheckCircleIcon className="w-5 h-5 text-green-500 flex-shrink-0 mt-0.5" />
              : <XCircleIcon className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />}
            <div className="text-sm">
              <p className={`font-semibold ${result.reachable ? 'text-green-700' : 'text-red-700'}`}>
                {result.host}:{result.port} — {result.reachable ? 'Reachable' : 'Unreachable'}
              </p>
              <p className="text-gray-600 mt-0.5">
                Latency: {result.latencyMs}ms
                {result.error && <span className="ml-2 text-red-600">({result.error})</span>}
              </p>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
