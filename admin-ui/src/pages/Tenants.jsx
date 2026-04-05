import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, BuildingOffice2Icon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function Tenants() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ slug: '', companyName: '', email: '' })

  const { data: tenants = [], isLoading } = useQuery({ queryKey: ['tenants'],
    queryFn: () => onboardingApi.get('/api/v1/tenants').then(r => r.data).catch(() => []) })

  const createMut = useMutation({
    mutationFn: (d) => onboardingApi.post('/api/v1/tenants/signup', d).then(r => r.data),
    onSuccess: (d) => { qc.invalidateQueries(['tenants']); setShowCreate(false); toast.success('Tenant created: ' + d.domain) },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Multi-Tenant Management</h1>
          <p className="text-gray-500 text-sm">SaaS tenants — each gets their own namespace</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> New Tenant</button>
      </div>

      <div className="space-y-3">
        {tenants.map(t => (
          <div key={t.id} className="card flex items-center gap-4">
            <BuildingOffice2Icon className="w-8 h-8 text-blue-500" />
            <div className="flex-1">
              <h3 className="font-semibold text-gray-900">{t.companyName}</h3>
              <p className="text-xs text-gray-500">{t.slug}.tranzfer.io — {t.contactEmail}</p>
            </div>
            <span className={`badge ${t.plan === 'TRIAL' ? 'badge-yellow' : 'badge-green'}`}>{t.plan}</span>
            <div className="text-right text-xs text-gray-500">
              <p>{t.transfersUsed || 0} transfers</p>
              {t.trialEndsAt && <p>Trial ends: {format(new Date(t.trialEndsAt), 'MMM d')}</p>}
            </div>
          </div>
        ))}
        {tenants.length === 0 && <div className="card text-center py-8 text-gray-500">No tenants yet</div>}
      </div>

      {showCreate && (
        <Modal title="Create Tenant" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div><label>Company Name</label><input value={form.companyName} onChange={e => setForm(f => ({...f, companyName: e.target.value}))} required placeholder="ACME Corporation" /></div>
            <div><label>Slug (URL-safe)</label><input value={form.slug} onChange={e => setForm(f => ({...f, slug: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '')}))} required placeholder="acme-corp" />
              <p className="text-xs text-gray-400 mt-1">{form.slug || 'slug'}.tranzfer.io</p></div>
            <div><label>Contact Email</label><input type="email" value={form.email} onChange={e => setForm(f => ({...f, email: e.target.value}))} required placeholder="admin@acme.com" /></div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary">Create Tenant</button></div>
          </form>
        </Modal>
      )}
    </div>
  )
}
