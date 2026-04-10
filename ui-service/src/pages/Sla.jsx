import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi, onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, PencilSquareIcon, TrashIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

const emptyForm = {
  name: '',
  partnerId: '',
  partnerName: '',
  tier: 'SILVER',
  protocol: 'ANY',
  serverId: '',
  expectedDeliveryStartHour: 0,
  expectedDeliveryEndHour: 6,
  minFilesPerWindow: 1,
  gracePeriodMinutes: 30,
  maxLatencySeconds: 300,
  expectedDays: ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'],
  active: true,
}
const ALL_DAYS = ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY']
const TIERS = ['PLATINUM', 'GOLD', 'SILVER', 'BRONZE']
const PROTOCOLS = ['ANY', 'SFTP', 'FTP', 'AS2', 'HTTPS']
const TIER_COLORS = { PLATINUM: 'bg-purple-100 text-purple-800 border-purple-200', GOLD: 'bg-yellow-100 text-yellow-800 border-yellow-200', SILVER: 'bg-gray-100 text-gray-700 border-gray-300', BRONZE: 'bg-orange-100 text-orange-800 border-orange-200' }

export default function Sla() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingSla, setEditingSla] = useState(null)
  const [form, setForm] = useState({ ...emptyForm })

  const { data: slas = [], isLoading } = useQuery({ queryKey: ['slas'], queryFn: () => configApi.get('/api/sla').then(r => r.data).catch(() => []) })
  const { data: breaches = [] } = useQuery({ queryKey: ['sla-breaches'], queryFn: () => configApi.get('/api/sla/breaches').then(r => r.data).catch(() => []), refetchInterval: 60000 })

  const { data: partners = [] } = useQuery({
    queryKey: ['partners-list'],
    queryFn: () => onboardingApi.get('/api/partners').then(r => r.data).catch(() => [])
  })

  const { data: servers = [] } = useQuery({
    queryKey: ['servers-list'],
    queryFn: () => onboardingApi.get('/api/servers?activeOnly=true').then(r => r.data).catch(() => [])
  })

  const filteredServers = useMemo(() => {
    if (form.protocol === 'ANY') return servers
    return servers.filter(s => (s.protocol || '').toUpperCase() === form.protocol)
  }, [servers, form.protocol])

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
      partnerId: s.partnerId || '',
      partnerName: s.partnerName || '',
      tier: s.tier || 'SILVER',
      protocol: s.protocol || 'ANY',
      serverId: s.serverId || '',
      expectedDeliveryStartHour: s.expectedDeliveryStartHour ?? 0,
      expectedDeliveryEndHour: s.expectedDeliveryEndHour ?? 6,
      minFilesPerWindow: s.minFilesPerWindow ?? 1,
      gracePeriodMinutes: s.gracePeriodMinutes ?? 30,
      maxLatencySeconds: s.maxLatencySeconds ?? 300,
      expectedDays: s.expectedDays || ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'],
      active: s.active !== false,
    })
    setEditingSla(s)
  }

  const openCreate = () => {
    setForm({ ...emptyForm, expectedDays: [...emptyForm.expectedDays] })
    setShowCreate(true)
  }

  const handlePartnerChange = (partnerId) => {
    const partner = partners.find(p => String(p.id) === String(partnerId))
    setForm(f => ({
      ...f,
      partnerId: partnerId,
      partnerName: partner?.name || '',
      name: partner ? `${partner.name.toLowerCase().replace(/\s+/g, '-')}-sla` : f.name,
    }))
  }

  const handleProtocolChange = (protocol) => {
    setForm(f => ({ ...f, protocol, serverId: '' }))
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
      {/* Partner dropdown */}
      <div>
        <label className="block text-sm font-medium text-primary mb-1">Partner *</label>
        <select value={form.partnerId} onChange={e => handlePartnerChange(e.target.value)} required>
          <option value="">Select a partner...</option>
          {partners.map(p => (
            <option key={p.id} value={p.id}>{p.name}{p.industry ? ` (${p.industry})` : ''}</option>
          ))}
        </select>
      </div>

      {/* Agreement Name (auto-generated, editable) */}
      <div>
        <label className="block text-sm font-medium text-primary mb-1">Agreement Name *</label>
        <input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="partner-name-sla" />
      </div>

      {/* Tier + Protocol row */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Tier</label>
          <select value={form.tier} onChange={e => setForm(f => ({...f, tier: e.target.value}))}>
            {TIERS.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Protocol</label>
          <select value={form.protocol} onChange={e => handleProtocolChange(e.target.value)}>
            {PROTOCOLS.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </div>
      </div>

      {/* Server dropdown (filtered by protocol) */}
      <div>
        <label className="block text-sm font-medium text-primary mb-1">Server (optional)</label>
        <select value={form.serverId} onChange={e => setForm(f => ({...f, serverId: e.target.value}))}>
          <option value="">Any server</option>
          {filteredServers.map(s => (
            <option key={s.id} value={s.id}>{s.name}{s.protocol ? ` [${s.protocol}]` : ''}</option>
          ))}
        </select>
        {form.protocol !== 'ANY' && filteredServers.length === 0 && (
          <p className="text-xs text-amber-500 mt-1">No {form.protocol} servers available.</p>
        )}
      </div>

      {/* Delivery window + min files */}
      <div className="grid grid-cols-3 gap-3">
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Delivery Start (UTC hour)</label>
          <input type="number" value={form.expectedDeliveryStartHour} onChange={e => setForm(f => ({...f, expectedDeliveryStartHour: +e.target.value}))} min={0} max={23} />
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Delivery End (UTC hour)</label>
          <input type="number" value={form.expectedDeliveryEndHour} onChange={e => setForm(f => ({...f, expectedDeliveryEndHour: +e.target.value}))} min={0} max={23} />
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Min Files</label>
          <input type="number" value={form.minFilesPerWindow} onChange={e => setForm(f => ({...f, minFilesPerWindow: +e.target.value}))} min={1} />
        </div>
      </div>

      {/* Grace period + max latency */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Grace Period (minutes)</label>
          <input type="number" value={form.gracePeriodMinutes} onChange={e => setForm(f => ({...f, gracePeriodMinutes: +e.target.value}))} min={0} />
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Max Latency (seconds)</label>
          <input type="number" value={form.maxLatencySeconds} onChange={e => setForm(f => ({...f, maxLatencySeconds: +e.target.value}))} min={0} placeholder="300" />
        </div>
      </div>

      {/* Expected days */}
      <div>
        <label className="block text-sm font-medium text-primary mb-1">Expected Days</label>
        <div className="flex flex-wrap gap-2 mt-2">
          {ALL_DAYS.map(day => (
            <button key={day} type="button" onClick={() => toggleDay(day)}
              className={`px-3 py-1 rounded-lg text-xs font-medium transition-colors ${form.expectedDays.includes(day) ? 'bg-blue-100 text-blue-800 border border-blue-200' : 'bg-gray-100 text-secondary border border-border'}`}>
              {day.substring(0, 3)}
            </button>
          ))}
        </div>
      </div>

      {/* Active toggle */}
      <div className="flex items-center gap-2">
        <label className="flex items-center gap-2 cursor-pointer select-none">
          <div className={`w-9 h-5 rounded-full relative transition-colors ${form.active ? 'bg-indigo-600' : 'bg-gray-300'}`}
            onClick={() => setForm(f => ({...f, active: !f.active}))}>
            <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${form.active ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </div>
          <span className="text-sm text-primary">Active</span>
        </label>
      </div>

      <div className="flex gap-3 justify-end pt-3 border-t">
        <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); setEditingSla(null) }}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{isPending ? pendingLabel : submitLabel}</button>
      </div>
    </form>
  )

  const tierBadge = (tier) => {
    const cls = TIER_COLORS[tier] || TIER_COLORS.SILVER
    return <span className={`text-xs px-2 py-0.5 rounded-full border font-medium ${cls}`}>{tier}</span>
  }

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
          <div key={s.id} className="card cursor-pointer hover:bg-[rgba(100,140,255,0.06)] transition-colors" onClick={() => openEdit(s)}>
            <div className="flex items-center justify-between">
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <h3 className="font-semibold text-gray-900">{s.partnerName || s.name}</h3>
                  {tierBadge(s.tier || 'SILVER')}
                  {s.protocol && s.protocol !== 'ANY' && <span className="badge badge-blue">{s.protocol}</span>}
                  {s.serverName && <span className="text-xs text-secondary">@ {s.serverName}</span>}
                  {s.active === false && <span className="badge badge-gray">Inactive</span>}
                </div>
                <p className="text-sm text-secondary">{s.name} | Window: {s.expectedDeliveryStartHour}:00 - {s.expectedDeliveryEndHour}:00 UTC | Min {s.minFilesPerWindow} file(s) | Grace: {s.gracePeriodMinutes}min</p>
                <p className="text-xs text-muted mt-1">Days: {(s.expectedDays || []).join(', ')}{s.maxLatencySeconds ? ` | Max latency: ${s.maxLatencySeconds}s` : ''}</p>
              </div>
              <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
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
