import { useState } from 'react'
import { onboardingApi } from '../api/client'
import toast from 'react-hot-toast'
import { CodeBracketIcon, ArrowUpTrayIcon } from '@heroicons/react/24/outline'

export default function ApiConsole() {
  const [file, setFile] = useState(null)
  const [sender, setSender] = useState('')
  const [dest, setDest] = useState('')
  const [result, setResult] = useState(null)
  const [pollResult, setPollResult] = useState(null)

  const sendTransfer = async () => {
    if (!file || !sender) { toast.error('File and sender required'); return }
    const form = new FormData()
    form.append('file', file)
    form.append('sender', sender)
    if (dest) form.append('destination', dest)
    try {
      const r = await onboardingApi.post('/api/v2/transfer', form, { headers: { 'Content-Type': 'multipart/form-data' } })
      setResult(r.data); toast.success('Transfer accepted: ' + r.data.trackId)
    } catch (e) { toast.error(e.response?.data?.error || 'Failed') }
  }

  const pollStatus = async (trackId) => {
    try {
      const r = await onboardingApi.get(`/api/v2/transfer/${trackId}`)
      setPollResult(r.data)
    } catch { toast.error('Not found') }
  }

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-primary flex items-center gap-2"><CodeBracketIcon className="w-7 h-7 text-blue-600" /> Transfer API v2</h1>
        <p className="text-secondary text-sm">Developer console — test the single-call transfer API</p></div>

      <div className="card space-y-4">
        <h3 className="font-semibold">Send a Transfer</h3>
        <div className="grid grid-cols-3 gap-3">
          <div><label className="text-sm font-medium text-primary mb-1 block">File</label>
            <input type="file" onChange={e => setFile(e.target.files[0])} className="text-sm" /></div>
          <div><label className="text-sm font-medium text-primary mb-1 block">Sender Account</label>
            <input value={sender} onChange={e => setSender(e.target.value)} placeholder="client_a_sender" className="w-full rounded-lg border px-3 py-2 text-sm" /></div>
          <div><label className="text-sm font-medium text-primary mb-1 block">Destination (optional)</label>
            <input value={dest} onChange={e => setDest(e.target.value)} placeholder="client_b_receiver" className="w-full rounded-lg border px-3 py-2 text-sm" /></div>
        </div>
        <button onClick={sendTransfer} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">
          <ArrowUpTrayIcon className="w-4 h-4 inline mr-1" /> Send Transfer
        </button>
      </div>

      {result && (
        <div className="card bg-green-50 border-green-200">
          <h3 className="font-semibold text-green-900 mb-2">Transfer Accepted</h3>
          <pre className="text-xs bg-surface rounded p-3 overflow-auto font-mono">{JSON.stringify(result, null, 2)}</pre>
          <button onClick={() => pollStatus(result.trackId)} className="mt-3 text-xs px-3 py-1 bg-blue-600 text-white rounded">Poll Status</button>
        </div>
      )}

      {pollResult && (
        <div className="card"><h3 className="font-semibold mb-2">Transfer Status</h3>
          <pre className="text-xs bg-canvas rounded p-3 overflow-auto font-mono">{JSON.stringify(pollResult, null, 2)}</pre>
        </div>
      )}

      <div className="card">
        <h3 className="font-semibold mb-3">API Reference</h3>
        <div className="space-y-2 text-sm font-mono">
          <div className="p-2 bg-canvas rounded"><span className="text-green-600 font-bold">POST</span> /api/v2/transfer — Single-call file transfer</div>
          <div className="p-2 bg-canvas rounded"><span className="text-blue-600 font-bold">GET</span> /api/v2/transfer/{'{trackId}'} — Poll transfer status</div>
          <div className="p-2 bg-canvas rounded"><span className="text-green-600 font-bold">POST</span> /api/v2/transfer/batch — Batch transfer (multiple files)</div>
          <div className="p-2 bg-canvas rounded"><span className="text-blue-600 font-bold">GET</span> /api/v2/transfer/{'{trackId}'}/receipt — Delivery receipt</div>
        </div>
      </div>
    </div>
  )
}
