import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { ShieldCheckIcon, MagnifyingGlassIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Blockchain() {
  const [verifyId, setVerifyId] = useState('')
  const [proof, setProof] = useState(null)

  const { data: anchors = [], isLoading } = useQuery({ queryKey: ['bc-anchors'],
    queryFn: () => onboardingApi.get('/api/v1/blockchain/anchors').then(r => r.data).catch(() => []) })

  const verify = async () => {
    if (!verifyId) return
    try {
      const r = await onboardingApi.get(`/api/v1/blockchain/verify/${verifyId}`)
      setProof(r.data); toast.success(r.data.verified ? 'Verified!' : 'Not verified')
    } catch { toast.error('Verification failed') }
  }

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-primary">Blockchain Notarization</h1>
        <p className="text-secondary text-sm">Immutable cryptographic proof of every file transfer — non-repudiation</p></div>

      <div className="card flex gap-3">
        <div className="relative flex-1"><MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted pointer-events-none" />
          <input value={verifyId} onChange={e => setVerifyId(e.target.value.toUpperCase())}
            onKeyDown={e => e.key === 'Enter' && verify()} placeholder="Enter Track ID to verify..."
            className="w-full pl-10 pr-3 py-2 text-sm border rounded-lg font-mono focus:ring-2 focus:ring-blue-500" /></div>
        <button onClick={verify} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">Verify</button>
      </div>

      {proof && (
        <div className={`card border ${proof.verified ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'}`}>
          <div className="flex items-center gap-2 mb-3">
            <ShieldCheckIcon className={`w-6 h-6 ${proof.verified ? 'text-green-600' : 'text-red-600'}`} />
            <h3 className="font-bold text-lg">{proof.verified ? 'VERIFIED' : 'NOT VERIFIED'}</h3>
          </div>
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div><span className="text-secondary">Track ID:</span> <strong className="font-mono">{proof.trackId}</strong></div>
            <div><span className="text-secondary">File:</span> <strong>{proof.filename}</strong></div>
            <div><span className="text-secondary">Chain:</span> <strong>{proof.chain}</strong></div>
            <div><span className="text-secondary">Anchored:</span> <strong>{proof.anchoredAt}</strong></div>
            <div className="col-span-2"><span className="text-secondary">SHA-256:</span> <code className="text-xs font-mono break-all">{proof.sha256}</code></div>
            <div className="col-span-2"><span className="text-secondary">Merkle Root:</span> <code className="text-xs font-mono break-all">{proof.merkleRoot}</code></div>
          </div>
          <p className="text-xs text-secondary mt-3 italic">{proof.nonRepudiation}</p>
        </div>
      )}

      <div className="card">
        <h3 className="font-semibold text-primary mb-3">Recent Anchors ({anchors.length})</h3>
        {anchors.length === 0 ? <p className="text-sm text-secondary">No anchors yet. Transfers are anchored every hour.</p> : (
          <table className="w-full"><thead><tr className="border-b">
            <th className="table-header">Track ID</th><th className="table-header">File</th><th className="table-header">Chain</th><th className="table-header">Anchored</th>
          </tr></thead><tbody>
            {anchors.slice(0, 20).map(a => (
              <tr key={a.id} className="table-row cursor-pointer hover:bg-[rgba(100,140,255,0.1)]" onClick={() => { setVerifyId(a.trackId); verify() }}>
                <td className="table-cell font-mono text-xs font-bold text-blue-600">{a.trackId}</td>
                <td className="table-cell text-sm">{a.filename}</td>
                <td className="table-cell"><span className="badge badge-blue">{a.chain}</span></td>
                <td className="table-cell text-xs text-secondary">{a.anchoredAt ? format(new Date(a.anchoredAt), 'MMM d HH:mm') : ''}</td>
              </tr>
            ))}
          </tbody></table>
        )}
      </div>
    </div>
  )
}
