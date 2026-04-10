import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getFolderTemplates, createFolderTemplate, updateFolderTemplate, deleteFolderTemplate, exportAllFolderTemplates, importFolderTemplates } from '../api/config'
import { getServerInstances, updateServerInstance } from '../api/accounts'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilIcon, ArrowDownTrayIcon, ArrowUpTrayIcon,
  LockClosedIcon, FolderIcon, ServerStackIcon, ArrowRightIcon,
  InformationCircleIcon, ChevronDownIcon, ChevronUpIcon,
} from '@heroicons/react/24/outline'
import { useState, useRef } from 'react'

// ── Protocol badge helpers ────────────────────────────────────────────

const PROTOCOL_LABELS = { SFTP: 'SFTP', FTP: 'FTP', FTP_WEB: 'FTP-Web', HTTPS: 'HTTPS' }

const protocolBadgeClass = (p) => {
  switch (p) {
    case 'SFTP': return 'badge-blue'
    case 'FTP': return 'badge-green'
    case 'FTP_WEB': return 'badge-purple'
    case 'HTTPS': return 'badge-yellow'
    default: return 'badge-gray'
  }
}

// ── Folder purpose labels and colors ──────────────────────────────────

const FOLDER_PURPOSES = [
  { value: 'INBOX', label: 'Inbox', hint: 'Incoming files land here', color: 'text-blue-600' },
  { value: 'OUTBOX', label: 'Outbox', hint: 'Files for partner to pick up', color: 'text-green-600' },
  { value: 'SENT', label: 'Sent', hint: 'Delivered files archived', color: 'text-indigo-600' },
  { value: 'ERROR', label: 'Error', hint: 'Failed transfers quarantined', color: 'text-red-600' },
  { value: 'ARCHIVE', label: 'Archive', hint: 'Long-term storage', color: 'text-amber-600' },
  { value: 'CUSTOM', label: 'Custom', hint: 'Custom folder', color: 'text-secondary' },
]

const purposeFromPath = (path) => {
  const p = (path || '').toLowerCase()
  if (p.includes('inbox') || p.includes('inbound')) return 'INBOX'
  if (p.includes('outbox') || p.includes('outbound')) return 'OUTBOX'
  if (p.includes('sent') || p.includes('deliver')) return 'SENT'
  if (p.includes('error') || p.includes('fail') || p.includes('quarantine')) return 'ERROR'
  if (p.includes('archive') || p.includes('backup')) return 'ARCHIVE'
  return 'CUSTOM'
}

const purposeColor = (path) => {
  const purpose = purposeFromPath(path)
  const found = FOLDER_PURPOSES.find(fp => fp.value === purpose)
  return found?.color || 'text-secondary'
}

const purposeHint = (path) => {
  const purpose = purposeFromPath(path)
  const found = FOLDER_PURPOSES.find(fp => fp.value === purpose)
  return found?.hint || ''
}

// ── Main Component ────────────────────────────────────────────────────

