import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

export default function Sla() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', expectedDeliveryStartHour: 0, expectedDeliveryEndHour: 6, minFilesPerWindow: 1, gracePeriodMinutes: 30, expectedDays: ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'] })

  const { data: slas = [], isLoading } = useQuery({ queryKey: ['slas'], queryFn: () => configApi.get('/api/sla').then(r => r.data).catch(() => []) })
  const { data: breaches = [] } = useQuery({ queryKey: ['sla-breaches'], queryFn: () => configApi.get('/api/sla/breaches').then(r => r.data).catch(() => []), refetchInterval: 60000 })
  const createMut = useMutation({ mutationFn: (d) => configApi.post('/api/sla', d),
    onSuccess: () => { qc.invalidateQueries(['slas']); setShowCreate(false); toast.success('SLA created') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">SLA Agreements</h1>
          <p className="text-gray-500 text-sm">Partner delivery agreements and breach monitoring</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> New Agreement</button>
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
                <p className="text-sm text-gray-500">Delivery window: {s.expectedDeliveryStartHour}:00 — {s.expectedDeliveryEndHour}:00 UTC | Min {s.minFilesPerWindow} file(s) | Grace: {s.gracePeriodMinutes}min</p>
                <p className="text-xs text-gray-400 mt-1">Days: {(s.expectedDays || []).join(', ')}</p></div>
              <div className="text-right text-sm">
                <span className={`badge ${s.totalBreaches > 0 ? 'badge-red' : 'badge-green'}`}>{s.totalBreaches} breaches</span>
              </div>
            </div>
          </div>
        ))}
      </div>
      {showCreate && (
        <Modal title="New SLA Agreement" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div><label>Agreement Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="partner-acme-daily" /></div>
            <div className="grid grid-cols-3 gap-3">
              <div><label>Delivery Start (UTC hour)</label><input type="number" value={form.expectedDeliveryStartHour} onChange={e => setForm(f => ({...f, expectedDeliveryStartHour: +e.target.value}))} min={0} max={23} /></div>
              <div><label>Delivery End (UTC hour)</label><input type="number" value={form.expectedDeliveryEndHour} onChange={e => setForm(f => ({...f, expectedDeliveryEndHour: +e.target.value}))} min={0} max={23} /></div>
              <div><label>Min Files</label><input type="number" value={form.minFilesPerWindow} onChange={e => setForm(f => ({...f, minFilesPerWindow: +e.target.value}))} min={1} /></div>
            </div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary">Create</button></div>
          </form>
        </Modal>
      )}
    </div>
  )
}
