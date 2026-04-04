import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PlayIcon, ArrowPathIcon } from '@heroicons/react/24/outline'

const STEP_TYPES = [
  { value: 'DECOMPRESS_GZIP', label: 'Decompress (GZIP)', category: 'Compression' },
  { value: 'DECOMPRESS_ZIP', label: 'Decompress (ZIP)', category: 'Compression' },
  { value: 'COMPRESS_GZIP', label: 'Compress (GZIP)', category: 'Compression' },
  { value: 'COMPRESS_ZIP', label: 'Compress (ZIP)', category: 'Compression' },
  { value: 'DECRYPT_PGP', label: 'Decrypt (PGP)', category: 'Encryption' },
  { value: 'DECRYPT_AES', label: 'Decrypt (AES)', category: 'Encryption' },
  { value: 'ENCRYPT_PGP', label: 'Encrypt (PGP)', category: 'Encryption' },
  { value: 'ENCRYPT_AES', label: 'Encrypt (AES)', category: 'Encryption' },
  { value: 'RENAME', label: 'Rename File', category: 'Transform' },
  { value: 'ROUTE', label: 'Route to Destination', category: 'Routing' },
]

const defaultForm = {
  name: '', description: '', filenamePattern: '', sourcePath: '/inbox',
  destinationPath: '/outbox', priority: 100, steps: []
}

