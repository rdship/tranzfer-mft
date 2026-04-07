import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getFolderMappings, createFolderMapping, updateFolderMapping, deleteFolderMapping, getAccounts } from '../api/accounts'
import Modal from '../components/Modal'
import StoragePerformanceEstimator from '../components/StoragePerformanceEstimator'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilSquareIcon, ArrowRightIcon } from '@heroicons/react/24/outline'

const emptyForm = { sourceAccountId: '', sourcePath: '/inbox', destinationAccountId: '', destinationPath: '/outbox', filenamePattern: '', encryptionOption: 'NONE' }

export default function FolderMappings() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingMapping, setEditingMapping] = useState(null)
  const [form, setForm] = useState({ ...emptyForm })

  const { data: mappings = [], isLoading } = useQuery({ queryKey: ['folder-mappings'], queryFn: getFolderMappings })
  const { data: accounts = [] } = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })

  const createMut = useMutation({
    mutationFn: createFolderMapping,
    onSuccess: () => { qc.invalidateQueries(['folder-mappings']); setShowCreate(false); setForm({ ...emptyForm }); toast.success('Mapping created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateFolderMapping(id, data),
    onSuccess: () => { qc.invalidateQueries(['folder-mappings']); setEditingMapping(null); setForm({ ...emptyForm }); toast.success('Mapping updated') },
    onError: err => toast.error(err.response?.data?.error || 'Failed')
  })
  const deleteMut = useMutation({
    mutationFn: deleteFolderMapping,
    onSuccess: () => { qc.invalidateQueries(['folder-mappings']); toast.success('Deleted') }
  })

  const openEdit = (m) => {
    setForm({
      sourceAccountId: m.sourceAccountId || '',
      sourcePath: m.sourcePath || '/inbox',
      destinationAccountId: m.destinationAccountId || '',
      destinationPath: m.destinationPath || '/outbox',
      filenamePattern: m.filenamePattern || '',
      encryptionOption: m.encryptionOption || 'NONE',
    })
    setEditingMapping(m)
  }

  const openCreate = () => {
    setForm({ ...emptyForm })
    setShowCreate(true)
  }

  if (isLoading) return <LoadingSpinner />

  const renderForm = (onSubmit, isPending, submitLabel, pendingLabel) => (
    <form onSubmit={onSubmit} className="space-y-4">
      <div className="grid grid-cols-2 gap-6">
        <div className="space-y-3">
          <h4 className="font-semibold text-gray-900">Source</h4>
          <div><label>Account</label>
            <select value={form.sourceAccountId} onChange={e => setForm(f => ({...f, sourceAccountId: e.target.value}))} required>
              <option value="">Select account...</option>
              {accounts.map(a => <option key={a.id} value={a.id}>{a.username} ({a.protocol})</option>)}
            </select></div>
          <div><label>Path</label>
            <input value={form.sourcePath} onChange={e => setForm(f => ({...f, sourcePath: e.target.value}))} placeholder="/inbox" /></div>
        </div>
        <div className="space-y-3">
          <h4 className="font-semibold text-gray-900">Destination</h4>
          <div><label>Account</label>
            <select value={form.destinationAccountId} onChange={e => setForm(f => ({...f, destinationAccountId: e.target.value}))} required>
              <option value="">Select account...</option>
              {accounts.map(a => <option key={a.id} value={a.id}>{a.username} ({a.protocol})</option>)}
            </select></div>
          <div><label>Path</label>
            <input value={form.destinationPath} onChange={e => setForm(f => ({...f, destinationPath: e.target.value}))} placeholder="/outbox" /></div>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div><label>Filename Pattern (regex, empty=all)</label>
          <input value={form.filenamePattern} onChange={e => setForm(f => ({...f, filenamePattern: e.target.value}))} placeholder=".*\.csv$" className="font-mono text-xs" /></div>
        <div><label>Encryption</label>
          <select value={form.encryptionOption} onChange={e => setForm(f => ({...f, encryptionOption: e.target.value}))}>
            <option value="NONE">None</option>
            <option value="ENCRYPT_BEFORE_FORWARD">Encrypt before forward</option>
            <option value="DECRYPT_THEN_FORWARD">Decrypt then forward</option>
          </select></div>
      </div>
      <div className="flex gap-3 justify-end pt-2">
        <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); setEditingMapping(null) }}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{isPending ? pendingLabel : submitLabel}</button>
      </div>
    </form>
  )

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Folder Mappings</h1>
          <p className="text-gray-500 text-sm">Route files from source accounts to destinations</p>
        </div>
        <button className="btn-primary" onClick={openCreate}>
          <PlusIcon className="w-4 h-4" /> New Mapping
        </button>
      </div>

      {mappings.length === 0 ? (
        <div className="card">
          <EmptyState title="No folder mappings" description="Create a mapping to route files between accounts. This is the core of file routing."
            action={<button className="btn-primary" onClick={openCreate}><PlusIcon className="w-4 h-4" />Create Mapping</button>} />
        </div>
      ) : (
        <div className="space-y-3">
          {mappings.map(m => (
            <div key={m.id} className="card flex items-center gap-4">
              <div className="flex-1">
                <div className="flex items-center gap-2 text-sm">
                  <span className="font-mono font-semibold text-blue-700">{m.sourceAccount?.username || m.sourceAccountId?.substring(0,8)}</span>
                  <span className="text-gray-400">{m.sourcePath}</span>
                  <ArrowRightIcon className="w-4 h-4 text-gray-400" />
                  <span className="font-mono font-semibold text-green-700">{m.destinationAccount?.username || m.destinationAccountId?.substring(0,8)}</span>
                  <span className="text-gray-400">{m.destinationPath}</span>
                </div>
                {m.filenamePattern && <p className="text-xs text-gray-400 font-mono mt-1">Pattern: {m.filenamePattern}</p>}
              </div>
              <span className={`badge ${m.active ? 'badge-green' : 'badge-red'}`}>{m.active ? 'Active' : 'Disabled'}</span>
              <span className="badge badge-blue">{m.encryptionOption || 'NONE'}</span>
              <button onClick={() => openEdit(m)}
                className="p-1.5 rounded hover:bg-blue-50 text-blue-500 transition-colors"><PencilSquareIcon className="w-4 h-4" /></button>
              <button onClick={() => { if(confirm('Delete?')) deleteMut.mutate(m.id) }}
                className="p-1.5 rounded hover:bg-red-50 text-red-500"><TrashIcon className="w-4 h-4" /></button>
            </div>
          ))}
        </div>
      )}

      <StoragePerformanceEstimator />

      {showCreate && (
        <Modal title="Create Folder Mapping" size="lg" onClose={() => setShowCreate(false)}>
          {renderForm(
            e => { e.preventDefault(); createMut.mutate(form) },
            createMut.isPending, 'Create Mapping', 'Creating...'
          )}
        </Modal>
      )}

      {editingMapping && (
        <Modal title="Edit Folder Mapping" size="lg" onClose={() => setEditingMapping(null)}>
          {renderForm(
            e => { e.preventDefault(); updateMut.mutate({ id: editingMapping.id, data: form }) },
            updateMut.isPending, 'Save Changes', 'Saving...'
          )}
        </Modal>
      )}
    </div>
  )
}
