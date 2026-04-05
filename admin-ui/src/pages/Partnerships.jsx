import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getPartnerships, createPartnership, deletePartnership, togglePartnership } from '../api/config'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, ArrowsRightLeftIcon, CheckCircleIcon, XCircleIcon, SignalIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'

const EMPTY_FORM = {
  partnerName: '', partnerAs2Id: '', ourAs2Id: '', endpointUrl: '', protocol: 'AS2',
  signingAlgorithm: 'SHA256', encryptionAlgorithm: 'AES256',
  mdnRequired: true, mdnAsync: false, mdnUrl: '', compressionEnabled: false,
  partnerCertificate: ''
}

export default function Partnerships() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState(EMPTY_FORM)
  const [filter, setFilter] = useState('ALL')

  const { data: partnerships = [], isLoading } = useQuery({ queryKey: ['partnerships'], queryFn: getPartnerships })
  const createMut = useMutation({
    mutationFn: createPartnership,
    onSuccess: () => { qc.invalidateQueries(['partnerships']); setShowCreate(false); setForm(EMPTY_FORM); toast.success('Partnership created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed to create partnership')
  })
  const deleteMut = useMutation({
    mutationFn: deletePartnership,
    onSuccess: () => { qc.invalidateQueries(['partnerships']); toast.success('Partnership deactivated') }
  })
  const toggleMut = useMutation({
    mutationFn: togglePartnership,
    onSuccess: () => { qc.invalidateQueries(['partnerships']); toast.success('Partnership toggled') }
  })

  const filtered = filter === 'ALL' ? partnerships : partnerships.filter(p => p.protocol === filter)
  const as2Count = partnerships.filter(p => p.protocol === 'AS2').length
  const as4Count = partnerships.filter(p => p.protocol === 'AS4').length

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">AS2/AS4 Partnerships</h1>
          <p className="text-gray-500 text-sm">Manage B2B trading partner configurations for AS2 (RFC 4130) and AS4 (OASIS ebMS3)</p>
        </div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}>
          <PlusIcon className="w-4 h-4" /> Add Partnership
        </button>
      </div>

      {/* Protocol Summary Cards */}
      <div className="grid grid-cols-3 gap-4">
        <button onClick={() => setFilter('ALL')}
          className={`card flex items-center gap-4 cursor-pointer transition-all ${filter === 'ALL' ? 'ring-2 ring-blue-500' : 'hover:shadow-md'}`}>
          <div className="w-10 h-10 bg-blue-100 rounded-xl flex items-center justify-center">
            <ArrowsRightLeftIcon className="w-5 h-5 text-blue-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{partnerships.length}</p>
            <p className="text-xs text-gray-500">Total Partnerships</p>
          </div>
        </button>
        <button onClick={() => setFilter('AS2')}
          className={`card flex items-center gap-4 cursor-pointer transition-all ${filter === 'AS2' ? 'ring-2 ring-green-500' : 'hover:shadow-md'}`}>
          <div className="w-10 h-10 bg-green-100 rounded-xl flex items-center justify-center">
            <SignalIcon className="w-5 h-5 text-green-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{as2Count}</p>
            <p className="text-xs text-gray-500">AS2 Partners</p>
          </div>
        </button>
        <button onClick={() => setFilter('AS4')}
          className={`card flex items-center gap-4 cursor-pointer transition-all ${filter === 'AS4' ? 'ring-2 ring-purple-500' : 'hover:shadow-md'}`}>
          <div className="w-10 h-10 bg-purple-100 rounded-xl flex items-center justify-center">
            <SignalIcon className="w-5 h-5 text-purple-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{as4Count}</p>
            <p className="text-xs text-gray-500">AS4 Partners</p>
          </div>
        </button>
      </div>

      {/* Partnership List */}
      {filtered.length === 0 ? (
        <div className="card">
          <EmptyState
            title={filter === 'ALL' ? 'No partnerships configured' : `No ${filter} partnerships`}
            description="Add trading partners to exchange files via AS2/AS4 protocol. Partners can send files inbound and receive files outbound."
            action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> Add Partnership</button>}
          />
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map(p => (
            <div key={p.id} className="card flex items-center gap-4">
              <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${p.active ? 'bg-green-100' : 'bg-gray-100'}`}>
                <ArrowsRightLeftIcon className={`w-5 h-5 ${p.active ? 'text-green-600' : 'text-gray-400'}`} />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <h3 className="font-semibold text-gray-900 truncate">{p.partnerName}</h3>
                  <span className={`badge ${p.protocol === 'AS2' ? 'badge-green' : 'badge-blue'}`}>{p.protocol}</span>
                </div>
                <p className="text-xs text-gray-500 font-mono truncate mt-0.5">
                  {p.partnerAs2Id} &harr; {p.ourAs2Id}
                </p>
                <p className="text-xs text-gray-400 truncate">{p.endpointUrl}</p>
              </div>
              <div className="flex items-center gap-2 text-xs text-gray-500 flex-shrink-0">
                {p.mdnRequired && <span className="badge badge-gray">MDN</span>}
                {p.mdnAsync && <span className="badge badge-yellow">Async</span>}
                <span className="badge badge-gray">{p.signingAlgorithm}</span>
                <span className="badge badge-gray">{p.encryptionAlgorithm}</span>
              </div>
              <div className="flex items-center gap-1 flex-shrink-0">
                <button onClick={() => toggleMut.mutate(p.id)}
                  className={`p-1.5 rounded transition-colors ${p.active ? 'hover:bg-yellow-50 text-green-500' : 'hover:bg-green-50 text-gray-400'}`}
                  title={p.active ? 'Deactivate' : 'Activate'}>
                  {p.active ? <CheckCircleIcon className="w-5 h-5" /> : <XCircleIcon className="w-5 h-5" />}
                </button>
                <button onClick={() => { if(confirm(`Deactivate partnership "${p.partnerName}"?`)) deleteMut.mutate(p.id) }}
                  className="p-1.5 rounded hover:bg-red-50 text-red-500 transition-colors">
                  <TrashIcon className="w-4 h-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Create Partnership Modal */}
      {showCreate && (
        <Modal title="Add Trading Partnership" onClose={() => setShowCreate(false)} size="lg">
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-5">
            {/* Partner Identity */}
            <div>
              <h4 className="text-sm font-semibold text-gray-900 mb-3">Partner Identity</h4>
              <div className="grid grid-cols-2 gap-3">
                <div><label>Partner Name</label><input value={form.partnerName} onChange={e => setForm(f => ({...f, partnerName: e.target.value}))} required placeholder="Acme Corp" /></div>
                <div><label>Protocol</label>
                  <select value={form.protocol} onChange={e => setForm(f => ({...f, protocol: e.target.value}))}>
                    <option value="AS2">AS2 (RFC 4130)</option>
                    <option value="AS4">AS4 (OASIS ebMS3)</option>
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3 mt-3">
                <div><label>Partner AS2 ID</label><input value={form.partnerAs2Id} onChange={e => setForm(f => ({...f, partnerAs2Id: e.target.value}))} required placeholder="ACME-AS2-ID" /></div>
                <div><label>Our AS2 ID</label><input value={form.ourAs2Id} onChange={e => setForm(f => ({...f, ourAs2Id: e.target.value}))} required placeholder="TRANZFER-AS2-ID" /></div>
              </div>
            </div>

            {/* Connection */}
            <div>
              <h4 className="text-sm font-semibold text-gray-900 mb-3">Connection</h4>
              <div><label>Endpoint URL</label><input value={form.endpointUrl} onChange={e => setForm(f => ({...f, endpointUrl: e.target.value}))} required placeholder="https://partner.com/as2/receive" /></div>
            </div>

            {/* Security */}
            <div>
              <h4 className="text-sm font-semibold text-gray-900 mb-3">Security</h4>
              <div className="grid grid-cols-2 gap-3">
                <div><label>Signing Algorithm</label>
                  <select value={form.signingAlgorithm} onChange={e => setForm(f => ({...f, signingAlgorithm: e.target.value}))}>
                    <option>SHA1</option><option>SHA256</option><option>SHA384</option><option>SHA512</option>
                  </select>
                </div>
                <div><label>Encryption Algorithm</label>
                  <select value={form.encryptionAlgorithm} onChange={e => setForm(f => ({...f, encryptionAlgorithm: e.target.value}))}>
                    <option>3DES</option><option>AES128</option><option>AES192</option><option>AES256</option>
                  </select>
                </div>
              </div>
              <div className="mt-3">
                <label>Partner Certificate (PEM)</label>
                <textarea rows={3} value={form.partnerCertificate} onChange={e => setForm(f => ({...f, partnerCertificate: e.target.value}))}
                  placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
                  className="font-mono text-xs" />
              </div>
            </div>

            {/* MDN Settings */}
            <div>
              <h4 className="text-sm font-semibold text-gray-900 mb-3">MDN (Receipt) Settings</h4>
              <div className="grid grid-cols-3 gap-3">
                <div className="flex items-center gap-2">
                  <input type="checkbox" checked={form.mdnRequired} onChange={e => setForm(f => ({...f, mdnRequired: e.target.checked}))}
                    className="w-4 h-4 rounded border-gray-300 text-blue-600" />
                  <label className="mb-0">Require MDN</label>
                </div>
                <div className="flex items-center gap-2">
                  <input type="checkbox" checked={form.mdnAsync} onChange={e => setForm(f => ({...f, mdnAsync: e.target.checked}))}
                    className="w-4 h-4 rounded border-gray-300 text-blue-600" />
                  <label className="mb-0">Async MDN</label>
                </div>
                <div className="flex items-center gap-2">
                  <input type="checkbox" checked={form.compressionEnabled} onChange={e => setForm(f => ({...f, compressionEnabled: e.target.checked}))}
                    className="w-4 h-4 rounded border-gray-300 text-blue-600" />
                  <label className="mb-0">Compression</label>
                </div>
              </div>
              {form.mdnAsync && (
                <div className="mt-3">
                  <label>Async MDN URL</label>
                  <input value={form.mdnUrl} onChange={e => setForm(f => ({...f, mdnUrl: e.target.value}))} placeholder="https://partner.com/as2/mdn" />
                </div>
              )}
            </div>

            <div className="flex gap-3 justify-end pt-2 border-t">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
                {createMut.isPending ? 'Creating...' : 'Create Partnership'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
