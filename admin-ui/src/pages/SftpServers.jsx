import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getSftpServers, createSftpServer, updateSftpServer, deleteSftpServer } from '../api/accounts'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilIcon, ServerStackIcon, SignalIcon, SignalSlashIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'

const emptyForm = {
  instanceId: '', name: '', description: '',
  internalHost: '', internalPort: 2222,
  externalHost: '', externalPort: '',
  useProxy: false, proxyHost: '', proxyPort: '',
  maxConnections: 500
}

export default function SftpServers() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editServer, setEditServer] = useState(null)
  const [form, setForm] = useState(emptyForm)

  const { data: servers = [], isLoading } = useQuery({ queryKey: ['sftp-servers'], queryFn: getSftpServers })

  const createMut = useMutation({
    mutationFn: createSftpServer,
    onSuccess: () => { qc.invalidateQueries(['sftp-servers']); setShowCreate(false); setForm(emptyForm); toast.success('SFTP Server created') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create server')
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateSftpServer(id, data),
    onSuccess: () => { qc.invalidateQueries(['sftp-servers']); setEditServer(null); toast.success('Server updated') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update server')
  })

  const deleteMut = useMutation({
    mutationFn: deleteSftpServer,
    onSuccess: () => { qc.invalidateQueries(['sftp-servers']); toast.success('Server deactivated') }
  })

  const toggleMut = useMutation({
    mutationFn: ({ id, active }) => updateSftpServer(id, { active }),
    onSuccess: () => { qc.invalidateQueries(['sftp-servers']); toast.success('Status updated') }
  })

  const openEdit = (s) => {
    setEditServer(s)
    setForm({
      instanceId: s.instanceId, name: s.name, description: s.description || '',
      internalHost: s.internalHost, internalPort: s.internalPort,
      externalHost: s.externalHost || '', externalPort: s.externalPort || '',
      useProxy: s.useProxy, proxyHost: s.proxyHost || '', proxyPort: s.proxyPort || '',
      maxConnections: s.maxConnections
    })
  }

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">SFTP Server Instances</h1>
          <p className="text-gray-500 text-sm">Manage SFTP server instances and assign users to specific servers</p>
        </div>
        <button className="btn-primary" onClick={() => { setForm(emptyForm); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> Add Server
        </button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-3 gap-4">
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Total Servers</p>
          <p className="text-2xl font-bold text-gray-900 mt-1">{servers.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Active</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{servers.filter(s => s.active).length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">With Proxy</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{servers.filter(s => s.useProxy).length}</p>
        </div>
      </div>

      {/* Server list */}
      {servers.length === 0 ? (
        <div className="card"><EmptyState title="No SFTP servers configured" description="Add your first SFTP server instance to get started." /></div>
      ) : (
        <div className="card">
          <table className="w-full">
            <thead>
              <tr className="border-b border-gray-100">
                <th className="table-header">Instance</th>
                <th className="table-header">Name</th>
                <th className="table-header">Internal</th>
                <th className="table-header">Client Connection</th>
                <th className="table-header">Proxy</th>
                <th className="table-header">Status</th>
                <th className="table-header">Actions</th>
              </tr>
            </thead>
            <tbody>
              {servers.map(s => (
                <tr key={s.id} className="table-row">
                  <td className="table-cell">
                    <div className="flex items-center gap-2">
                      <ServerStackIcon className="w-4 h-4 text-blue-500" />
                      <span className="font-mono text-xs font-medium">{s.instanceId}</span>
                    </div>
                  </td>
                  <td className="table-cell">
                    <div>
                      <p className="font-medium text-gray-900">{s.name}</p>
                      {s.description && <p className="text-xs text-gray-400">{s.description}</p>}
                    </div>
                  </td>
                  <td className="table-cell font-mono text-xs text-gray-500">{s.internalHost}:{s.internalPort}</td>
                  <td className="table-cell font-mono text-xs text-gray-700">{s.clientHost}:{s.clientPort}</td>
                  <td className="table-cell">
                    {s.useProxy ? (
                      <span className="badge badge-blue">Proxy: {s.proxyHost}:{s.proxyPort}</span>
                    ) : (
                      <span className="text-gray-400 text-xs">Direct</span>
                    )}
                  </td>
                  <td className="table-cell">
                    <button onClick={() => toggleMut.mutate({ id: s.id, active: !s.active })}
                      className={`inline-flex items-center gap-1 px-2 py-1 rounded text-xs font-medium ${
                        s.active ? 'bg-green-50 text-green-700 hover:bg-green-100' : 'bg-red-50 text-red-700 hover:bg-red-100'
                      }`}>
                      {s.active ? <><SignalIcon className="w-3 h-3" /> Active</> : <><SignalSlashIcon className="w-3 h-3" /> Inactive</>}
                    </button>
                  </td>
                  <td className="table-cell">
                    <div className="flex gap-1">
                      <button onClick={() => openEdit(s)} className="p-1.5 rounded hover:bg-blue-50 text-blue-500">
                        <PencilIcon className="w-4 h-4" />
                      </button>
                      <button onClick={() => { if(confirm('Deactivate this server?')) deleteMut.mutate(s.id) }}
                        className="p-1.5 rounded hover:bg-red-50 text-red-500">
                        <TrashIcon className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Create Modal */}
      {showCreate && (
        <Modal title="Add SFTP Server Instance" onClose={() => setShowCreate(false)}>
          <ServerForm form={form} setForm={setForm}
            onSubmit={() => createMut.mutate(form)}
            isPending={createMut.isPending}
            onCancel={() => setShowCreate(false)}
            submitLabel="Create Server"
            showInstanceId
          />
        </Modal>
      )}

      {/* Edit Modal */}
      {editServer && (
        <Modal title={`Edit: ${editServer.name}`} onClose={() => setEditServer(null)}>
          <ServerForm form={form} setForm={setForm}
            onSubmit={() => updateMut.mutate({ id: editServer.id, data: form })}
            isPending={updateMut.isPending}
            onCancel={() => setEditServer(null)}
            submitLabel="Save Changes"
          />
        </Modal>
      )}
    </div>
  )
}

function ServerForm({ form, setForm, onSubmit, isPending, onCancel, submitLabel, showInstanceId }) {
  const f = (key, val) => setForm(prev => ({ ...prev, [key]: val }))
  return (
    <form onSubmit={e => { e.preventDefault(); onSubmit() }} className="space-y-4">
      {showInstanceId && (
        <div>
          <label>Instance ID</label>
          <input value={form.instanceId} onChange={e => f('instanceId', e.target.value)} required
            placeholder="sftp-3" pattern="[a-z0-9\-]+" title="Lowercase letters, numbers, hyphens" />
          <p className="text-xs text-gray-400 mt-1">Unique identifier (e.g., sftp-3, sftp-eu-west)</p>
        </div>
      )}
      <div className="grid grid-cols-2 gap-4">
        <div><label>Name</label><input value={form.name} onChange={e => f('name', e.target.value)} required placeholder="EU West SFTP" /></div>
        <div><label>Max Connections</label><input type="number" value={form.maxConnections} onChange={e => f('maxConnections', parseInt(e.target.value))} /></div>
      </div>
      <div><label>Description</label><input value={form.description} onChange={e => f('description', e.target.value)} placeholder="Production SFTP server for EU region" /></div>

      {/* Internal connection */}
      <p className="text-xs text-gray-500 uppercase tracking-wider font-semibold pt-2">Internal Connection (Docker/Host)</p>
      <div className="grid grid-cols-2 gap-4">
        <div><label>Host</label><input value={form.internalHost} onChange={e => f('internalHost', e.target.value)} required placeholder="sftp-service-3" /></div>
        <div><label>Port</label><input type="number" value={form.internalPort} onChange={e => f('internalPort', parseInt(e.target.value))} /></div>
      </div>

      {/* External connection */}
      <p className="text-xs text-gray-500 uppercase tracking-wider font-semibold pt-2">External Connection (Client-Facing)</p>
      <div className="grid grid-cols-2 gap-4">
        <div><label>External Host</label><input value={form.externalHost} onChange={e => f('externalHost', e.target.value)} placeholder="sftp.example.com" /></div>
        <div><label>External Port</label><input type="number" value={form.externalPort} onChange={e => f('externalPort', e.target.value ? parseInt(e.target.value) : '')} placeholder="22222" /></div>
      </div>

      {/* Reverse proxy */}
      <div className="flex items-center gap-3 pt-2">
        <input type="checkbox" id="useProxy" checked={form.useProxy} onChange={e => f('useProxy', e.target.checked)}
          className="w-4 h-4 text-blue-600 rounded border-gray-300" />
        <label htmlFor="useProxy" className="text-sm font-medium text-gray-700">Use Reverse Proxy</label>
      </div>
      {form.useProxy && (
        <div className="grid grid-cols-2 gap-4 pl-7">
          <div><label>Proxy Host</label><input value={form.proxyHost} onChange={e => f('proxyHost', e.target.value)} placeholder="proxy.example.com" /></div>
          <div><label>Proxy Port</label><input type="number" value={form.proxyPort} onChange={e => f('proxyPort', e.target.value ? parseInt(e.target.value) : '')} placeholder="2222" /></div>
        </div>
      )}

      <div className="flex gap-3 justify-end pt-4 border-t">
        <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{submitLabel}</button>
      </div>
    </form>
  )
}
