import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getAccounts, createAccount, updateAccount, deleteAccount, toggleAccount, getServerInstancesActive } from '../api/accounts'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilSquareIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const PROTOCOLS = ['SFTP', 'FTP', 'FTP_WEB']

const QOS_PRESETS = {
  HIGH:     { uploadBytesPerSecond: 52428800,  downloadBytesPerSecond: 52428800,  maxConcurrentSessions: 10, priority: 3, burstAllowancePercent: 20 },
  MEDIUM:   { uploadBytesPerSecond: 10485760,  downloadBytesPerSecond: 10485760,  maxConcurrentSessions: 3,  priority: 5, burstAllowancePercent: 10 },
  LOW:      { uploadBytesPerSecond: 1048576,   downloadBytesPerSecond: 1048576,   maxConcurrentSessions: 2,  priority: 8, burstAllowancePercent: 5 },
}

function formatBps(bps) {
  if (!bps || bps === 0) return 'Unlimited'
  if (bps >= 1048576) return `${(bps / 1048576).toFixed(0)} MB/s`
  if (bps >= 1024) return `${(bps / 1024).toFixed(0)} KB/s`
  return `${bps} B/s`
}

function getQosTier(acc) {
  const up = acc.qosUploadBytesPerSecond
  if (!up || up === 0) return { label: 'Unlimited', color: 'badge-purple' }
  if (up >= 52428800) return { label: 'HIGH', color: 'badge-green' }
  if (up >= 10485760) return { label: 'MEDIUM', color: 'badge-blue' }
  return { label: 'LOW', color: 'badge-yellow' }
}

const defaultForm = { protocol: 'SFTP', username: '', password: '', confirmPassword: '', homeDir: '', serverInstance: '', qos: { ...QOS_PRESETS.MEDIUM } }
const defaultEditQos = { uploadBytesPerSecond: '', downloadBytesPerSecond: '', maxConcurrentSessions: '', priority: '', burstAllowancePercent: '' }

