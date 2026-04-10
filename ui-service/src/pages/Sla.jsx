import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, PencilSquareIcon, TrashIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

const emptyForm = { name: '', expectedDeliveryStartHour: 0, expectedDeliveryEndHour: 6, minFilesPerWindow: 1, gracePeriodMinutes: 30, expectedDays: ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'] }
const ALL_DAYS = ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY']

export default function Sla() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingSla, setEditingSla] = useState(null)
  const [form, setForm] = useState({ ...emptyForm })

  const { data: slas = [], isLoading } = useQuery({ queryKey: ['slas'], queryFn: () => configApi.get('/api/sla').then(r => r.data).catch(() => []) })
  const { data: breaches = [] } = useQuery({ queryKey: ['sla-breaches'], queryFn: () => configApi.get('/api/sla/breaches').then(r => r.data).catch(() => []), refetchInterval: 60000 })

  const createMut = useMutation({ mutationFn: (d) => configApi.post('/api/sla', d),
    onSuccess: () => { qc.invalidateQueries(['slas']); setShowCreate(false); setForm({ ...emptyForm }); toast.success('SLA created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const updateMut = useMutation({ mutationFn: ({ id, data }) => configApi.put(`/api/sla/${id}`, data),
    onSuccess: () => { qc.invalidateQueries(['slas']); setEditingSla(null); setForm({ ...emptyForm }); toast.success('SLA updated') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const deleteMut = useMutation({ mutationFn: (id) => configApi.delete(`/api/sla/${id}`),
    onSuccess: () => { qc.invalidateQueries(['slas']); toast.success('SLA deleted') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })

  const openEdit = (s) => {
    setForm({
      name: s.name || '',
      expectedDeliveryStartHour: s.expectedDeliveryStartHour ?? 0,
      expectedDeliveryEndHour: s.expectedDeliveryEndHour ?? 6,
      minFilesPerWindow: s.minFilesPerWindow ?? 1,
      gracePeriodMinutes: s.gracePeriodMinutes ?? 30,
      expectedDays: s.expectedDays || ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'],
    })
    setEditingSla(s)
  }

  const openCreate = () => {
    setForm({ ...emptyForm, expectedDays: [...emptyForm.expectedDays] })
    setShowCreate(true)
  }

  const toggleDay = (day) => {
    setForm(f => ({
      ...f,
      expectedDays: f.expectedDays.includes(day) ? f.expectedDays.filter(d => d !== day) : [...f.expectedDays, day]
    }))
  }

  if (isLoading) return <LoadingSpinner />

  const renderForm = (onSubmit, isPending, submitLabel, pendingLabel) => (
    <form onSubmit={onSubmit} className="space-y-4">
      <div><label>Agreement Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="partner-acme-daily" /></div>
      <div className="grid grid-cols-3 gap-3">
        <div><label>Delivery Start (UTC hour)</label><input type="number" value={form.expectedDeliveryStartHour} onChange={e => setForm(f => ({...f, expectedDeliveryStartHour: +e.target.value}))} min={0} max={23} /></div>
        <div><label>Delivery End (UTC hour)</label><input type="number" value={form.expectedDeliveryEndHour} onChange={e => setForm(f => ({...f, expectedDeliveryEndHour: +e.target.value}))} min={0} max={23} /></div>
        <div><label>Min Files</label><input type="number" value={form.minFilesPerWindow} onChange={e => setForm(f => ({...f, minFilesPerWindow: +e.target.value}))} min={1} /></div>
      </div>
      <div><label>Grace Period (minutes)</label><input type="number" value={form.gracePeriodMinutes} onChange={e => setForm(f => ({...f, gracePeriodMinutes: +e.target.value}))} min={0} /></div>
      <div>
        <label>Expected Days</label>
        <div className="flex flex-wrap gap-2 mt-2">
          {ALL_DAYS.map(day => (
            <button key={day} type="button" onClick={() => toggleDay(day)}
              className={`px-3 py-1 rounded-lg text-xs font-medium transition-colors ${form.expectedDays.includes(day) ? 'bg-blue-100 text-blue-800 border border-blue-200' : 'bg-gray-100 text-secondary border border-border'}`}>
              {day.substring(0, 3)}
            </button>
          ))}
        </div>
      </div>
      <div className="flex gap-3 justify-end">
        <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); setEditingSla(null) }}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{isPending ? pendingLabel : submitLabel}</button>
      </div>
    </form>
  )

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">SLA Agreements</h1>
          <p className="text-secondary text-sm">Partner delivery agreements and breach monitoring</p></div>
        <button className="btn-primary" onClick={openCreate}><PlusIcon className="w-4 h-4" /> New Agreement</button>
      </div>
      {breaches.length > 0 && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-4">
          <h3 className="font-semibold text-red-800 flex items-center gap-2"><ExclamationTriangleIcon className="w-4 h-4" /> Active SLA Breaches ({breaches.length})</h3>
          {breaches.map((b, i) => (
            <div key={i} className="mt-2 text-sm text-red-700">{b.agreementName}: expected {b.expectedFiles} files by {b.deadlineHour}:00 UTC, received {b.receivedFiles}</div>
          ))}
        </div>
      )}
      <div className="space-y-3">
        {slas.map(s => (
          <div key={s.id} className="card">
            <div className="flex items-center justify-between">
              <div><h3 className="font-semibold text-gray-900">{s.name}</h3>
                <p className="text-sm text-secondary">Delivery window: {s.expectedDeliveryStartHour}:00 — {s.expectedDeliveryEndHour}:00 UTC | Min {s.minFilesPerWindow} file(s) | Grace: {s.gracePeriodMinutes}min</p>
                <p className="text-xs text-muted mt-1">Days: {(s.expectedDays || []).join(', ')}</p></div>
              <div className="flex items-center gap-2">
                <span className={`badge ${s.totalBreaches > 0 ? 'badge-red' : 'badge-green'}`}>{s.totalBreaches} breaches</span>
                <button onClick={() => openEdit(s)} title="Edit agreement"
                  className="p-1.5 rounded hover:bg-blue-50 text-blue-500 transition-colors"><PencilSquareIcon className="w-4 h-4" /></button>
                <button onClick={() => { if (confirm('Delete this SLA agreement?')) deleteMut.mutate(s.id) }} title="Delete agreement"
                  className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors"><TrashIcon className="w-4 h-4" /></button>
              </div>
            </div>
          </div>
        ))}
        {slas.length === 0 && <div className="card text-center py-8 text-secondary">No SLA agreements configured</div>}
      </div>

      {showCreate && (
        <Modal title="New SLA Agreement" onClose={() => setShowCreate(false)}>
          {renderForm(
            e => { e.preventDefault(); createMut.mutate(form) },
            createMut.isPending, 'Create', 'Creating...'
          )}
        </Modal>
      )}

      {editingSla && (
        <Modal title="Edit SLA Agreement" onClose={() => setEditingSla(null)}>
          {renderForm(
            e => { e.preventDefault(); updateMut.mutate({ id: editingSla.id, data: form }) },
            updateMut.isPending, 'Save Changes', 'Saving...'
          )}
        </Modal>
      )}
    </div>
  )
}
