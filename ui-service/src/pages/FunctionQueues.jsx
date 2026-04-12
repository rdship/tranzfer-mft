import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getFunctionQueues, createFunctionQueue, updateFunctionQueue, toggleFunctionQueue, deleteFunctionQueue } from '../api/config'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import toast from 'react-hot-toast'
import {
  PlusIcon, PencilSquareIcon, TrashIcon, QueueListIcon,
  ShieldCheckIcon, ArrowPathIcon, TruckIcon, CpuChipIcon,
  ArrowsUpDownIcon, ClockIcon, ExclamationTriangleIcon,
} from '@heroicons/react/24/outline'

const CATEGORY_META = {
  SECURITY:  { icon: ShieldCheckIcon, color: 'blue',   label: 'Security' },
  TRANSFORM: { icon: ArrowPathIcon,   color: 'purple', label: 'Transform' },
  DELIVERY:  { icon: TruckIcon,       color: 'green',  label: 'Delivery' },
  CUSTOM:    { icon: CpuChipIcon,     color: 'orange', label: 'Custom' },
}

const emptyForm = {
  functionType: '', displayName: '', description: '', category: 'CUSTOM',
  retryCount: 0, retryBackoffMs: 5000, timeoutSeconds: 60,
  minConcurrency: 2, maxConcurrency: 8, messageTtlMs: 600000,
}

