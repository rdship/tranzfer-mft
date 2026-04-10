import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, PencilSquareIcon, TrashIcon, BuildingOffice2Icon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Tenants() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingTenant, setEditingTenant] = useState(null)
  const [form, setForm] = useState({ slug: '', companyName: '', email: '' })

  const { data: tenants = [], isLoading } = useQuery({ queryKey: ['tenants'],
    queryFn: () => onboardingApi.get('/api/v1/tenants').then(r => r.data).catch(() => []) })

  const createMut = useMutation({
    mutationFn: (d) => onboardingApi.post('/api/v1/tenants/signup', d).then(r => r.data),
    onSuccess: (d) => { qc.invalidateQueries(['tenants']); setShowCreate(false); setForm({ slug: '', companyName: '', email: '' }); toast.success('Tenant created: ' + d.domain) },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => onboardingApi.put(`/api/v1/tenants/${id}`, data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['tenants']); setEditingTenant(null); setForm({ slug: '', companyName: '', email: '' }); toast.success('Tenant updated') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })
  const deleteMut = useMutation({
    mutationFn: (id) => onboardingApi.delete(`/api/v1/tenants/${id}`),
    onSuccess: () => { qc.invalidateQueries(['tenants']); toast.success('Tenant deleted') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })

  const openEdit = (t) => {
    setForm({
      slug: t.slug || '',
      companyName: t.companyName || '',
      email: t.contactEmail || '',
    })
    setEditingTenant(t)
  }

  const openCreate = () => {
    setForm({ slug: '', companyName: '', email: '' })
    setShowCreate(true)
  }

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Multi-Tenant Management</h1>
          <p className="text-secondary text-sm">SaaS tenants — each gets their own namespace</p></div>
        <button className="btn-primary" onClick={openCreate}><PlusIcon className="w-4 h-4" /> New Tenant</button>
      </div>

      <div className="space-y-3">
        {tenants.map(t => (
          <div key={t.id} className="card flex items-center gap-4">
            <BuildingOffice2Icon className="w-8 h-8 text-blue-500" />
            <div className="flex-1">
              <h3 className="font-semibold text-gray-900">{t.companyName}</h3>
              <p className="text-xs text-secondary">{t.slug}.tranzfer.io — {t.contactEmail}</p>
            </div>
            <span className={`badge ${t.plan === 'TRIAL' ? 'badge-yellow' : 'badge-green'}`}>{t.plan}</span>
            <div className="text-right text-xs text-secondary">
              <p>{t.transfersUsed || 0} transfers</p>
              {t.trialEndsAt && <p>Trial ends: {format(new Date(t.trialEndsAt), 'MMM d')}</p>}
            </div>
            <button onClick={() => openEdit(t)} title="Edit tenant"
              className="p-1.5 rounded hover:bg-blue-50 text-blue-500 transition-colors"><PencilSquareIcon className="w-4 h-4" /></button>
            <button onClick={() => { if (confirm('Delete this tenant? This cannot be undone.')) deleteMut.mutate(t.id) }} title="Delete tenant"
              className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors"><TrashIcon className="w-4 h-4" /></button>
          </div>
        ))}
        {tenants.length === 0 && <div className="card text-center py-8 text-secondary">No tenants yet</div>}
      </div>

      {showCreate && (
        <Modal title="Create Tenant" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div><label>Company Name</label><input value={form.companyName} onChange={e => setForm(f => ({...f, companyName: e.target.value}))} required placeholder="ACME Corporation" /></div>
            <div><label>Slug (URL-safe)</label><input value={form.slug} onChange={e => setForm(f => ({...f, slug: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '')}))} required placeholder="acme-corp" />
              <p className="text-xs text-muted mt-1">{form.slug || 'slug'}.tranzfer.io</p></div>
            <div><label>Contact Email</label><input type="email" value={form.email} onChange={e => setForm(f => ({...f, email: e.target.value}))} required placeholder="admin@acme.com" /></div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>{createMut.isPending ? 'Creating...' : 'Create Tenant'}</button></div>
          </form>
        </Modal>
      )}

      {editingTenant && (
        <Modal title="Edit Tenant" onClose={() => setEditingTenant(null)}>
          <form onSubmit={e => { e.preventDefault(); updateMut.mutate({ id: editingTenant.id, data: form }) }} className="space-y-4">
            <div><label>Company Name</label><input value={form.companyName} onChange={e => setForm(f => ({...f, companyName: e.target.value}))} required placeholder="ACME Corporation" /></div>
            <div><label>Slug (URL-safe)</label><input value={form.slug} onChange={e => setForm(f => ({...f, slug: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '')}))} required placeholder="acme-corp" />
              <p className="text-xs text-muted mt-1">{form.slug || 'slug'}.tranzfer.io</p></div>
            <div><label>Contact Email</label><input type="email" value={form.email} onChange={e => setForm(f => ({...f, email: e.target.value}))} required placeholder="admin@acme.com" /></div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setEditingTenant(null)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={updateMut.isPending}>{updateMut.isPending ? 'Saving...' : 'Save Changes'}</button></div>
          </form>
        </Modal>
      )}
    </div>
  )
}
