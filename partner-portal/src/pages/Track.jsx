import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { trackTransfer, getReceipt } from '../api/client'
import toast from 'react-hot-toast'
import { ShieldCheckIcon, MagnifyingGlassIcon } from '@heroicons/react/24/outline'

const stageIcon = { RECEIVED: '📥', COMPRESS_GZIP: '🗜️', DECOMPRESS_GZIP: '🗜️', ENCRYPT_PGP: '🔒',
  DECRYPT_PGP: '🔓', SCREEN: '🛡️', RENAME: '✏️', DELIVERED: '📤', CONFIRMED: '✅', FAILED: '❌' }
const stageColor = { COMPLETED: 'bg-green-500', OK: 'bg-green-500', FAILED: 'bg-red-500' }

export default function Track({ username }) {
  const { trackId: paramId } = useParams()
  const [trackId, setTrackId] = useState(paramId || '')
  const [journey, setJourney] = useState(null)
  const [receipt, setReceipt] = useState(null)
  const [loading, setLoading] = useState(false)

  const search = async (id) => {
    if (!id) return
    setLoading(true)
    try { setJourney(await trackTransfer(id, username)); setReceipt(null) }
    catch { toast.error('Transfer not found: ' + id) }
    finally { setLoading(false) }
  }

  useEffect(() => { if (paramId) search(paramId) }, [paramId])

  const downloadReceipt = async () => {
    try {
      const r = await getReceipt(journey.trackId, username)
      setReceipt(r)
      toast.success('Delivery receipt generated')
    } catch { toast.error('Could not generate receipt') }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-900">Track a Transfer</h1>

      <div className="bg-white rounded-xl border p-5 flex gap-3">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-2.5 w-4 h-4 text-gray-400" />
          <input value={trackId} onChange={e => setTrackId(e.target.value.toUpperCase())}
            onKeyDown={e => e.key === 'Enter' && search(trackId)}
            placeholder="Enter Track ID (e.g. TRZA3X5T3LUY)"
            className="w-full pl-9 rounded-lg border border-gray-200 px-3 py-2 text-sm font-mono focus:ring-2 focus:ring-blue-500" />
        </div>
        <button onClick={() => search(trackId)} disabled={loading}
          className="px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50">
          {loading ? 'Searching...' : 'Track'}
        </button>
      </div>

      {journey && (
        <div className="bg-white rounded-xl border">
          <div className="p-5 border-b flex items-center justify-between">
            <div>
              <h2 className="text-lg font-bold font-mono text-gray-900">{journey.trackId}</h2>
              <p className="text-sm text-gray-500">{journey.filename}</p>
            </div>
            <div className="flex items-center gap-3">
              <span className={`text-xs px-2.5 py-1 rounded-full font-medium ${journey.status === 'FAILED' ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700'}`}>
                {journey.status}
              </span>
              <button onClick={downloadReceipt} className="text-xs px-3 py-1.5 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors">
                📄 Receipt
              </button>
            </div>
          </div>

          {/* Integrity */}
          <div className={`mx-5 mt-4 p-3 rounded-lg flex items-center gap-2 text-sm ${journey.integrity === 'VERIFIED' ? 'bg-green-50 text-green-800' : 'bg-gray-50 text-gray-600'}`}>
            <ShieldCheckIcon className="w-4 h-4" />
            <span className="font-medium">Integrity: {journey.integrity}</span>
            {journey.sourceChecksum && <span className="font-mono text-xs ml-auto">SHA-256: {journey.sourceChecksum.substring(0, 16)}...</span>}
          </div>

          {/* Journey stages */}
          <div className="p-5 space-y-0">
            {journey.stages?.map((stage, i) => (
              <div key={i} className="flex items-start gap-3 relative">
                {i < journey.stages.length - 1 && <div className="absolute left-4 top-8 w-0.5 h-full bg-gray-200" />}
                <div className={`w-8 h-8 rounded-full flex items-center justify-center text-white text-sm flex-shrink-0 z-10 ${stageColor[stage.status] || 'bg-gray-400'}`}>
                  {stageIcon[stage.stage] || '○'}
                </div>
                <div className="flex-1 pb-4">
                  <div className="flex items-center justify-between">
                    <span className="font-medium text-sm text-gray-900">{stage.stage}</span>
                    <span className={`text-xs px-1.5 py-0.5 rounded ${stage.status === 'COMPLETED' || stage.status === 'OK' ? 'bg-green-100 text-green-700' : stage.status === 'FAILED' ? 'bg-red-100 text-red-700' : 'bg-gray-100'}`}>{stage.status}</span>
                  </div>
                  {stage.detail && <p className="text-xs text-gray-500 mt-0.5">{stage.detail}</p>}
                  {stage.timestamp && <p className="text-xs text-gray-400">{stage.timestamp}</p>}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Delivery Receipt */}
      {receipt && (
        <div className="bg-white rounded-xl border p-5">
          <h3 className="font-semibold text-gray-900 mb-3">📄 Delivery Receipt</h3>
          <div className="bg-gray-50 rounded-lg p-4 font-mono text-xs space-y-1">
            <p>Receipt ID:     {receipt.receiptId}</p>
            <p>Track ID:       {receipt.trackId}</p>
            <p>File:           {receipt.filename}</p>
            <p>Size:           {receipt.fileSizeBytes} bytes</p>
            <p>Status:         {receipt.status}</p>
            <p>Source SHA-256: {receipt.sourceChecksum}</p>
            <p>Dest SHA-256:   {receipt.destinationChecksum}</p>
            <p>Integrity:      {receipt.integrityVerified ? 'VERIFIED ✅' : 'PENDING'}</p>
            <p>Uploaded:       {receipt.uploadedAt}</p>
            <p>Delivered:      {receipt.deliveredAt}</p>
            <p>Generated:      {receipt.generatedAt}</p>
            <p className="pt-2 text-gray-500">{receipt.notice}</p>
          </div>
        </div>
      )}
    </div>
  )
}
