import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getAccounts, createAccount, updateAccount, deleteAccount, toggleAccount, getServerInstancesActive } from '../api/accounts'
import Modal from '../components/Modal'
import FormField, { friendlyError } from '../components/FormField'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilSquareIcon, MagnifyingGlassIcon } from '@heroicons/react/24/outline'
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
  const [createErrors, setCreateErrors] = useState({})
  const createMut = useMutation({ mutationFn: createAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); setShowCreate(false); setForm({ ...defaultForm }); setCreateErrors({}); toast.success('Account created') },
    onError: err => toast.error(friendlyError(err)) })
  const updateMut = useMutation({ mutationFn: ({ id, data }) => updateAccount(id, data),
    onSuccess: () => { qc.invalidateQueries(['accounts']); setEditAccount(null); toast.success('Account updated') },
    onError: err => toast.error(friendlyError(err)) })
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
          <h1 className="text-2xl font-bold text-primary">Transfer Accounts</h1>
          <p className="text-secondary text-sm">{accounts.length} accounts configured</p>
        </div>
        <button className="btn-primary" onClick={() => { setForm({ ...defaultForm }); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> New Account
        </button>
      </div>

      <div className="card">
        <div className="mb-4">
          <div className="relative max-w-sm">
            <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted pointer-events-none" />
            <input placeholder="Search by username or protocol..." value={search} onChange={e => setSearch(e.target.value)} className="pl-10 pr-3 py-2 text-sm border rounded-lg w-full focus:ring-2 focus:ring-accent" />
          </div>
        </div>

        {filtered.length === 0 ? (
          <EmptyState title="No accounts found" description="Create your first transfer account to get started." action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />New Account</button>} />
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-border">
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
                  <td className="table-cell text-xs text-secondary">{acc.serverInstance || <span className="text-muted">Any</span>}</td>
                  <td className="table-cell"><span className={`badge ${tier.color}`}>{tier.label}</span></td>
                  <td className="table-cell text-xs text-secondary">
                    <span title="Upload">&uarr;{formatBps(acc.qosUploadBytesPerSecond)}</span>
                    {' / '}
                    <span title="Download">&darr;{formatBps(acc.qosDownloadBytesPerSecond)}</span>
                  </td>
                  <td className="table-cell text-xs text-secondary">
                    {acc.qosMaxConcurrentSessions || '-'}
                  </td>
                  <td className="table-cell">
                    <button onClick={() => toggleMut.mutate({ id: acc.id, active: !acc.active })}
                      className={`badge cursor-pointer ${acc.active ? 'badge-green' : 'badge-red'}`}>
                      {acc.active ? 'Active' : 'Disabled'}
                    </button>
                  </td>
                  <td className="table-cell text-secondary text-xs">{acc.createdAt ? format(new Date(acc.createdAt), 'MMM d, yyyy') : '-'}</td>
                  <td className="table-cell">
                    <div className="flex gap-1">
                      <button onClick={() => openEdit(acc)} title="Edit account"
                        className="p-1.5 rounded hover:bg-accent-soft text-accent hover:text-accent transition-colors">
                        <PencilSquareIcon className="w-4 h-4" />
                      </button>
                      <button onClick={() => { if (confirm('Delete account?')) deleteMut.mutate(acc.id) }} title="Delete account"
                        className="p-1.5 rounded hover:bg-[rgb(60,20,20)] text-[rgb(240,120,120)] hover:text-[rgb(255,140,140)] transition-colors">
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
        <Modal title="Create Transfer Account" onClose={() => { setShowCreate(false); setCreateErrors({}) }}>
          <form onSubmit={e => {
            e.preventDefault()
            const errs = {}
            if (!form.username.trim()) errs.username = 'Username is required'
            if (!form.password) errs.password = 'Password is required'
            else if (form.password.length < 8) errs.password = 'Password must be at least 8 characters'
            if (form.password !== form.confirmPassword) errs.confirmPassword = 'Passwords do not match'
            setCreateErrors(errs)
            if (Object.keys(errs).length > 0) return
            const { confirmPassword, ...payload } = form
            createMut.mutate(payload)
          }} className="space-y-4">
            <FormField label="Protocol" helper="The file transfer protocol for this account.">
              <select value={form.protocol} onChange={e => setForm(f => ({ ...f, protocol: e.target.value, serverInstance: '' }))}>
                {PROTOCOLS.map(p => <option key={p}>{p}</option>)}
              </select>
            </FormField>
            <FormField label="Username" required error={createErrors.username} helper="Unique login name for this transfer account.">
              <input value={form.username} onChange={e => { setForm(f => ({ ...f, username: e.target.value })); setCreateErrors(prev => { const n = {...prev}; delete n.username; return n }) }} placeholder="e.g. partner_acme"
                className={createErrors.username ? 'border-red-300 focus:ring-red-500' : ''} />
            </FormField>
            <FormField label="Password" required error={createErrors.password} helper="Minimum 8 characters. Used for protocol authentication.">
              <input type="password" value={form.password} onChange={e => { setForm(f => ({ ...f, password: e.target.value })); setCreateErrors(prev => { const n = {...prev}; delete n.password; return n }) }}
                className={createErrors.password ? 'border-red-300 focus:ring-red-500' : ''} />
            </FormField>
            <FormField label="Confirm Password" required error={createErrors.confirmPassword}>
              <input type="password" value={form.confirmPassword} onChange={e => { setForm(f => ({ ...f, confirmPassword: e.target.value })); setCreateErrors(prev => { const n = {...prev}; delete n.confirmPassword; return n }) }}
                className={createErrors.confirmPassword ? 'border-red-300 focus:ring-red-500' : ''} />
            </FormField>
            <FormField label="Home Directory" helper="Root directory for this account's files (e.g., /data/sftp/partner).">
              <input value={form.homeDir} onChange={e => setForm(f => ({ ...f, homeDir: e.target.value }))} placeholder="/data/sftp/partner_acme" />
            </FormField>
            {filteredInstances.length > 0 && (
              <div>
                <label>Server Instance</label>
                <select value={form.serverInstance} onChange={e => setForm(f => ({ ...f, serverInstance: e.target.value }))}>
                  <option value="">Any (no restriction)</option>
                  {filteredInstances.map(s => (
                    <option key={s.instanceId} value={s.instanceId}>{s.name} ({s.instanceId})</option>
                  ))}
                </select>
                <p className="text-xs text-muted mt-1">Restrict this account to a specific {form.protocol} server</p>
              </div>
            )}

            {/* QoS Configuration */}
            <div className="border-t border-border pt-4 mt-4">
              <div className="flex items-center justify-between mb-3">
                <label className="text-sm font-semibold text-primary">Quality of Service</label>
                <div className="flex gap-1.5">
                  {Object.keys(QOS_PRESETS).map(tier => (
                    <button key={tier} type="button" onClick={() => applyPreset(tier)}
                      className="px-2.5 py-1 rounded text-xs font-medium bg-hover hover:bg-surface text-primary transition-colors">
                      {tier}
                    </button>
                  ))}
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-secondary">Upload Limit (MB/s)</label>
                  <input type="number" min="0" value={form.qos.uploadBytesPerSecond ? Math.round(form.qos.uploadBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, uploadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label className="text-xs text-secondary">Download Limit (MB/s)</label>
                  <input type="number" min="0" value={form.qos.downloadBytesPerSecond ? Math.round(form.qos.downloadBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, downloadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label className="text-xs text-secondary">Max Concurrent Sessions</label>
                  <input type="number" min="1" max="100" value={form.qos.maxConcurrentSessions || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, maxConcurrentSessions: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label className="text-xs text-secondary">Priority (1=Highest, 10=Lowest)</label>
                  <input type="number" min="1" max="10" value={form.qos.priority || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, priority: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label className="text-xs text-secondary">Burst Allowance (%)</label>
                  <input type="number" min="0" max="100" value={form.qos.burstAllowancePercent || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, burstAllowancePercent: Number(e.target.value) } }))} />
                </div>
              </div>
            </div>

            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); setCreateErrors({}) }}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
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
                  className="px-2.5 py-1 rounded text-xs font-medium bg-hover hover:bg-surface text-primary transition-colors">
                  {tier}
                </button>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-secondary">Upload Limit (MB/s)</label>
                <input type="number" min="0" value={editQos.uploadBytesPerSecond ? Math.round(editQos.uploadBytesPerSecond / 1048576) : ''}
                  onChange={e => setEditQos(q => ({ ...q, uploadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 }))}
                  placeholder="0 = unlimited" />
              </div>
              <div>
                <label className="text-xs text-secondary">Download Limit (MB/s)</label>
                <input type="number" min="0" value={editQos.downloadBytesPerSecond ? Math.round(editQos.downloadBytesPerSecond / 1048576) : ''}
                  onChange={e => setEditQos(q => ({ ...q, downloadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 }))}
                  placeholder="0 = unlimited" />
              </div>
              <div>
                <label className="text-xs text-secondary">Max Concurrent Sessions</label>
                <input type="number" min="1" max="100" value={editQos.maxConcurrentSessions || ''}
                  onChange={e => setEditQos(q => ({ ...q, maxConcurrentSessions: Number(e.target.value) }))} />
              </div>
              <div>
                <label className="text-xs text-secondary">Priority (1=Highest, 10=Lowest)</label>
                <input type="number" min="1" max="10" value={editQos.priority || ''}
                  onChange={e => setEditQos(q => ({ ...q, priority: Number(e.target.value) }))} />
              </div>
              <div>
                <label className="text-xs text-secondary">Burst Allowance (%)</label>
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