export default function FunctionQueues() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editing, setEditing] = useState(null)
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [filter, setFilter] = useState('all')

  const { data: queues = [], isLoading } = useQuery({
    queryKey: ['function-queues'],
    queryFn: getFunctionQueues,
    meta: { errorMessage: 'Failed to load function queues' },
  })

  const createMut = useMutation({
    mutationFn: createFunctionQueue,
    onSuccess: () => { qc.invalidateQueries(['function-queues']); setShowCreate(false); toast.success('Queue created') },
    onError: err => toast.error(err.response?.data?.message || err.response?.data?.error || 'Create failed'),
  })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateFunctionQueue(id, data),
    onSuccess: () => { qc.invalidateQueries(['function-queues']); setEditing(null); toast.success('Queue updated') },
    onError: err => toast.error(err.response?.data?.message || 'Update failed'),
  })
  const toggleMut = useMutation({
    mutationFn: toggleFunctionQueue,
    onSuccess: () => { qc.invalidateQueries(['function-queues']); toast.success('Queue toggled') },
  })
  const deleteMut = useMutation({
    mutationFn: (id) => deleteFunctionQueue(id),
    onSuccess: () => { qc.invalidateQueries(['function-queues']); setConfirmDelete(null); toast.success('Queue deleted') },
    onError: err => toast.error(err.response?.data?.error || 'Delete failed'),
  })

  const grouped = useMemo(() => {
    const filtered = filter === 'all' ? queues : queues.filter(q => q.category === filter)
    const groups = {}
    for (const q of filtered) {
      const cat = q.category || 'CUSTOM'
      if (!groups[cat]) groups[cat] = []
      groups[cat].push(q)
    }
    return groups
  }, [queues, filter])

  const categories = ['all', 'SECURITY', 'TRANSFORM', 'DELIVERY', 'CUSTOM']

  if (isLoading) return <div className="p-8 text-center text-muted">Loading queues...</div>

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-primary flex items-center gap-2">
            <QueueListIcon className="w-6 h-6" /> Function Queues
          </h1>
          <p className="text-sm text-muted mt-1">
            Configure per-step pipeline queues — retry, timeout, concurrency, scaling
          </p>
        </div>
        <button className="btn-primary flex items-center gap-1.5" onClick={() => { setForm(emptyForm); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> Add Queue
        </button>
      </div>

      {/* Category tabs */}
      <div className="flex gap-2">
        {categories.map(c => (
          <button key={c} onClick={() => setFilter(c)}
            className={`px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
              filter === c ? 'bg-blue-600 text-white' : 'bg-canvas text-secondary hover:text-primary border border-border'
            }`}>
            {c === 'all' ? `All (${queues.length})` : `${CATEGORY_META[c]?.label || c} (${queues.filter(q => q.category === c).length})`}
          </button>
        ))}
      </div>

      {/* Queue cards grouped by category */}
      {Object.entries(grouped).map(([category, items]) => {
        const meta = CATEGORY_META[category] || CATEGORY_META.CUSTOM
        const Icon = meta.icon
        return (
          <div key={category}>
            <h2 className="text-sm font-bold text-secondary uppercase tracking-wider mb-3 flex items-center gap-2">
              <Icon className="w-4 h-4" /> {meta.label}
            </h2>
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {items.map(q => (
                <div key={q.id} className={`card p-4 ${!q.enabled ? 'opacity-50' : ''}`}>
                  <div className="flex items-start justify-between">
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="font-semibold text-sm text-primary">{q.displayName}</h3>
                        {q.defaultQueue && <span className="badge badge-blue text-[10px]">default</span>}
                        {q.builtIn && <span className="badge badge-gray text-[10px]">built-in</span>}
                        {!q.enabled && <span className="badge badge-red text-[10px]">disabled</span>}
                      </div>
                      <p className="text-xs text-muted mt-0.5 font-mono">{q.functionType}</p>
                    </div>
                    <div className="flex gap-1">
                      <button onClick={() => { setEditing(q); setForm({...q}) }} className="p-1 rounded hover:bg-blue-50 text-blue-500">
                        <PencilSquareIcon className="w-3.5 h-3.5" />
                      </button>
                      <button onClick={() => toggleMut.mutate(q.id)} className="p-1 rounded hover:bg-yellow-50 text-yellow-600">
                        <ArrowsUpDownIcon className="w-3.5 h-3.5" />
                      </button>
                      {!q.builtIn && (
                        <button onClick={() => setConfirmDelete(q)} className="p-1 rounded hover:bg-red-50 text-red-500">
                          <TrashIcon className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>
                  <p className="text-xs text-secondary mt-2">{q.description}</p>
                  <div className="grid grid-cols-3 gap-2 mt-3 text-xs">
                    <div className="bg-canvas rounded px-2 py-1.5">
                      <span className="text-muted">Retry</span>
                      <span className="block font-bold text-primary">{q.retryCount}x</span>
                    </div>
                    <div className="bg-canvas rounded px-2 py-1.5">
                      <span className="text-muted">Timeout</span>
                      <span className="block font-bold text-primary">{q.timeoutSeconds}s</span>
                    </div>
                    <div className="bg-canvas rounded px-2 py-1.5">
                      <span className="text-muted">Workers</span>
                      <span className="block font-bold text-primary">{q.minConcurrency}-{q.maxConcurrency}</span>
                    </div>
                  </div>
                  {q.activeFlowCount > 0 && (
                    <p className="text-[10px] text-muted mt-2">Used by {q.activeFlowCount} active flow(s)</p>
                  )}
                  <p className="text-[10px] font-mono text-muted mt-1">{q.topicName}</p>
                </div>
              ))}
            </div>
          </div>
        )
      })}

      {/* Create Modal */}
      {showCreate && (
        <Modal title="Add Function Queue" size="lg" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Function Type</label><input value={form.functionType} onChange={e => setForm(f => ({...f, functionType: e.target.value.toUpperCase()}))} required placeholder="WATERMARK_PDF" /></div>
              <div><label>Display Name</label><input value={form.displayName} onChange={e => setForm(f => ({...f, displayName: e.target.value}))} required placeholder="PDF Watermark" /></div>
              <div className="col-span-2"><label>Description</label><input value={form.description} onChange={e => setForm(f => ({...f, description: e.target.value}))} placeholder="What this function does" /></div>
              <div><label>Category</label>
                <select value={form.category} onChange={e => setForm(f => ({...f, category: e.target.value}))}>
                  <option>SECURITY</option><option>TRANSFORM</option><option>DELIVERY</option><option>CUSTOM</option>
                </select>
              </div>
              <div><label>Retry Count</label><input type="number" value={form.retryCount} onChange={e => setForm(f => ({...f, retryCount: +e.target.value}))} min={0} max={10} /></div>
              <div><label>Timeout (seconds)</label><input type="number" value={form.timeoutSeconds} onChange={e => setForm(f => ({...f, timeoutSeconds: +e.target.value}))} min={5} max={600} /></div>
              <div><label>Backoff (ms)</label><input type="number" value={form.retryBackoffMs} onChange={e => setForm(f => ({...f, retryBackoffMs: +e.target.value}))} min={1000} /></div>
              <div><label>Min Workers</label><input type="number" value={form.minConcurrency} onChange={e => setForm(f => ({...f, minConcurrency: +e.target.value}))} min={1} max={32} /></div>
              <div><label>Max Workers</label><input type="number" value={form.maxConcurrency} onChange={e => setForm(f => ({...f, maxConcurrency: +e.target.value}))} min={1} max={64} /></div>
            </div>
            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>Create</button>
            </div>
          </form>
        </Modal>
      )}

      {/* Edit Modal */}
      {editing && (
        <Modal title={`Edit: ${editing.displayName}`} size="lg" onClose={() => setEditing(null)}>
          <form onSubmit={e => { e.preventDefault(); updateMut.mutate({ id: editing.id, data: form }) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Function Type</label><input value={form.functionType} disabled className="opacity-60" /></div>
              <div><label>Display Name</label><input value={form.displayName} onChange={e => setForm(f => ({...f, displayName: e.target.value}))} /></div>
              <div className="col-span-2"><label>Description</label><input value={form.description || ''} onChange={e => setForm(f => ({...f, description: e.target.value}))} /></div>
              <div><label>Retry Count</label><input type="number" value={form.retryCount} onChange={e => setForm(f => ({...f, retryCount: +e.target.value}))} min={0} max={10} /></div>
              <div><label>Timeout (seconds)</label><input type="number" value={form.timeoutSeconds} onChange={e => setForm(f => ({...f, timeoutSeconds: +e.target.value}))} min={5} max={600} /></div>
              <div><label>Backoff (ms)</label><input type="number" value={form.retryBackoffMs} onChange={e => setForm(f => ({...f, retryBackoffMs: +e.target.value}))} min={1000} /></div>
              <div><label>Min Workers</label><input type="number" value={form.minConcurrency} onChange={e => setForm(f => ({...f, minConcurrency: +e.target.value}))} min={1} max={32} /></div>
              <div><label>Max Workers</label><input type="number" value={form.maxConcurrency} onChange={e => setForm(f => ({...f, maxConcurrency: +e.target.value}))} min={1} max={64} /></div>
              <div><label>Message TTL (ms)</label><input type="number" value={form.messageTtlMs} onChange={e => setForm(f => ({...f, messageTtlMs: +e.target.value}))} min={60000} /></div>
            </div>
            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={() => setEditing(null)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={updateMut.isPending}>{updateMut.isPending ? 'Saving...' : 'Save'}</button>
            </div>
          </form>
        </Modal>
      )}

      {/* Delete Confirm */}
      {confirmDelete && (
        <ConfirmDialog
          open
          variant="danger"
          title="Delete Queue?"
          message={confirmDelete.activeFlowCount > 0
            ? `Cannot delete "${confirmDelete.displayName}" — used by ${confirmDelete.activeFlowCount} active flow(s). Remove it from all flows first.`
            : `Delete "${confirmDelete.displayName}"? This cannot be undone.`}
          confirmLabel="Delete"
          loading={deleteMut.isPending}
          onConfirm={() => deleteMut.mutate(confirmDelete.id)}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </div>
  )
}
