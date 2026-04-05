import { useState } from 'react'
import axios from 'axios'
import toast from 'react-hot-toast'

const aiApi = axios.create({ baseURL: 'http://localhost:8091' })

export default function Edi() {
  const [content, setContent] = useState('')
  const [result, setResult] = useState(null)
  const [tab, setTab] = useState('detect')

  const run = async (action) => {
    if (!content.trim()) { toast.error('Paste EDI content first'); return }
    try {
      const endpoint = action === 'csv' ? '/api/v1/edi/translate/csv' :
        action === 'json' ? '/api/v1/edi/translate/json' :
        action === 'validate' ? '/api/v1/edi/validate' : '/api/v1/edi/detect'
      const r = await aiApi.post(endpoint, { content })
      setResult({ action, data: r.data }); setTab(action)
    } catch { toast.error('EDI operation failed') }
  }

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">EDI/X12 Translation</h1>
        <p className="text-gray-500 text-sm">Detect, validate, and translate EDI files (837, 835, 850, SWIFT, EDIFACT)</p></div>

      <div className="card">
        <label className="text-sm font-medium text-gray-700 mb-1 block">Paste EDI Content</label>
        <textarea value={content} onChange={e => setContent(e.target.value)} rows={6}
          className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="ISA*00*          *00*          *ZZ*SENDER..." />
        <div className="flex gap-2 mt-3">
          <button onClick={() => run('detect')} className="px-3 py-1.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">Detect Type</button>
          <button onClick={() => run('validate')} className="px-3 py-1.5 bg-green-600 text-white text-sm rounded-lg hover:bg-green-700">Validate</button>
          <button onClick={() => run('json')} className="px-3 py-1.5 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700">→ JSON</button>
          <button onClick={() => run('csv')} className="px-3 py-1.5 bg-amber-600 text-white text-sm rounded-lg hover:bg-amber-700">→ CSV</button>
        </div>
      </div>

      {result && (
        <div className="card">
          <h3 className="font-semibold mb-2 capitalize">{result.action} Result</h3>
          <pre className="text-xs bg-gray-50 rounded p-3 overflow-auto font-mono max-h-96">
            {typeof result.data === 'string' ? result.data : JSON.stringify(result.data, null, 2)}
          </pre>
        </div>
      )}
    </div>
  )
}
