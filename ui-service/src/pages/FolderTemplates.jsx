import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getFolderTemplates, createFolderTemplate, updateFolderTemplate, deleteFolderTemplate, exportAllFolderTemplates, importFolderTemplates } from '../api/config'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilIcon, ArrowDownTrayIcon, ArrowUpTrayIcon, LockClosedIcon, FolderIcon } from '@heroicons/react/24/outline'
import { useState, useRef } from 'react'

export default function FolderTemplates() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editTemplate, setEditTemplate] = useState(null)
  const [form, setForm] = useState({ name: '', description: '', folders: [{ path: '', description: '' }] })
  const fileRef = useRef(null)

  const { data: templates = [], isLoading } = useQuery({ queryKey: ['folder-templates'], queryFn: getFolderTemplates })

  const createMut = useMutation({
    mutationFn: createFolderTemplate,
    onSuccess: () => { qc.invalidateQueries(['folder-templates']); setShowCreate(false); toast.success('Template created') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create')
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateFolderTemplate(id, data),
    onSuccess: () => { qc.invalidateQueries(['folder-templates']); setEditTemplate(null); toast.success('Template updated') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update')
  })

  const deleteMut = useMutation({
    mutationFn: deleteFolderTemplate,
    onSuccess: () => { qc.invalidateQueries(['folder-templates']); toast.success('Template deleted') },
    onError: err => toast.error(err.response?.data?.message || 'Cannot delete')
  })

  const openEdit = (t) => {
    setEditTemplate(t)
    setForm({ name: t.name, description: t.description || '', folders: t.folders.length ? t.folders : [{ path: '', description: '' }] })
  }

  const handleExport = async () => {
    try {
      const data = await exportAllFolderTemplates()
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = 'folder-templates.json'; a.click()
      URL.revokeObjectURL(url)
      toast.success('Exported')
    } catch { toast.error('Export failed') }
  }

  const handleImport = async (e) => {
    const file = e.target.files[0]
    if (!file) return
    try {
      const text = await file.text()
      let data = JSON.parse(text)
      if (!Array.isArray(data)) data = [data]
      await importFolderTemplates(data)
      qc.invalidateQueries(['folder-templates'])
      toast.success(`Imported ${data.length} template(s)`)
    } catch (err) {
      toast.error(err.response?.data?.message || 'Import failed')
    }
    e.target.value = ''
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    const payload = { ...form, folders: form.folders.filter(f => f.path.trim()) }
    if (editTemplate) {
      updateMut.mutate({ id: editTemplate.id, data: payload })
    } else {
      createMut.mutate(payload)
    }
  }

  const addFolder = () => setForm(f => ({ ...f, folders: [...f.folders, { path: '', description: '' }] }))
  const removeFolder = (i) => setForm(f => ({ ...f, folders: f.folders.filter((_, idx) => idx !== i) }))
  const updateFolder = (i, key, val) => setForm(f => ({
    ...f, folders: f.folders.map((fd, idx) => idx === i ? { ...fd, [key]: val } : fd)
  }))

  if (isLoading) return <LoadingSpinner />

  const builtIn = templates.filter(t => t.builtIn)
  const custom = templates.filter(t => !t.builtIn)

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Folder Templates</h1>
          <p className="text-gray-500 text-sm">Define directory structures for server instances. Assign templates to servers to control partner folder layouts.</p>
        </div>
        <div className="flex gap-2">
          <button className="btn-secondary" onClick={handleExport}><ArrowDownTrayIcon className="w-4 h-4" /> Export</button>
          <button className="btn-secondary" onClick={() => fileRef.current?.click()}><ArrowUpTrayIcon className="w-4 h-4" /> Import</button>
          <input ref={fileRef} type="file" accept=".json" className="hidden" onChange={handleImport} />
          <button className="btn-primary" onClick={() => { setForm({ name: '', description: '', folders: [{ path: '', description: '' }] }); setShowCreate(true) }}>
            <PlusIcon className="w-4 h-4" /> New Template
          </button>
        </div>
      </div>

      {/* Summary */}
      <div className="grid grid-cols-3 gap-4">
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Total Templates</p>
          <p className="text-2xl font-bold text-gray-900 mt-1">{templates.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Built-in</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{builtIn.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Custom</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{custom.length}</p>
        </div>
      </div>

      {templates.length === 0 ? <EmptyState title="No folder templates" /> : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {templates.map(t => (
            <div key={t.id} className="card p-4 space-y-3">
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="font-semibold text-gray-900">{t.name}</h3>
                    {t.builtIn && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700">
                        <LockClosedIcon className="w-3 h-3" /> Built-in
                      </span>
                    )}
                  </div>
                  {t.description && <p className="text-sm text-gray-500 mt-1">{t.description}</p>}
                </div>
                {!t.builtIn && (
                  <div className="flex gap-1">
                    <button className="p-1 text-gray-400 hover:text-blue-600" onClick={() => openEdit(t)}><PencilIcon className="w-4 h-4" /></button>
                    <button className="p-1 text-gray-400 hover:text-red-600" onClick={() => { if (confirm('Delete this template?')) deleteMut.mutate(t.id) }}><TrashIcon className="w-4 h-4" /></button>
                  </div>
                )}
              </div>
              <div className="flex flex-wrap gap-1.5">
                {t.folders.map((f, i) => (
                  <span key={i} className="inline-flex items-center gap-1 px-2 py-1 rounded bg-gray-100 text-xs font-mono text-gray-700">
                    <FolderIcon className="w-3 h-3 text-yellow-500" /> {f.path}
                  </span>
                ))}
              </div>
              <p className="text-xs text-gray-400">{t.folders.length} folder{t.folders.length !== 1 ? 's' : ''}</p>
            </div>
          ))}
        </div>
      )}

      {/* Create Modal */}
      <Modal open={showCreate} onClose={() => setShowCreate(false)} title="Create Folder Template">
        <TemplateForm form={form} setForm={setForm} onSubmit={handleSubmit}
          addFolder={addFolder} removeFolder={removeFolder} updateFolder={updateFolder}
          onCancel={() => setShowCreate(false)} submitLabel="Create" isPending={createMut.isPending} />
      </Modal>

      {/* Edit Modal */}
      <Modal open={!!editTemplate} onClose={() => setEditTemplate(null)} title="Edit Folder Template">
        <TemplateForm form={form} setForm={setForm} onSubmit={handleSubmit}
          addFolder={addFolder} removeFolder={removeFolder} updateFolder={updateFolder}
          onCancel={() => setEditTemplate(null)} submitLabel="Save" isPending={updateMut.isPending} />
      </Modal>
    </div>
  )
}

