import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilSquareIcon, MagnifyingGlassIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

const SSH_CIPHERS = ['aes256-ctr','aes128-ctr','aes256-gcm@openssh.com','aes128-gcm@openssh.com','chacha20-poly1305@openssh.com']
const SSH_MACS = ['hmac-sha2-256','hmac-sha2-512','hmac-sha2-256-etm@openssh.com','hmac-sha2-512-etm@openssh.com']
const KEX_ALGOS = ['ecdh-sha2-nistp256','ecdh-sha2-nistp384','diffie-hellman-group14-sha256','diffie-hellman-group16-sha512','curve25519-sha256']
const HOST_KEY_ALGOS = ['rsa-sha2-256','rsa-sha2-512','ecdsa-sha2-nistp256','ssh-ed25519']

const defaultForm = {
  name: '', description: '', type: 'SSH',
  sshCiphers: [...SSH_CIPHERS], sshMacs: [...SSH_MACS], kexAlgorithms: [...KEX_ALGOS], hostKeyAlgorithms: [...HOST_KEY_ALGOS],
  tlsMinVersion: 'TLS_1_2', tlsCiphers: [], clientAuthRequired: false
}

export default function SecurityProfiles() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingProfile, setEditingProfile] = useState(null)
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [search, setSearch] = useState('')
  const [form, setForm] = useState({ ...defaultForm })

  const { data: profiles = [], isLoading, isError, refetch } = useQuery({
    queryKey: ['security-profiles'],
    queryFn: () => configApi.get('/api/security-profiles').then(r => r.data),
    retry: 1
  })

  const createMut = useMutation({
    mutationFn: (data) => configApi.post('/api/security-profiles', data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['security-profiles']); setShowCreate(false); setForm({ ...defaultForm }); toast.success('Security profile created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed to create profile — check your input and try again')
  })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => configApi.put(`/api/security-profiles/${id}`, data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['security-profiles']); setEditingProfile(null); setForm({ ...defaultForm }); toast.success('Profile updated') },
    onError: err => toast.error(err.response?.data?.error || 'Failed to update profile — check your input and try again')
  })
  const deleteMut = useMutation({
    mutationFn: (id) => configApi.delete(`/api/security-profiles/${id}`),
    onSuccess: () => { qc.invalidateQueries(['security-profiles']); toast.success('Profile deleted') },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to delete profile — the item may be in use')
  })

  const toggleItem = (field, item) => {
    setForm(f => ({
      ...f,
      [field]: f[field].includes(item) ? f[field].filter(i => i !== item) : [...f[field], item]
    }))
  }

  const openEdit = (p) => {
    setForm({
      name: p.name || '',
      description: p.description || '',
      type: p.type || 'SSH',
      sshCiphers: p.sshCiphers || [...SSH_CIPHERS],
      sshMacs: p.sshMacs || [...SSH_MACS],
      kexAlgorithms: p.kexAlgorithms || [...KEX_ALGOS],
      hostKeyAlgorithms: p.hostKeyAlgorithms || [...HOST_KEY_ALGOS],
      tlsMinVersion: p.tlsMinVersion || 'TLS_1_2',
      tlsCiphers: p.tlsCiphers || [],
      clientAuthRequired: p.clientAuthRequired || false,
    })
    setEditingProfile(p)
  }

  const openCreate = () => {
    setForm({ ...defaultForm, sshCiphers: [...SSH_CIPHERS], sshMacs: [...SSH_MACS], kexAlgorithms: [...KEX_ALGOS], hostKeyAlgorithms: [...HOST_KEY_ALGOS] })
    setShowCreate(true)
  }

  if (isLoading) return <LoadingSpinner />

  const renderForm = (onSubmit, isPending, submitLabel, pendingLabel) => (
    <form onSubmit={onSubmit} className="space-y-5">
      <div className="grid grid-cols-2 gap-4">
        <div><label>Profile Name</label>
          <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} required placeholder="e.g. FIPS-140-2" /></div>
        <div><label>Type</label>
          <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))}>
            <option>SSH</option><option>TLS</option>
          </select>
        </div>
      </div>
      <div><label>Description</label>
        <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} placeholder="Describe when to use this profile" /></div>

      {form.type === 'SSH' && (
        <>
          <div>
            <label>Allowed Ciphers</label>
            <div className="mt-2 grid grid-cols-1 gap-1">
              {SSH_CIPHERS.map(c => (
                <label key={c} className="flex items-center gap-2 cursor-pointer mb-0">
                  <input type="checkbox" className="w-auto rounded" checked={form.sshCiphers.includes(c)} onChange={() => toggleItem('sshCiphers', c)} />
                  <span className="font-mono text-xs">{c}</span>
                </label>
              ))}
            </div>
          </div>
          <div>
            <label>Allowed MACs</label>
            <div className="mt-2 grid grid-cols-1 gap-1">
              {SSH_MACS.map(m => (
                <label key={m} className="flex items-center gap-2 cursor-pointer mb-0">
                  <input type="checkbox" className="w-auto rounded" checked={form.sshMacs.includes(m)} onChange={() => toggleItem('sshMacs', m)} />
                  <span className="font-mono text-xs">{m}</span>
                </label>
              ))}
            </div>
          </div>
          <div>
            <label>Key Exchange Algorithms</label>
            <div className="mt-2 grid grid-cols-1 gap-1">
              {KEX_ALGOS.map(k => (
                <label key={k} className="flex items-center gap-2 cursor-pointer mb-0">
                  <input type="checkbox" className="w-auto rounded" checked={form.kexAlgorithms.includes(k)} onChange={() => toggleItem('kexAlgorithms', k)} />
                  <span className="font-mono text-xs">{k}</span>
                </label>
              ))}
            </div>
          </div>
        </>
      )}

      {form.type === 'TLS' && (
        <div className="grid grid-cols-2 gap-4">
          <div><label>Min TLS Version</label>
            <select value={form.tlsMinVersion} onChange={e => setForm(f => ({ ...f, tlsMinVersion: e.target.value }))}>
              <option>TLS_1_2</option><option>TLS_1_3</option>
            </select>
          </div>
          <div><label>Require Client Auth</label>
            <label className="flex items-center gap-2 mt-2 mb-0">
              <input type="checkbox" className="w-auto" checked={form.clientAuthRequired} onChange={e => setForm(f => ({ ...f, clientAuthRequired: e.target.checked }))} />
              <span className="text-sm">mTLS (client certificate)</span>
            </label>
          </div>
        </div>
      )}

      <div className="flex gap-3 justify-end pt-2">
        <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); setEditingProfile(null) }}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{isPending ? pendingLabel : submitLabel}</button>
      </div>
    </form>
  )

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
        <div>
          <h1 className="text-2xl font-bold text-primary">Security Profiles</h1>
          <p className="text-secondary text-sm">Configure cipher suites, MACs, and key exchange algorithms</p>
        </div>
        <div className="flex items-center gap-3">
          <div className="relative">
            <MagnifyingGlassIcon className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search profiles..." className="pl-9 w-64" />
          </div>
          <button className="btn-primary" onClick={openCreate}>
            <PlusIcon className="w-4 h-4" /> New Profile
          </button>
        </div>
      </div>

      {profiles.length === 0 ? (
        <div className="card">
          <EmptyState title="No security profiles" description="Create a security profile to enforce cipher/MAC policies on your SFTP/FTP servers and partner connections."
            action={<button className="btn-primary" onClick={openCreate}><PlusIcon className="w-4 h-4" />Create Profile</button>} />
        </div>
      ) : (
        <div className="grid gap-4">
          {(profiles || []).filter(p => {
            if (!search) return true
            const q = search.toLowerCase()
            return p.name?.toLowerCase().includes(q)
          }).map(p => (
            <div key={p.id} className="card">
              <div className="flex items-start justify-between">
                <div>
                  <h3 className="font-semibold text-primary">{p.name}</h3>
                  <p className="text-sm text-secondary mt-0.5">{p.description}</p>
                  <span className={`badge mt-2 ${p.type === 'SSH' ? 'badge-blue' : 'badge-purple'}`}>{p.type}</span>
                </div>
                <div className="flex items-center gap-1">
                  <button onClick={() => openEdit(p)} title="Edit profile" aria-label="Edit profile"
                    className="p-1.5 rounded hover:bg-[rgba(100,140,255,0.1)] text-blue-500 transition-colors">
                    <PencilSquareIcon className="w-4 h-4" />
                  </button>
                  <button onClick={() => setConfirmDelete(p)} title="Delete profile" aria-label="Delete profile"
                    className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors">
                    <TrashIcon className="w-4 h-4" />
                  </button>
                </div>
              </div>
              {p.type === 'SSH' && (
                <div className="mt-4 grid grid-cols-2 gap-4 text-xs">
                  <div><p className="font-semibold text-secondary mb-1">Ciphers</p>
                    {(p.sshCiphers || []).map(c => <div key={c} className="text-secondary font-mono">{c}</div>)}</div>
                  <div><p className="font-semibold text-secondary mb-1">MACs</p>
                    {(p.sshMacs || []).map(m => <div key={m} className="text-secondary font-mono">{m}</div>)}</div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {showCreate && (
        <Modal title="New Security Profile" size="lg" onClose={() => setShowCreate(false)}>
          {renderForm(
            e => { e.preventDefault(); createMut.mutate(form) },
            createMut.isPending, 'Save Profile', 'Saving...'
          )}
        </Modal>
      )}

      {editingProfile && (
        <Modal title="Edit Security Profile" size="lg" onClose={() => setEditingProfile(null)}>
          {renderForm(
            e => { e.preventDefault(); updateMut.mutate({ id: editingProfile.id, data: form }) },
            updateMut.isPending, 'Save Changes', 'Saving...'
          )}
        </Modal>
      )}

      {confirmDelete && (
        <Modal title="Confirm Delete" onClose={() => setConfirmDelete(null)}>
          <p className="text-secondary mb-4">Are you sure you want to delete profile <strong>{confirmDelete.name}</strong>? This action cannot be undone.</p>
          <div className="flex gap-3 justify-end">
            <button className="btn-secondary" onClick={() => setConfirmDelete(null)}>Cancel</button>
            <button className="btn-primary bg-red-600 hover:bg-red-700" onClick={() => { deleteMut.mutate(confirmDelete.id); setConfirmDelete(null) }}>Delete</button>
          </div>
        </Modal>
      )}
    </div>
  )
}