export default function Flows() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ ...defaultForm })

  const { data: flows = [], isLoading } = useQuery({
    queryKey: ['flows'],
    queryFn: () => configApi.get('/api/flows').then(r => r.data).catch(() => [])
  })

  const { data: executions } = useQuery({
    queryKey: ['flow-executions'],
    queryFn: () => configApi.get('/api/flows/executions?size=10').then(r => r.data?.content || []).catch(() => []),
    refetchInterval: 10000
  })

  const createMut = useMutation({
    mutationFn: (data) => configApi.post('/api/flows', data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['flows']); setShowCreate(false); setForm({...defaultForm}); toast.success('Flow created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })

  const toggleMut = useMutation({
    mutationFn: (id) => configApi.patch(`/api/flows/${id}/toggle`).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['flows']); toast.success('Flow toggled') }
  })

  const addStep = (type) => {
    setForm(f => ({
      ...f, steps: [...f.steps, { type, config: {}, order: f.steps.length }]
    }))
  }

  const removeStep = (idx) => {
    setForm(f => ({ ...f, steps: f.steps.filter((_, i) => i !== idx).map((s, i) => ({...s, order: i})) }))
  }

  const statusColor = { COMPLETED: 'badge-green', FAILED: 'badge-red', PROCESSING: 'badge-yellow', PENDING: 'badge-gray' }

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">File Processing Flows</h1>
          <p className="text-gray-500 text-sm">Define pipelines: decrypt → decompress → rename → route</p>
        </div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}>
          <PlusIcon className="w-4 h-4" /> New Flow
        </button>
      </div>

      {/* Active Flows */}
      {flows.length === 0 ? (
        <div className="card"><EmptyState title="No flows configured" description="Create a flow to define how files are processed when they arrive." /></div>
      ) : (
        <div className="grid gap-4">
          {flows.map(flow => (
            <div key={flow.id} className="card">
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="font-semibold text-gray-900">{flow.name}</h3>
                    <span className={`badge ${flow.active ? 'badge-green' : 'badge-red'}`}>
                      {flow.active ? 'Active' : 'Disabled'}
                    </span>
                    <span className="badge badge-gray">P{flow.priority}</span>
                  </div>
                  <p className="text-sm text-gray-500 mt-0.5">{flow.description}</p>
                  {flow.filenamePattern && (
                    <p className="text-xs text-gray-400 mt-1 font-mono">Pattern: {flow.filenamePattern}</p>
                  )}
                </div>
                <button onClick={() => toggleMut.mutate(flow.id)}
                  className="btn-secondary text-xs px-2 py-1">
                  {flow.active ? 'Disable' : 'Enable'}
                </button>
              </div>
              {/* Steps visualization */}
              <div className="mt-3 flex items-center gap-1 flex-wrap">
                {(flow.steps || []).map((step, i) => (
                  <div key={i} className="flex items-center gap-1">
                    {i > 0 && <span className="text-gray-300">→</span>}
                    <span className="px-2 py-1 bg-blue-50 text-blue-700 text-xs font-mono rounded">
                      {step.type}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Recent Executions */}
      {executions?.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-3">Recent Executions</h3>
          <table className="w-full">
            <thead><tr className="border-b border-gray-100">
              <th className="table-header">Track ID</th>
              <th className="table-header">Flow</th>
              <th className="table-header">File</th>
              <th className="table-header">Status</th>
              <th className="table-header">Step</th>
            </tr></thead>
            <tbody>
              {executions.map(ex => (
                <tr key={ex.trackId} className="table-row">
                  <td className="table-cell font-mono text-xs font-bold text-blue-600">{ex.trackId}</td>
                  <td className="table-cell text-sm">{ex.flow?.name || '—'}</td>
                  <td className="table-cell text-xs text-gray-500 truncate max-w-40">{ex.originalFilename}</td>
                  <td className="table-cell"><span className={`badge ${statusColor[ex.status] || 'badge-gray'}`}>{ex.status}</span></td>
                  <td className="table-cell text-xs">{ex.currentStep}/{ex.flow?.steps?.length || '?'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Create Flow Modal */}
      {showCreate && (
        <Modal title="Create Processing Flow" size="xl" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div><label>Flow Name</label>
                <input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="e.g. partner-inbound-pgp" /></div>
              <div><label>Priority (lower = first)</label>
                <input type="number" value={form.priority} onChange={e => setForm(f => ({...f, priority: parseInt(e.target.value)}))} /></div>
            </div>
            <div><label>Description</label>
              <input value={form.description} onChange={e => setForm(f => ({...f, description: e.target.value}))} placeholder="What this flow does..." /></div>
            <div className="grid grid-cols-2 gap-4">
              <div><label>Filename Pattern (regex, empty=all)</label>
                <input value={form.filenamePattern} onChange={e => setForm(f => ({...f, filenamePattern: e.target.value}))} placeholder=".*\.pgp$" className="font-mono text-xs" /></div>
              <div><label>Source Path</label>
                <input value={form.sourcePath} onChange={e => setForm(f => ({...f, sourcePath: e.target.value}))} placeholder="/inbox" /></div>
            </div>

            {/* Steps Builder */}
            <div>
              <label>Processing Steps (executed in order)</label>
              <div className="mt-2 space-y-2">
                {form.steps.map((step, i) => (
                  <div key={i} className="flex items-center gap-2 p-2 bg-gray-50 rounded-lg">
                    <span className="text-xs font-bold text-gray-400 w-6">{i + 1}.</span>
                    <span className="px-2 py-1 bg-blue-100 text-blue-700 text-xs font-mono rounded flex-1">{step.type}</span>
                    {step.type === 'RENAME' && (
                      <input value={step.config?.pattern || ''} onChange={e => {
                        const steps = [...form.steps]; steps[i] = {...step, config: {pattern: e.target.value}}; setForm(f => ({...f, steps}))
                      }} placeholder="${basename}_${trackid}${ext}" className="text-xs font-mono flex-1" />
                    )}
                    <button type="button" onClick={() => removeStep(i)} className="p-1 text-red-400 hover:text-red-600">
                      <TrashIcon className="w-4 h-4" />
                    </button>
                  </div>
                ))}
                {form.steps.length === 0 && <p className="text-xs text-gray-400 py-2">No steps added. Click below to add processing steps.</p>}
              </div>
              <div className="mt-3 flex flex-wrap gap-1">
                {STEP_TYPES.map(st => (
                  <button key={st.value} type="button" onClick={() => addStep(st.value)}
                    className="px-2 py-1 text-xs bg-gray-100 text-gray-600 rounded hover:bg-blue-100 hover:text-blue-700 transition-colors">
                    + {st.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex gap-3 justify-end pt-2 border-t">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending || form.steps.length === 0}>
                {createMut.isPending ? 'Creating...' : 'Create Flow'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
