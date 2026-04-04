import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, LockClosedIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'
import Modal from '../components/Modal'

export default function Encryption() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', algorithm: 'AES', keySize: 256 })

  const { data: keys = [], isLoading } = useQuery({ queryKey: ['enc-keys'],
    queryFn: () => configApi.get('/api/encryption-keys').then(r => r.data).catch(() => []) })
  const createMut = useMutation({ mutationFn: (d) => configApi.post('/api/encryption-keys', d),
    onSuccess: () => { qc.invalidateQueries(['enc-keys']); setShowCreate(false); toast.success('Key created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const deleteMut = useMutation({ mutationFn: (id) => configApi.delete(`/api/encryption-keys/${id}`),
    onSuccess: () => { qc.invalidateQueries(['enc-keys']); toast.success('Deleted') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Encryption Keys</h1>
          <p className="text-gray-500 text-sm">AES and PGP keys for file encryption/decryption flows</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> New Key</button>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className="card text-center"><LockClosedIcon className="w-8 h-8 text-blue-500 mx-auto mb-2" />
          <p className="text-2xl font-bold">{keys.length}</p><p className="text-sm text-gray-500">Total Keys</p></div>
        <div className="card text-center"><p className="text-2xl font-bold">{keys.filter(k => k.algorithm === 'AES').length}</p>
          <p className="text-sm text-gray-500">AES Keys</p></div>
        <div className="card text-center"><p className="text-2xl font-bold">{keys.filter(k => k.algorithm === 'PGP').length}</p>
          <p className="text-sm text-gray-500">PGP Keys</p></div>
      </div>

      <div className="card">
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Name</th><th className="table-header">Algorithm</th><th className="table-header">Status</th><th className="table-header">Actions</th>
        </tr></thead><tbody>
          {keys.map(k => (
            <tr key={k.id} className="table-row">
              <td className="table-cell font-medium">{k.name || k.id?.substring(0,8)}</td>
              <td className="table-cell"><span className="badge badge-blue">{k.algorithm}</span></td>
              <td className="table-cell"><span className={`badge ${k.active !== false ? 'badge-green' : 'badge-red'}`}>{k.active !== false ? 'Active' : 'Inactive'}</span></td>
              <td className="table-cell"><button onClick={() => { if(confirm('Delete key?')) deleteMut.mutate(k.id) }} className="p-1 text-red-500 hover:text-red-700"><TrashIcon className="w-4 h-4" /></button></td>
            </tr>
          ))}
        </tbody></table>
      </div>

      {showCreate && (
        <Modal title="Create Encryption Key" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="partner-acme-aes" /></div>
            <div className="grid grid-cols-2 gap-3">
              <div><label>Algorithm</label><select value={form.algorithm} onChange={e => setForm(f => ({...f, algorithm: e.target.value}))}><option>AES</option><option>PGP</option></select></div>
              <div><label>Key Size</label><select value={form.keySize} onChange={e => setForm(f => ({...f, keySize: +e.target.value}))}><option value={128}>128-bit</option><option value={256}>256-bit</option></select></div>
            </div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary">Create</button></div>
          </form>
        </Modal>
      )}
    </div>
  )
}