export default function FolderTemplates() {
  const qc = useQueryClient()
  const [tab, setTab] = useState('templates')
  const [showCreate, setShowCreate] = useState(false)
  const [editTemplate, setEditTemplate] = useState(null)
  const [form, setForm] = useState({ name: '', description: '', folders: [{ path: '', description: '' }] })
  const [expandedCards, setExpandedCards] = useState({})
  const fileRef = useRef(null)

  // ── Queries ──

  const { data: templates = [], isLoading } = useQuery({
    queryKey: ['folder-templates'],
    queryFn: getFolderTemplates
  })

  const { data: servers = [], isLoading: loadingServers } = useQuery({
    queryKey: ['server-instances'],
    queryFn: getServerInstances
  })

  // ── Mutations ──

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
    onError: err => toast.error(err.response?.data?.message || 'Cannot delete — template may be in use')
  })

  const assignMut = useMutation({
    mutationFn: ({ id, folderTemplateId }) => updateServerInstance(id, { folderTemplateId, clearFolderTemplate: !folderTemplateId }),
    onSuccess: () => { qc.invalidateQueries(['server-instances']); toast.success('Server template updated') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update server')
  })

  // ── Handlers ──

  const openEdit = (t) => {
    setEditTemplate(t)
    setForm({
      name: t.name,
      description: t.description || '',
      folders: t.folders.length ? t.folders.map(f => ({ ...f })) : [{ path: '', description: '' }]
    })
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
  const moveFolder = (i, dir) => {
    setForm(f => {
      const folders = [...f.folders]
      const target = i + dir
      if (target < 0 || target >= folders.length) return f
      ;[folders[i], folders[target]] = [folders[target], folders[i]]
      return { ...f, folders }
    })
  }

  const toggleCard = (id) => setExpandedCards(prev => ({ ...prev, [id]: !prev[id] }))

  // ── Derived data ──

  const serversByTemplate = {}
  servers.forEach(s => {
    const tId = s.folderTemplateId || '__none__'
    if (!serversByTemplate[tId]) serversByTemplate[tId] = []
    serversByTemplate[tId].push(s)
  })

  const builtIn = templates.filter(t => t.builtIn)
  const custom = templates.filter(t => !t.builtIn)
  const usedTemplateCount = templates.filter(t => (serversByTemplate[t.id] || []).length > 0).length

  if (isLoading) return <LoadingSpinner />

  // ── Render: Templates Tab ──────────────────────────────────────────

  const renderTemplates = () => (
    <div className="space-y-4">
      {/* Summary stats */}
      <div className="grid grid-cols-4 gap-4">
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Total Templates</p>
          <p className="text-2xl font-bold text-primary mt-1">{templates.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Built-in</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{builtIn.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Custom</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{custom.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">In Use</p>
          <p className="text-2xl font-bold text-purple-600 mt-1">{usedTemplateCount}</p>
        </div>
      </div>

      {/* Template cards */}
      {templates.length === 0 ? (
        <EmptyState title="No folder templates" description="Create your first folder template to define reusable directory structures for partner accounts." />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {templates.map(t => {
            const linkedServers = serversByTemplate[t.id] || []
            const isExpanded = expandedCards[t.id]
            return (
              <div key={t.id} className="card p-0 overflow-hidden cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => !t.builtIn && openEdit(t)}>
                {/* Card header */}
                <div className="p-4 pb-3">
                  <div className="flex items-start justify-between">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h3 className="font-semibold text-primary">{t.name}</h3>
                        {t.builtIn && (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700">
                            <LockClosedIcon className="w-3 h-3" /> Built-in
                          </span>
                        )}
                        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${
                          linkedServers.length > 0
                            ? 'bg-green-50 text-green-700'
                            : 'bg-hover text-secondary'
                        }`}>
                          <ServerStackIcon className="w-3 h-3" />
                          {linkedServers.length} server{linkedServers.length !== 1 ? 's' : ''}
                        </span>
                      </div>
                      {t.description && <p className="text-sm text-secondary mt-1">{t.description}</p>}
                    </div>
                    {!t.builtIn && (
                      <div className="flex gap-1 ml-2 flex-shrink-0">
                        <button className="p-1.5 rounded hover:bg-blue-50 text-muted hover:text-blue-600 transition-colors"
                          onClick={(e) => { e.stopPropagation(); openEdit(t) }} title="Edit template">
                          <PencilIcon className="w-4 h-4" />
                        </button>
                        <button className="p-1.5 rounded hover:bg-red-50 text-muted hover:text-red-600 transition-colors"
                          onClick={(e) => { e.stopPropagation(); if (confirm('Delete this template? Servers using it will lose their folder structure assignment.')) deleteMut.mutate(t.id) }}
                          title="Delete template">
                          <TrashIcon className="w-4 h-4" />
                        </button>
                      </div>
                    )}
                  </div>
                </div>

                {/* Folder tree visualization */}
                <div className="px-4 pb-3">
                  <div className="bg-canvas rounded-lg p-3 border border-border">
                    <p className="text-[10px] text-muted uppercase tracking-wider font-semibold mb-2">Folder Structure</p>
                    <div className="space-y-1">
                      {t.folders.map((f, i) => {
                        const hint = f.description || purposeHint(f.path)
                        return (
                          <div key={i} className="flex items-center gap-2 group">
                            <span className="text-gray-300 text-xs font-mono select-none w-4 text-right">{i === t.folders.length - 1 ? '\u2514' : '\u251C'}</span>
                            <FolderIcon className={`w-4 h-4 flex-shrink-0 text-yellow-500`} />
                            <span className={`text-sm font-mono font-medium ${purposeColor(f.path)}`}>/{f.path}</span>
                            {hint && <span className="text-xs text-muted hidden sm:inline">&larr; {hint}</span>}
                          </div>
                        )
                      })}
                    </div>
                  </div>
                </div>

                {/* Linked servers (collapsible) */}
                {linkedServers.length > 0 && (
                  <div className="border-t border-border">
                    <button
                      onClick={() => toggleCard(t.id)}
                      className="w-full px-4 py-2.5 flex items-center justify-between text-xs text-secondary hover:bg-canvas transition-colors"
                    >
                      <span className="font-medium">Servers using this template</span>
                      {isExpanded
                        ? <ChevronUpIcon className="w-3.5 h-3.5" />
                        : <ChevronDownIcon className="w-3.5 h-3.5" />
                      }
                    </button>
                    {isExpanded && (
                      <div className="px-4 pb-3 space-y-1.5">
                        {linkedServers.map(s => (
                          <div key={s.id} className="flex items-center gap-2 px-2.5 py-1.5 rounded bg-canvas border border-border">
                            <ServerStackIcon className="w-3.5 h-3.5 text-muted" />
                            <span className="text-sm font-medium text-primary">{s.name}</span>
                            <span className="font-mono text-[10px] text-muted">{s.instanceId}</span>
                            <span className={`badge ${protocolBadgeClass(s.protocol)} text-[10px]`}>
                              {PROTOCOL_LABELS[s.protocol] || s.protocol}
                            </span>
                            {s.active
                              ? <span className="badge badge-green text-[10px]">Active</span>
                              : <span className="badge badge-gray text-[10px]">Inactive</span>
                            }
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {/* Footer: folder count */}
                <div className="px-4 py-2 bg-canvas border-t border-border flex items-center justify-between">
                  <span className="text-xs text-muted">{t.folders.length} folder{t.folders.length !== 1 ? 's' : ''} defined</span>
                  {linkedServers.length === 0 && (
                    <span className="text-[10px] text-muted italic">Not assigned to any server</span>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )

  // ── Render: Server Assignment Tab ──────────────────────────────────

  const renderServerAssignment = () => (
    <div className="space-y-4">
      <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg bg-amber-50 border border-amber-200">
        <InformationCircleIcon className="w-5 h-5 text-amber-500 flex-shrink-0 mt-0.5" />
        <div>
          <p className="text-sm text-amber-800 font-medium">Changing a template only affects new accounts</p>
          <p className="text-xs text-amber-600">Existing accounts keep their current folder structure. Only accounts created after the change will use the new template.</p>
        </div>
      </div>

      {loadingServers ? <LoadingSpinner /> : servers.length === 0 ? (
        <EmptyState title="No servers configured" description="Create server instances first, then assign folder templates here." />
      ) : (
        <div className="card overflow-hidden p-0">
          <table className="w-full">
            <thead>
              <tr className="border-b">
                <th className="table-header">Server</th>
                <th className="table-header">Protocol</th>
                <th className="table-header">Status</th>
                <th className="table-header">Current Template</th>
                <th className="table-header">Folders Provisioned</th>
              </tr>
            </thead>
            <tbody>
              {servers.map(s => {
                const currentTemplate = templates.find(t => t.id === s.folderTemplateId)
                return (
                  <tr key={s.id} className="table-row">
                    <td className="table-cell">
                      <div className="flex items-center gap-2">
                        <ServerStackIcon className="w-4 h-4 text-blue-500" />
                        <div>
                          <p className="font-medium text-primary">{s.name}</p>
                          <p className="text-xs text-muted font-mono">{s.instanceId}</p>
                        </div>
                      </div>
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${protocolBadgeClass(s.protocol)}`}>
                        {PROTOCOL_LABELS[s.protocol] || s.protocol}
                      </span>
                    </td>
                    <td className="table-cell">
                      {s.active
                        ? <span className="badge badge-green">Active</span>
                        : <span className="badge badge-gray">Inactive</span>
                      }
                    </td>
                    <td className="table-cell">
                      <select
                        className="input w-52"
                        value={s.folderTemplateId || ''}
                        onChange={e => assignMut.mutate({
                          id: s.id,
                          folderTemplateId: e.target.value || null
                        })}
                      >
                        <option value="">-- No template --</option>
                        {templates.map(t => (
                          <option key={t.id} value={t.id}>{t.name}{t.builtIn ? ' (built-in)' : ''}</option>
                        ))}
                      </select>
                    </td>
                    <td className="table-cell">
                      {currentTemplate ? (
                        <div className="flex flex-wrap gap-1">
                          {currentTemplate.folders.map((f, i) => (
                            <span key={i} className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-mono bg-hover text-secondary">
                              <FolderIcon className="w-3 h-3 text-yellow-500" /> {f.path}
                            </span>
                          ))}
                        </div>
                      ) : (
                        <span className="text-xs text-muted italic">Flat home directory (no structure)</span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )

  // ── Render: How It Works Tab ───────────────────────────────────────

  const renderHowItWorks = () => {
    // Pick the first template that has folders for the example
    const exampleTemplate = templates.find(t => t.folders.length > 0) || { name: 'Standard Layout', folders: [{ path: 'inbox' }, { path: 'outbox' }, { path: 'sent' }] }
    const exampleServer = servers.find(s => s.folderTemplateId) || { name: 'SFTP Server 1', protocol: 'SFTP' }

    return (
      <div className="space-y-6">
        {/* Step-by-step flow */}
        <div className="card p-6">
          <h3 className="text-sm font-semibold text-primary mb-4">Automatic Folder Provisioning Flow</h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Step 1: Template */}
            <div className="rounded-xl border-2 border-blue-200 bg-blue-50 p-4">
              <div className="flex items-center gap-2 mb-3">
                <span className="w-6 h-6 rounded-full bg-blue-600 text-white text-xs font-bold flex items-center justify-center">1</span>
                <span className="text-sm font-semibold text-blue-900">Define Template</span>
              </div>
              <div className="bg-surface rounded-lg p-3 border border-blue-100">
                <p className="text-xs font-semibold text-primary mb-2">"{exampleTemplate.name}"</p>
                <div className="space-y-1">
                  {exampleTemplate.folders.slice(0, 4).map((f, i) => (
                    <div key={i} className="flex items-center gap-1.5">
                      <FolderIcon className="w-3.5 h-3.5 text-yellow-500" />
                      <span className="text-xs font-mono text-secondary">/{f.path}</span>
                    </div>
                  ))}
                  {exampleTemplate.folders.length > 4 && (
                    <p className="text-[10px] text-muted">+{exampleTemplate.folders.length - 4} more...</p>
                  )}
                </div>
              </div>
            </div>

            {/* Arrow */}
            <div className="hidden md:flex items-center justify-center">
              <div className="flex flex-col items-center gap-2">
                <div className="flex items-center gap-1">
                  <div className="h-0.5 w-12 bg-gray-300"></div>
                  <ArrowRightIcon className="w-5 h-5 text-muted" />
                </div>
                <span className="text-[10px] text-muted font-medium">Assigned to</span>
              </div>
            </div>
            {/* Mobile arrow */}
            <div className="flex md:hidden items-center justify-center py-1">
              <div className="w-0.5 h-6 bg-gray-300"></div>
            </div>

            {/* Step 2: Server */}
            <div className="rounded-xl border-2 border-purple-200 bg-purple-50 p-4">
              <div className="flex items-center gap-2 mb-3">
                <span className="w-6 h-6 rounded-full bg-purple-600 text-white text-xs font-bold flex items-center justify-center">2</span>
                <span className="text-sm font-semibold text-purple-900">Assign to Server</span>
              </div>
              <div className="bg-surface rounded-lg p-3 border border-purple-100">
                <div className="flex items-center gap-2 mb-2">
                  <ServerStackIcon className="w-4 h-4 text-purple-500" />
                  <span className="text-xs font-semibold text-primary">{exampleServer.name}</span>
                  <span className={`badge ${protocolBadgeClass(exampleServer.protocol)} text-[10px]`}>
                    {PROTOCOL_LABELS[exampleServer.protocol] || exampleServer.protocol}
                  </span>
                </div>
                <p className="text-[10px] text-muted">All new accounts on this server get the template folders</p>
              </div>
            </div>
          </div>

          {/* Step 3: Result (full width) */}
          <div className="mt-4 flex items-center justify-center py-1">
            <div className="w-0.5 h-6 bg-gray-300"></div>
          </div>
          <div className="rounded-xl border-2 border-green-200 bg-green-50 p-4">
            <div className="flex items-center gap-2 mb-3">
              <span className="w-6 h-6 rounded-full bg-green-600 text-white text-xs font-bold flex items-center justify-center">3</span>
              <span className="text-sm font-semibold text-green-900">New Account Created</span>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="bg-surface rounded-lg p-3 border border-green-100">
                <p className="text-xs font-semibold text-primary mb-2">Account: "acme-sftp"</p>
                <p className="text-[10px] text-muted mb-2">Folders auto-provisioned at home directory:</p>
                <div className="space-y-1 font-mono text-xs">
                  {exampleTemplate.folders.slice(0, 5).map((f, i) => (
                    <div key={i} className="flex items-center gap-1.5 text-green-700">
                      <FolderIcon className="w-3.5 h-3.5 text-yellow-500" />
                      /data/partners/acme/{f.path}
                    </div>
                  ))}
                </div>
              </div>
              <div className="bg-surface rounded-lg p-3 border border-green-100">
                <p className="text-xs font-semibold text-primary mb-2">Account: "globex-sftp"</p>
                <p className="text-[10px] text-muted mb-2">Same template, different partner:</p>
                <div className="space-y-1 font-mono text-xs">
                  {exampleTemplate.folders.slice(0, 5).map((f, i) => (
                    <div key={i} className="flex items-center gap-1.5 text-green-700">
                      <FolderIcon className="w-3.5 h-3.5 text-yellow-500" />
                      /data/partners/globex/{f.path}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Key concepts */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="card p-4">
            <h4 className="text-sm font-semibold text-primary mb-2">Consistent Structure</h4>
            <p className="text-xs text-secondary">Every partner gets identical folder layouts. No manual setup, no drift between accounts.</p>
          </div>
          <div className="card p-4">
            <h4 className="text-sm font-semibold text-primary mb-2">Per-Server Control</h4>
            <p className="text-xs text-secondary">Different servers can use different templates. Your EDI server may need different folders than your general SFTP server.</p>
          </div>
          <div className="card p-4">
            <h4 className="text-sm font-semibold text-primary mb-2">Non-Destructive Changes</h4>
            <p className="text-xs text-secondary">Changing a server's template only affects future accounts. Existing accounts keep their current folders intact.</p>
          </div>
        </div>
      </div>
    )
  }

  // ── Main Render ────────────────────────────────────────────────────

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary flex items-center gap-2">
            <FolderIcon className="w-7 h-7 text-indigo-600" />
            Folder Templates
          </h1>
          <p className="text-secondary text-sm mt-1">
            Define reusable folder structures that auto-provision when new partner accounts are created.
            Assign a template to a server — every account on that server gets these folders automatically.
          </p>
        </div>
        <div className="flex gap-2 flex-shrink-0">
          <button className="btn-secondary" onClick={handleExport}>
            <ArrowDownTrayIcon className="w-4 h-4" /> Export
          </button>
          <button className="btn-secondary" onClick={() => fileRef.current?.click()}>
            <ArrowUpTrayIcon className="w-4 h-4" /> Import
          </button>
          <input ref={fileRef} type="file" accept=".json" className="hidden" onChange={handleImport} />
          <button className="btn-primary" onClick={() => { setForm({ name: '', description: '', folders: [{ path: '', description: '' }] }); setShowCreate(true) }}>
            <PlusIcon className="w-4 h-4" /> New Template
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b">
        <button
          onClick={() => setTab('templates')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            tab === 'templates' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'
          }`}
        >
          Templates
          <span className="ml-1.5 text-xs bg-hover text-secondary px-1.5 py-0.5 rounded-full">{templates.length}</span>
        </button>
        <button
          onClick={() => setTab('servers')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            tab === 'servers' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'
          }`}
        >
          Server Assignment
          <span className="ml-1.5 text-xs bg-hover text-secondary px-1.5 py-0.5 rounded-full">{servers.length}</span>
        </button>
        <button
          onClick={() => setTab('how')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors flex items-center gap-1.5 ${
            tab === 'how' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'
          }`}
        >
          <InformationCircleIcon className="w-4 h-4" />
          How It Works
        </button>
      </div>

      {/* Tab Content */}
      {tab === 'templates' && renderTemplates()}
      {tab === 'servers' && renderServerAssignment()}
      {tab === 'how' && renderHowItWorks()}

      {/* ── Create Template Modal ── */}
      {showCreate && (
        <Modal onClose={() => setShowCreate(false)} title="Create Folder Template">
          <TemplateForm
            form={form} setForm={setForm} onSubmit={handleSubmit}
            addFolder={addFolder} removeFolder={removeFolder} updateFolder={updateFolder} moveFolder={moveFolder}
            onCancel={() => setShowCreate(false)} submitLabel="Create" isPending={createMut.isPending}
          />
        </Modal>
      )}

      {/* ── Edit Template Modal ── */}
      {editTemplate && (
        <Modal onClose={() => setEditTemplate(null)} title="Edit Folder Template">
          <TemplateForm
            form={form} setForm={setForm} onSubmit={handleSubmit}
            addFolder={addFolder} removeFolder={removeFolder} updateFolder={updateFolder} moveFolder={moveFolder}
            onCancel={() => setEditTemplate(null)} submitLabel="Save" isPending={updateMut.isPending}
          />
        </Modal>
      )}
    </div>
  )
}

// ── Template Editor Form ──────────────────────────────────────────────

function TemplateForm({ form, setForm, onSubmit, addFolder, removeFolder, updateFolder, moveFolder, onCancel, submitLabel, isPending }) {
  return (
    <form onSubmit={onSubmit} className="space-y-5">
      {/* Basic info */}
      <div>
        <h4 className="text-sm font-semibold text-primary mb-2 border-b pb-1">Basic Information</h4>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">Template Name *</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} required
              placeholder="e.g. Standard Partner Layout" className="w-full" />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">Description</label>
            <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              placeholder="What is this template for?" className="w-full" />
          </div>
        </div>
      </div>

      {/* Folder builder */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <h4 className="text-sm font-semibold text-primary border-b pb-1">Folder Structure</h4>
          <button type="button" className="text-xs text-blue-600 hover:text-blue-800 font-medium" onClick={addFolder}>
            + Add Folder
          </button>
        </div>

        <div className="space-y-2">
          {form.folders.map((f, i) => (
            <div key={i} className="flex gap-2 items-start p-2.5 rounded-lg bg-canvas border border-border">
              {/* Reorder buttons */}
              <div className="flex flex-col gap-0.5 pt-1">
                <button type="button"
                  className="p-0.5 text-gray-300 hover:text-secondary transition-colors disabled:opacity-30"
                  onClick={() => moveFolder(i, -1)} disabled={i === 0}>
                  <ChevronUpIcon className="w-3.5 h-3.5" />
                </button>
                <button type="button"
                  className="p-0.5 text-gray-300 hover:text-secondary transition-colors disabled:opacity-30"
                  onClick={() => moveFolder(i, 1)} disabled={i === form.folders.length - 1}>
                  <ChevronDownIcon className="w-3.5 h-3.5" />
                </button>
              </div>

              {/* Folder icon */}
              <FolderIcon className="w-5 h-5 text-yellow-500 mt-2 flex-shrink-0" />

              {/* Path input */}
              <div className="flex-1">
                <input value={f.path} onChange={e => updateFolder(i, 'path', e.target.value)}
                  placeholder="e.g. inbox, edi/inbound" className="font-mono text-sm w-full" required />
              </div>

              {/* Description input */}
              <div className="flex-1">
                <input value={f.description || ''} onChange={e => updateFolder(i, 'description', e.target.value)}
                  placeholder="Purpose (e.g. Incoming files)" className="text-sm w-full" />
              </div>

              {/* Remove button */}
              {form.folders.length > 1 && (
                <button type="button" className="p-1.5 text-gray-300 hover:text-red-500 transition-colors mt-1"
                  onClick={() => removeFolder(i)} title="Remove folder">
                  <TrashIcon className="w-4 h-4" />
                </button>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Live preview */}
      {form.folders.some(f => f.path.trim()) && (
        <div>
          <h4 className="text-sm font-semibold text-primary mb-2 border-b pb-1">Preview</h4>
          <div className="bg-gray-900 rounded-lg p-3">
            <p className="text-[10px] text-secondary font-mono mb-2">$ tree /data/partners/acme/</p>
            {form.folders.filter(f => f.path.trim()).map((f, i, arr) => (
              <div key={i} className="flex items-center gap-2 font-mono text-sm">
                <span className="text-secondary select-none">{i === arr.length - 1 ? '\u2514\u2500\u2500' : '\u251C\u2500\u2500'}</span>
                <FolderIcon className="w-3.5 h-3.5 text-yellow-400" />
                <span className="text-green-400">{f.path}</span>
                {f.description && <span className="text-secondary text-xs ml-2"># {f.description}</span>}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Actions */}
      <div className="flex gap-3 justify-end pt-4 border-t">
        <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>
          {isPending ? 'Saving...' : submitLabel}
        </button>
      </div>
    </form>
  )
}