function TemplateForm({ form, setForm, onSubmit, addFolder, removeFolder, updateFolder, onCancel, submitLabel, isPending }) {
  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div>
        <label>Template Name</label>
        <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} required placeholder="My Custom Template" />
      </div>
      <div>
        <label>Description</label>
        <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} placeholder="What is this template for?" />
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="text-sm font-medium text-gray-700">Folders</label>
          <button type="button" className="text-xs text-blue-600 hover:text-blue-800" onClick={addFolder}>+ Add Folder</button>
        </div>
        <div className="space-y-2">
          {form.folders.map((f, i) => (
            <div key={i} className="flex gap-2 items-start">
              <div className="flex-1">
                <input value={f.path} onChange={e => updateFolder(i, 'path', e.target.value)}
                  placeholder="e.g. inbox, edi/inbound" className="font-mono text-sm" required />
              </div>
              <div className="flex-1">
                <input value={f.description || ''} onChange={e => updateFolder(i, 'description', e.target.value)}
                  placeholder="Description (optional)" className="text-sm" />
              </div>
              {form.folders.length > 1 && (
                <button type="button" className="p-2 text-gray-400 hover:text-red-600" onClick={() => removeFolder(i)}>
                  <TrashIcon className="w-4 h-4" />
                </button>
              )}
            </div>
          ))}
        </div>
      </div>

      <div className="flex gap-3 justify-end pt-4 border-t">
        <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{submitLabel}</button>
      </div>
    </form>
  )
}
