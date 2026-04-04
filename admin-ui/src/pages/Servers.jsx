import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getServers, createServer, deleteServer } from '../api/config'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'

export default function Servers() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', serviceType: 'SFTP', host: '', port: 2222, maxConnections: 1000 })

  const { data: servers = [], isLoading } = useQuery({ queryKey: ['servers'], queryFn: getServers })
  const createMut = useMutation({ mutationFn: createServer,
    onSuccess: () => { qc.invalidateQueries(['servers']); setShowCreate(false); toast.success('Server created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const deleteMut = useMutation({ mutationFn: deleteServer,
    onSuccess: () => { qc.invalidateQueries(['servers']); toast.success('Deleted') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Server Configurations</h1>
          <p className="text-gray-500 text-sm">Manage SFTP/FTP server instances</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> New Server</button>
      </div>
      {servers.length === 0 ? (
        <div className="card"><EmptyState title="No servers configured" description="Server configurations are auto-registered by running services." /></div>
      ) : (
        <div className="card">
          <table className="w-full">
            <thead><tr className="border-b border-gray-100">
              <th className="table-header">Name</th><th className="table-header">Type</th><th className="table-header">Host</th><th className="table-header">Port</th><th className="table-header">Actions</th>
            </tr></thead>
            <tbody>
              {servers.map(s => (
                <tr key={s.id} className="table-row">
                  <td className="table-cell font-medium">{s.name}</td>
                  <td className="table-cell"><span className="badge badge-blue">{s.serviceType}</span></td>
                  <td className="table-cell text-gray-500 font-mono text-xs">{s.host}</td>
                  <td className="table-cell">{s.port}</td>
                  <td className="table-cell"><button onClick={() => { if(confirm('Delete?')) deleteMut.mutate(s.id) }} className="p-1.5 rounded hover:bg-red-50 text-red-500"><TrashIcon className="w-4 h-4" /></button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {showCreate && (
        <Modal title="Add Server" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="sftp-prod-1" /></div>
            <div className="grid grid-cols-3 gap-3">
              <div><label>Type</label><select value={form.serviceType} onChange={e => setForm(f => ({...f, serviceType: e.target.value}))}><option>SFTP</option><option>FTP</option><option>FTP_WEB</option></select></div>
              <div><label>Host</label><input value={form.host} onChange={e => setForm(f => ({...f, host: e.target.value}))} placeholder="0.0.0.0" /></div>
              <div><label>Port</label><input type="number" value={form.port} onChange={e => setForm(f => ({...f, port: parseInt(e.target.value)}))} /></div>
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