export default function Accounts() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editAccount, setEditAccount] = useState(null)
  const [search, setSearch] = useState('')
  const [form, setForm] = useState({ ...defaultForm })
  const [editQos, setEditQos] = useState({ ...defaultEditQos })

  const { data: accounts = [], isLoading } = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })
  const { data: serverInstances = [] } = useQuery({ queryKey: ['server-instances-active'], queryFn: getServerInstancesActive })
  const createMut = useMutation({ mutationFn: createAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); setShowCreate(false); setForm({ ...defaultForm }); toast.success('Account created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const updateMut = useMutation({ mutationFn: ({ id, data }) => updateAccount(id, data),
    onSuccess: () => { qc.invalidateQueries(['accounts']); setEditAccount(null); toast.success('Account updated') },
    onError: err => toast.error(err.response?.data?.error || 'Update failed') })
  const deleteMut = useMutation({ mutationFn: deleteAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); toast.success('Account deleted') } })
  const toggleMut = useMutation({ mutationFn: ({ id, active }) => toggleAccount(id, active),
    onSuccess: () => qc.invalidateQueries(['accounts']) })

  const filtered = accounts.filter(a =>
    a.username?.toLowerCase().includes(search.toLowerCase()) ||
    a.protocol?.toLowerCase().includes(search.toLowerCase()))

  const filteredInstances = serverInstances.filter(s => s.protocol === form.protocol)

  const applyPreset = (tier) => {
    setForm(f => ({ ...f, qos: { ...QOS_PRESETS[tier] } }))
  }

  const openEdit = (acc) => {
    setEditAccount(acc)
    setEditQos({
      uploadBytesPerSecond: acc.qosUploadBytesPerSecond || '',
      downloadBytesPerSecond: acc.qosDownloadBytesPerSecond || '',
      maxConcurrentSessions: acc.qosMaxConcurrentSessions || '',
      priority: acc.qosPriority || '',
      burstAllowancePercent: acc.qosBurstAllowancePercent || '',
    })
  }

  const handleEditSubmit = (e) => {
    e.preventDefault()
    const qos = {}
    if (editQos.uploadBytesPerSecond !== '') qos.uploadBytesPerSecond = Number(editQos.uploadBytesPerSecond)
    if (editQos.downloadBytesPerSecond !== '') qos.downloadBytesPerSecond = Number(editQos.downloadBytesPerSecond)
    if (editQos.maxConcurrentSessions !== '') qos.maxConcurrentSessions = Number(editQos.maxConcurrentSessions)
    if (editQos.priority !== '') qos.priority = Number(editQos.priority)
    if (editQos.burstAllowancePercent !== '') qos.burstAllowancePercent = Number(editQos.burstAllowancePercent)
    updateMut.mutate({ id: editAccount.id, data: { qos } })
  }

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Transfer Accounts</h1>
          <p className="text-gray-500 text-sm">{accounts.length} accounts configured</p>
        </div>
        <button className="btn-primary" onClick={() => { setForm({ ...defaultForm }); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> New Account
        </button>
      </div>

      <div className="card">
        <div className="mb-4">
          <input placeholder="Search by username or protocol..." value={search} onChange={e => setSearch(e.target.value)} className="max-w-sm" />
        </div>

        {filtered.length === 0 ? (
          <EmptyState title="No accounts found" description="Create your first transfer account to get started." action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />New Account</button>} />
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-gray-100">
                <th className="table-header">Username</th>
                <th className="table-header">Protocol</th>
                <th className="table-header">Server</th>
                <th className="table-header">QoS</th>
                <th className="table-header">Bandwidth</th>
                <th className="table-header">Sessions</th>
                <th className="table-header">Status</th>
                <th className="table-header">Created</th>
                <th className="table-header">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(acc => {
                const tier = getQosTier(acc)
                return (
                <tr key={acc.id} className="table-row">
                  <td className="table-cell font-medium">{acc.username}</td>
                  <td className="table-cell"><span className="badge badge-blue">{acc.protocol}</span></td>
                  <td className="table-cell text-xs text-gray-500">{acc.serverInstance || <span className="text-gray-300">Any</span>}</td>
                  <td className="table-cell"><span className={`badge ${tier.color}`}>{tier.label}</span></td>
                  <td className="table-cell text-xs text-gray-500">
                    <span title="Upload">&uarr;{formatBps(acc.qosUploadBytesPerSecond)}</span>
                    {' / '}
                    <span title="Download">&darr;{formatBps(acc.qosDownloadBytesPerSecond)}</span>
                  </td>
                  <td className="table-cell text-xs text-gray-500">
                    {acc.qosMaxConcurrentSessions || '-'}
                  </td>
                  <td className="table-cell">
                    <button onClick={() => toggleMut.mutate({ id: acc.id, active: !acc.active })}
                      className={`badge cursor-pointer ${acc.active ? 'badge-green' : 'badge-red'}`}>
                      {acc.active ? 'Active' : 'Disabled'}
                    </button>
                  </td>
                  <td className="table-cell text-gray-500 text-xs">{acc.createdAt ? format(new Date(acc.createdAt), 'MMM d, yyyy') : '-'}</td>
                  <td className="table-cell">
                    <div className="flex gap-1">
                      <button onClick={() => openEdit(acc)}
                        className="p-1.5 rounded hover:bg-blue-50 text-blue-500 hover:text-blue-700 transition-colors">
                        <PencilSquareIcon className="w-4 h-4" />
                      </button>
                      <button onClick={() => { if (confirm('Delete account?')) deleteMut.mutate(acc.id) }}
                        className="p-1.5 rounded hover:bg-red-50 text-red-500 hover:text-red-700 transition-colors">
                        <TrashIcon className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              )})}
            </tbody>
          </table>
        )}
      </div>

      {/* Create Account Modal */}
      {showCreate && (
        <Modal title="Create Transfer Account" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); if (form.password !== form.confirmPassword) { toast.error('Passwords do not match'); return }; const { confirmPassword, ...payload } = form; createMut.mutate(payload) }} className="space-y-4">
            <div>
              <label>Protocol</label>
              <select value={form.protocol} onChange={e => setForm(f => ({ ...f, protocol: e.target.value, serverInstance: '' }))}>
                {PROTOCOLS.map(p => <option key={p}>{p}</option>)}
              </select>
            </div>
            <div>
              <label>Username</label>
              <input value={form.username} onChange={e => setForm(f => ({ ...f, username: e.target.value }))} required placeholder="e.g. partner_acme" />
            </div>
            <div>
              <label>Password</label>
              <input type="password" value={form.password} onChange={e => setForm(f => ({ ...f, password: e.target.value }))} required />
            </div>
            <div>
              <label>Confirm Password</label>
              <input type="password" value={form.confirmPassword} onChange={e => setForm(f => ({ ...f, confirmPassword: e.target.value }))} required />
              {form.confirmPassword && form.password !== form.confirmPassword && (
                <p className="text-xs text-red-500 mt-1">Passwords do not match</p>
              )}
            </div>
            <div>
              <label>Home Directory</label>
              <input value={form.homeDir} onChange={e => setForm(f => ({ ...f, homeDir: e.target.value }))} placeholder="/data/sftp/partner_acme" />
            </div>
            {filteredInstances.length > 0 && (
              <div>
                <label>Server Instance</label>
                <select value={form.serverInstance} onChange={e => setForm(f => ({ ...f, serverInstance: e.target.value }))}>
                  <option value="">Any (no restriction)</option>
                  {filteredInstances.map(s => (
                    <option key={s.instanceId} value={s.instanceId}>{s.name} ({s.instanceId})</option>
                  ))}
                </select>
                <p className="text-xs text-gray-400 mt-1">Restrict this account to a specific {form.protocol} server</p>
              </div>
            )}

            {/* QoS Configuration */}
            <div className="border-t border-gray-200 pt-4 mt-4">
              <div className="flex items-center justify-between mb-3">
                <label className="text-sm font-semibold text-gray-700">Quality of Service</label>
                <div className="flex gap-1.5">
                  {Object.keys(QOS_PRESETS).map(tier => (
                    <button key={tier} type="button" onClick={() => applyPreset(tier)}
                      className="px-2.5 py-1 rounded text-xs font-medium bg-gray-100 hover:bg-gray-200 text-gray-700 transition-colors">
                      {tier}
                    </button>
                  ))}
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-gray-500">Upload Limit (MB/s)</label>
                  <input type="number" min="0" value={form.qos.uploadBytesPerSecond ? Math.round(form.qos.uploadBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, uploadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label className="text-xs text-gray-500">Download Limit (MB/s)</label>
                  <input type="number" min="0" value={form.qos.downloadBytesPerSecond ? Math.round(form.qos.downloadBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, downloadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label className="text-xs text-gray-500">Max Concurrent Sessions</label>
                  <input type="number" min="1" max="100" value={form.qos.maxConcurrentSessions || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, maxConcurrentSessions: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label className="text-xs text-gray-500">Priority (1=Highest, 10=Lowest)</label>
                  <input type="number" min="1" max="10" value={form.qos.priority || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, priority: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label className="text-xs text-gray-500">Burst Allowance (%)</label>
                  <input type="number" min="0" max="100" value={form.qos.burstAllowancePercent || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, burstAllowancePercent: Number(e.target.value) } }))} />
                </div>
              </div>
            </div>

            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending || (form.confirmPassword && form.password !== form.confirmPassword)}>
                {createMut.isPending ? 'Creating...' : 'Create Account'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Edit Account QoS Modal */}
      {editAccount && (
        <Modal title={`Edit QoS: ${editAccount.username}`} onClose={() => setEditAccount(null)}>
          <form onSubmit={handleEditSubmit} className="space-y-4">
            <div className="flex gap-1.5 mb-3">
              {Object.entries(QOS_PRESETS).map(([tier, preset]) => (
                <button key={tier} type="button" onClick={() => setEditQos({
                  uploadBytesPerSecond: preset.uploadBytesPerSecond,
                  downloadBytesPerSecond: preset.downloadBytesPerSecond,
                  maxConcurrentSessions: preset.maxConcurrentSessions,
                  priority: preset.priority,
                  burstAllowancePercent: preset.burstAllowancePercent,
                })}
                  className="px-2.5 py-1 rounded text-xs font-medium bg-gray-100 hover:bg-gray-200 text-gray-700 transition-colors">
                  {tier}
                </button>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-gray-500">Upload Limit (MB/s)</label>
                <input type="number" min="0" value={editQos.uploadBytesPerSecond ? Math.round(editQos.uploadBytesPerSecond / 1048576) : ''}
                  onChange={e => setEditQos(q => ({ ...q, uploadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 }))}
                  placeholder="0 = unlimited" />
              </div>
              <div>
                <label className="text-xs text-gray-500">Download Limit (MB/s)</label>
                <input type="number" min="0" value={editQos.downloadBytesPerSecond ? Math.round(editQos.downloadBytesPerSecond / 1048576) : ''}
                  onChange={e => setEditQos(q => ({ ...q, downloadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 }))}
                  placeholder="0 = unlimited" />
              </div>
              <div>
                <label className="text-xs text-gray-500">Max Concurrent Sessions</label>
                <input type="number" min="1" max="100" value={editQos.maxConcurrentSessions || ''}
                  onChange={e => setEditQos(q => ({ ...q, maxConcurrentSessions: Number(e.target.value) }))} />
              </div>
              <div>
                <label className="text-xs text-gray-500">Priority (1=Highest, 10=Lowest)</label>
                <input type="number" min="1" max="10" value={editQos.priority || ''}
                  onChange={e => setEditQos(q => ({ ...q, priority: Number(e.target.value) }))} />
              </div>
              <div>
                <label className="text-xs text-gray-500">Burst Allowance (%)</label>
                <input type="number" min="0" max="100" value={editQos.burstAllowancePercent || ''}
                  onChange={e => setEditQos(q => ({ ...q, burstAllowancePercent: Number(e.target.value) }))} />
              </div>
            </div>
            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setEditAccount(null)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={updateMut.isPending}>
                {updateMut.isPending ? 'Saving...' : 'Save QoS'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
