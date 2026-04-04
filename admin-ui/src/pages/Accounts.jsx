import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getAccounts, createAccount, deleteAccount, toggleAccount } from '../api/accounts'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const PROTOCOLS = ['SFTP', 'FTP', 'FTP_WEB']

export default function Accounts() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [search, setSearch] = useState('')
  const [form, setForm] = useState({ protocol: 'SFTP', username: '', password: '', homeDir: '' })

  const { data: accounts = [], isLoading } = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })
  const createMut = useMutation({ mutationFn: createAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); setShowCreate(false); toast.success('Account created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const deleteMut = useMutation({ mutationFn: deleteAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); toast.success('Account deleted') } })
  const toggleMut = useMutation({ mutationFn: ({ id, active }) => toggleAccount(id, active),
    onSuccess: () => qc.invalidateQueries(['accounts']) })

  const filtered = accounts.filter(a =>
    a.username?.toLowerCase().includes(search.toLowerCase()) ||
    a.protocol?.toLowerCase().includes(search.toLowerCase()))

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Transfer Accounts</h1>
          <p className="text-gray-500 text-sm">{accounts.length} accounts configured</p>
        </div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}>
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
                <th className="table-header">Home Directory</th>
                <th className="table-header">Status</th>
                <th className="table-header">Created</th>
                <th className="table-header">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(acc => (
                <tr key={acc.id} className="table-row">
                  <td className="table-cell font-medium">{acc.username}</td>
                  <td className="table-cell"><span className="badge badge-blue">{acc.protocol}</span></td>
                  <td className="table-cell text-gray-500 font-mono text-xs">{acc.homeDir}</td>
                  <td className="table-cell">
                    <button onClick={() => toggleMut.mutate({ id: acc.id, active: !acc.active })}
                      className={`badge cursor-pointer ${acc.active ? 'badge-green' : 'badge-red'}`}>
                      {acc.active ? 'Active' : 'Disabled'}
                    </button>
                  </td>
                  <td className="table-cell text-gray-500 text-xs">{acc.createdAt ? format(new Date(acc.createdAt), 'MMM d, yyyy') : '—'}</td>
                  <td className="table-cell">
                    <button onClick={() => { if (confirm('Delete account?')) deleteMut.mutate(acc.id) }}
                      className="p-1.5 rounded hover:bg-red-50 text-red-500 hover:text-red-700 transition-colors">
                      <TrashIcon className="w-4 h-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {showCreate && (
        <Modal title="Create Transfer Account" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div>
              <label>Protocol</label>
              <select value={form.protocol} onChange={e => setForm(f => ({ ...f, protocol: e.target.value }))}>
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
              <label>Home Directory</label>
              <input value={form.homeDir} onChange={e => setForm(f => ({ ...f, homeDir: e.target.value }))} placeholder="/data/sftp/partner_acme" />
            </div>
            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
                {createMut.isPending ? 'Creating...' : 'Create Account'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
