import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, PencilSquareIcon, TrashIcon, BuildingOffice2Icon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Tenants() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingTenant, setEditingTenant] = useState(null)
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [form, setForm] = useState({ slug: '', companyName: '', email: '' })

  const { data: tenants = [], isLoading, isError, refetch } = useQuery({ queryKey: ['tenants'],
    queryFn: () => onboardingApi.get('/api/v1/tenants').then(r => r.data),
    retry: 1 })

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
      {isError && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">Failed to load data — service may be unavailable</span>
          </div>
          <button onClick={() => refetch()} className="text-xs text-red-400 hover:text-red-300 underline">Retry</button>
        </div>
      )}
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-primary">Multi-Tenant Management</h1>
          <p className="text-secondary text-sm">SaaS tenants — each gets their own namespace</p></div>
        <button className="btn-primary" onClick={openCreate}><PlusIcon className="w-4 h-4" /> New Tenant</button>
      </div>

      <div className="space-y-3">
        {tenants.map(t => (
          <div key={t.id} className="card flex items-center gap-4">
            <BuildingOffice2Icon className="w-8 h-8 text-blue-500" />
            <div className="flex-1">
              <h3 className="font-semibold text-primary">{t.companyName}</h3>
              <p className="text-xs text-secondary">{t.slug}.tranzfer.io — {t.contactEmail}</p>
            </div>
            <span className={`badge ${t.plan === 'TRIAL' ? 'badge-yellow' : 'badge-green'}`}>{t.plan}</span>
            <div className="text-right text-xs text-secondary">
              <p>{t.transfersUsed || 0} transfers</p>
              {t.trialEndsAt && <p>Trial ends: {format(new Date(t.trialEndsAt), 'MMM d')}</p>}
            </div>
            <button onClick={() => openEdit(t)} title="Edit tenant" aria-label="Edit tenant"
              className="p-1.5 rounded hover:bg-[rgba(100,140,255,0.1)] text-blue-500 transition-colors"><PencilSquareIcon className="w-4 h-4" /></button>
            <button onClick={() => setConfirmDelete(t)} title="Delete tenant" aria-label="Delete tenant"
              className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors"><TrashIcon className="w-4 h-4" /></button>
          </div>
        ))}
        {tenants.length === 0 && (
          <div className="card flex flex-col items-center justify-center py-16 px-4 text-center">
            <div
              className="w-14 h-14 rounded-full flex items-center justify-center mb-3"
              style={{ background: 'rgba(79, 70, 229, 0.1)' }}
            >
              <BuildingOffice2Icon className="w-7 h-7" style={{ color: 'rgb(var(--accent, 79 70 229))' }} />
            </div>
            <h3 className="text-base font-semibold mb-1" style={{ color: 'rgb(var(--tx-primary))' }}>
              No tenants yet
            </h3>
            <p className="text-xs max-w-md mb-4" style={{ color: 'rgb(148, 163, 184)' }}>
              Tenants partition the platform for multi-customer deployments.
            </p>
            <button
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
              style={{ background: 'rgb(var(--accent, 79 70 229))', color: '#fff' }}
              onClick={openCreate}
            >
              <PlusIcon className="w-3.5 h-3.5" />
              Create Tenant
            </button>
          </div>
        )}
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
      <ConfirmDialog
        open={!!confirmDelete}
        variant="danger"
        title="Delete tenant?"
        message={confirmDelete ? `Are you sure you want to delete tenant "${confirmDelete.companyName}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteMut.isPending}
        onConfirm={() => { deleteMut.mutate(confirmDelete.id); setConfirmDelete(null) }}
        onCancel={() => setConfirmDelete(null)}
      />
    </div>
  )
}
