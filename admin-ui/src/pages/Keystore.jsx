import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, ArrowPathIcon, KeyIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const ksApi = axios.create({ baseURL: 'http://localhost:8093' })
const genTypes = [
  { id: 'ssh-host', label: 'SSH Host Key', fields: ['alias', 'ownerService'] },
  { id: 'ssh-user', label: 'SSH User Key', fields: ['alias', 'partnerAccount', 'keySize'] },
  { id: 'aes', label: 'AES-256 Key', fields: ['alias', 'ownerService'] },
  { id: 'tls', label: 'TLS Certificate', fields: ['alias', 'cn', 'validDays'] },
  { id: 'hmac', label: 'HMAC Secret', fields: ['alias', 'ownerService'] },
]

export default function Keystore() {
  const qc = useQueryClient()
  const [showGen, setShowGen] = useState(false)
  const [genType, setGenType] = useState('ssh-host')
  const [form, setForm] = useState({})

  const { data: keys = [], isLoading } = useQuery({ queryKey: ['keys'], queryFn: () => ksApi.get('/api/v1/keys').then(r => r.data).catch(() => []) })
  const genMut = useMutation({
    mutationFn: (data) => ksApi.post(`/api/v1/keys/generate/${genType}`, data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['keys']); setShowGen(false); setForm({}); toast.success('Key generated') },
    onError: err => toast.error(err.response?.data?.message || 'Failed')
  })
  const rotateMut = useMutation({
    mutationFn: (alias) => ksApi.post(`/api/v1/keys/${alias}/rotate`, { newAlias: alias + '-' + Date.now() }).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['keys']); toast.success('Key rotated') }
  })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Keystore Manager</h1>
          <p className="text-gray-500 text-sm">Central key & certificate management — {keys.length} active keys</p></div>
        <button className="btn-primary" onClick={() => setShowGen(true)}><PlusIcon className="w-4 h-4" /> Generate Key</button>
      </div>

      <div className="card">
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Alias</th><th className="table-header">Type</th><th className="table-header">Algorithm</th>
          <th className="table-header">Owner</th><th className="table-header">Fingerprint</th><th className="table-header">Expires</th><th className="table-header">Actions</th>
        </tr></thead><tbody>
          {keys.map(k => (
            <tr key={k.id} className="table-row">
              <td className="table-cell font-mono text-xs font-bold">{k.alias}</td>
              <td className="table-cell"><span className="badge badge-blue">{k.keyType}</span></td>
              <td className="table-cell text-xs text-gray-500">{k.algorithm}</td>
              <td className="table-cell text-xs">{k.ownerService || k.partnerAccount || '—'}</td>
              <td className="table-cell text-xs font-mono text-gray-400">{k.fingerprint?.substring(0,12)}...</td>
              <td className="table-cell text-xs">{k.expiresAt ? format(new Date(k.expiresAt), 'MMM d, yyyy') : '—'}</td>
              <td className="table-cell">
                <button onClick={() => rotateMut.mutate(k.alias)} className="text-xs text-blue-600 hover:text-blue-800"><ArrowPathIcon className="w-3.5 h-3.5 inline" /> Rotate</button>
              </td>
            </tr>
          ))}
        </tbody></table>
      </div>

      {showGen && (
        <Modal title="Generate Key" onClose={() => setShowGen(false)}>
          <form onSubmit={e => { e.preventDefault(); genMut.mutate(form) }} className="space-y-4">
            <div><label>Key Type</label>
              <select value={genType} onChange={e => { setGenType(e.target.value); setForm({}) }}>
                {genTypes.map(t => <option key={t.id} value={t.id}>{t.label}</option>)}
              </select></div>
            {genTypes.find(t => t.id === genType)?.fields.map(f => (
              <div key={f}><label>{f}</label><input value={form[f] || ''} onChange={e => setForm(p => ({...p, [f]: e.target.value}))} required={f === 'alias'} placeholder={f === 'alias' ? 'unique-key-name' : f === 'cn' ? 'mft.company.com' : f} /></div>
            ))}
            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setShowGen(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={genMut.isPending}>{genMut.isPending ? 'Generating...' : 'Generate'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
