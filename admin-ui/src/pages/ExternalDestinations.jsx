import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getExternalDestinations, createExternalDestination, deleteExternalDestination } from '../api/config'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, SignalIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'

export default function ExternalDestinations() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', type: 'SFTP', host: '', port: 22, username: '', encryptedPassword: '', remotePath: '/incoming' })

  const { data: dests = [], isLoading } = useQuery({ queryKey: ['ext-dests'], queryFn: getExternalDestinations })
  const createMut = useMutation({ mutationFn: createExternalDestination,
    onSuccess: () => { qc.invalidateQueries(['ext-dests']); setShowCreate(false); toast.success('Destination created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const deleteMut = useMutation({ mutationFn: deleteExternalDestination,
    onSuccess: () => { qc.invalidateQueries(['ext-dests']); toast.success('Deleted') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">External Destinations</h1>
          <p className="text-gray-500 text-sm">SFTP/FTP servers outside your platform for file forwarding</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> Add Destination</button>
      </div>
      {dests.length === 0 ? (
        <div className="card"><EmptyState title="No external destinations" description="Add partner SFTP/FTP servers to forward files externally." action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />Add Destination</button>} /></div>
      ) : (
        <div className="space-y-3">
          {dests.map(d => (
            <div key={d.id} className="card flex items-center gap-4">
              <SignalIcon className="w-5 h-5 text-blue-500 flex-shrink-0" />
              <div className="flex-1">
                <h3 className="font-semibold text-gray-900">{d.name}</h3>
                <p className="text-xs text-gray-500 font-mono">{d.type}://{d.username}@{d.host}:{d.port}{d.remotePath}</p>
              </div>
              <span className={`badge ${d.active ? 'badge-green' : 'badge-red'}`}>{d.active ? 'Active' : 'Disabled'}</span>
              <span className="badge badge-blue">{d.type}</span>
              <button onClick={() => { if(confirm('Delete?')) deleteMut.mutate(d.id) }} className="p-1.5 rounded hover:bg-red-50 text-red-500"><TrashIcon className="w-4 h-4" /></button>
            </div>
          ))}
        </div>
      )}
      {showCreate && (
        <Modal title="Add External Destination" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="partner-acme-sftp" /></div>
              <div><label>Type</label><select value={form.type} onChange={e => setForm(f => ({...f, type: e.target.value}))}><option>SFTP</option><option>FTP</option></select></div>
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div><label>Host</label><input value={form.host} onChange={e => setForm(f => ({...f, host: e.target.value}))} required placeholder="sftp.partner.com" /></div>
              <div><label>Port</label><input type="number" value={form.port} onChange={e => setForm(f => ({...f, port: parseInt(e.target.value)}))} /></div>
              <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/incoming" /></div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div><label>Username</label><input value={form.username} onChange={e => setForm(f => ({...f, username: e.target.value}))} required /></div>
              <div><label>Password</label><input type="password" value={form.encryptedPassword} onChange={e => setForm(f => ({...f, encryptedPassword: e.target.value}))} required /></div>
            </div>
            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>Create</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
