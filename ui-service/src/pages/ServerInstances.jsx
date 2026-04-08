import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getServerInstances, createServerInstance, updateServerInstance, deleteServerInstance } from '../api/accounts'
import { getFolderTemplates } from '../api/config'
import { getPlatformSettings } from '../api/platformSettings'
import { useServices } from '../context/ServiceContext'
import SecurityTierSelector from '../components/SecurityTierSelector'
import ProtocolSecurityConfig from '../components/ProtocolSecurityConfig'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilIcon, ServerStackIcon, SignalIcon, SignalSlashIcon,
  FolderIcon, CircleStackIcon, LockClosedIcon
} from '@heroicons/react/24/outline'
import { useState, useEffect } from 'react'

const PROTOCOLS = ['SFTP', 'FTP', 'FTP_WEB', 'HTTPS']

const DEFAULT_PORTS = {
  SFTP: 2222,
  FTP: 21,
  FTP_WEB: 8083,
  HTTPS: 443
}

const PROTOCOL_LABELS = {
  SFTP: 'SFTP',
  FTP: 'FTP',
  FTP_WEB: 'FTP-Web (HTTP/S)',
  HTTPS: 'HTTPS'
}

const SECURITY_BADGES = {
  RULES: { badge: 'badge-yellow', label: 'Rules' },
  AI: { badge: 'badge-blue', label: 'AI' },
  AI_LLM: { badge: 'badge-purple', label: 'AI+LLM' },
}

const STORAGE_MODES = [
  { value: 'PHYSICAL', label: 'Physical Storage', desc: 'Traditional filesystem — files stored directly on disk at home directory paths', icon: FolderIcon, color: 'blue' },
  { value: 'VIRTUAL', label: 'Virtual File System (VFS)', desc: 'Phantom folders — zero-cost provisioning, content-addressed storage, inline small files', icon: CircleStackIcon, color: 'purple' },
]

const emptyForm = {
  instanceId: '', protocol: 'SFTP', name: '', description: '',
  internalHost: '', internalPort: 2222,
  externalHost: '', externalPort: '',
  useProxy: false, proxyHost: '', proxyPort: '',
  proxyQos: { enabled: false, maxBytesPerSecond: '', perConnectionMaxBytesPerSecond: '', priority: 5, burstAllowancePercent: 20 },
  maxConnections: 500,
  folderTemplateId: '',
  clearFolderTemplate: false,
  defaultStorageMode: 'PHYSICAL',
  securityTier: 'AI', securityPolicy: {},
  protocolCredentials: {}
}

export default function ServerInstances() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editServer, setEditServer] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [protocolFilter, setProtocolFilter] = useState('ALL')

  const { data: servers = [], isLoading } = useQuery({ queryKey: ['server-instances'], queryFn: getServerInstances })

  const createMut = useMutation({
    mutationFn: createServerInstance,
    onSuccess: () => { qc.invalidateQueries(['server-instances']); setShowCreate(false); setForm(emptyForm); toast.success('Server instance created') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create server')
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateServerInstance(id, data),
    onSuccess: () => { qc.invalidateQueries(['server-instances']); setEditServer(null); toast.success('Server updated') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update server')
  })

  const deleteMut = useMutation({
    mutationFn: deleteServerInstance,
    onSuccess: () => { qc.invalidateQueries(['server-instances']); toast.success('Server deactivated') }
  })

  const toggleMut = useMutation({
    mutationFn: ({ id, active }) => updateServerInstance(id, { active }),
    onSuccess: () => { qc.invalidateQueries(['server-instances']); toast.success('Status updated') }
  })

  const openEdit = (s) => {
    setEditServer(s)
    setForm({
      instanceId: s.instanceId, protocol: s.protocol, name: s.name, description: s.description || '',
      internalHost: s.internalHost, internalPort: s.internalPort,
      externalHost: s.externalHost || '', externalPort: s.externalPort || '',
      useProxy: s.useProxy, proxyHost: s.proxyHost || '', proxyPort: s.proxyPort || '',
      proxyQos: {
        enabled: s.proxyQosEnabled || false,
        maxBytesPerSecond: s.proxyQosMaxBytesPerSecond || '',
        perConnectionMaxBytesPerSecond: s.proxyQosPerConnectionMaxBytesPerSecond || '',
        priority: s.proxyQosPriority || 5,
        burstAllowancePercent: s.proxyQosBurstAllowancePercent || 20,
      },
      maxConnections: s.maxConnections,
      folderTemplateId: s.folderTemplateId || '',
      defaultStorageMode: s.defaultStorageMode || 'PHYSICAL',
      securityTier: s.securityTier || 'AI',
      securityPolicy: s.securityPolicy || {},
      protocolCredentials: s.protocolCredentials || {}
    })
  }

  const filtered = protocolFilter === 'ALL'
    ? servers
    : servers.filter(s => s.protocol === protocolFilter)

  const protocolCounts = PROTOCOLS.reduce((acc, p) => {
    acc[p] = servers.filter(s => s.protocol === p).length
    return acc
  }, {})

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Server Instances</h1>
          <p className="text-gray-500 text-sm">Manage server instances across all protocols and assign users to specific servers</p>
        </div>
        <button className="btn-primary" onClick={() => { setForm(emptyForm); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> Add Server
        </button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-5 gap-4">
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Total Servers</p>
          <p className="text-2xl font-bold text-gray-900 mt-1">{servers.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Active</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{servers.filter(s => s.active).length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">Physical Storage</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{servers.filter(s => !s.defaultStorageMode || s.defaultStorageMode === 'PHYSICAL').length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">VFS (Virtual)</p>
          <p className="text-2xl font-bold text-purple-600 mt-1">{servers.filter(s => s.defaultStorageMode === 'VIRTUAL').length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wider">With Proxy</p>
          <p className="text-2xl font-bold text-amber-600 mt-1">{servers.filter(s => s.useProxy).length}</p>
        </div>
      </div>

      {/* Protocol filter tabs */}
      <div className="flex gap-2">
        <button
          onClick={() => setProtocolFilter('ALL')}
          className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
            protocolFilter === 'ALL' ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}>
          All ({servers.length})
        </button>
        {PROTOCOLS.map(p => (
          protocolCounts[p] > 0 || protocolFilter === p ? (
            <button key={p}
              onClick={() => setProtocolFilter(p)}
              className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
                protocolFilter === p ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {PROTOCOL_LABELS[p]} ({protocolCounts[p] || 0})
            </button>
          ) : null
        ))}
      </div>

      {/* Server list */}
      {filtered.length === 0 ? (
        <div className="card"><EmptyState title="No server instances found" description="Add your first server instance to get started." /></div>
      ) : (
        <div className="card">
          <table className="w-full">
            <thead>
              <tr className="border-b border-gray-100">
                <th className="table-header">Instance</th>
                <th className="table-header">Protocol</th>
                <th className="table-header">Name</th>
                <th className="table-header">Storage</th>
                <th className="table-header">Internal</th>
                <th className="table-header">Client Connection</th>
                <th className="table-header">Security</th>
                <th className="table-header">Status</th>
                <th className="table-header">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(s => {
                const tierInfo = s.securityTier
                  ? SECURITY_BADGES[s.securityTier] || SECURITY_BADGES.RULES
                  : SECURITY_BADGES.RULES
                const isVFS = s.defaultStorageMode === 'VIRTUAL'
                return (
                  <tr key={s.id} className="table-row">
                    <td className="table-cell">
                      <div className="flex items-center gap-2">
                        <ServerStackIcon className="w-4 h-4 text-blue-500" />
                        <span className="font-mono text-xs font-medium">{s.instanceId}</span>
                      </div>
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${
                        s.protocol === 'SFTP' ? 'badge-blue' :
                        s.protocol === 'FTP' ? 'badge-green' :
                        s.protocol === 'FTP_WEB' ? 'badge-purple' : 'badge-yellow'
                      }`}>{PROTOCOL_LABELS[s.protocol] || s.protocol}</span>
                    </td>
                    <td className="table-cell">
                      <div>
                        <p className="font-medium text-gray-900">{s.name}</p>
                        {s.description && <p className="text-xs text-gray-400">{s.description}</p>}
                      </div>
                    </td>
                    <td className="table-cell">
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${
                        isVFS ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'
                      }`}>
                        {isVFS ? <><CircleStackIcon className="w-3 h-3" /> VFS</> : <><FolderIcon className="w-3 h-3" /> Physical</>}
                      </span>
                    </td>
                    <td className="table-cell font-mono text-xs text-gray-500">{s.internalHost}:{s.internalPort}</td>
                    <td className="table-cell font-mono text-xs text-gray-700">
                      {s.clientHost}:{s.clientPort}
                      {s.useProxy && (
                        <span className="ml-1 text-[10px] text-blue-500">(proxy)</span>
                      )}
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${tierInfo.badge}`}>{tierInfo.label}</span>
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
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Create Modal */}
      {showCreate && (
        <Modal title="Add Server Instance" onClose={() => setShowCreate(false)} size="xl">
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
        <Modal title={`Edit: ${editServer.name}`} onClose={() => setEditServer(null)} size="xl">
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
  const { services } = useServices() || { services: {} }
  const dmzRunning = services?.dmz !== false

  // Check if LLM is enabled in platform settings
  const { data: aiSettings = [] } = useQuery({
    queryKey: ['platform-settings-ai'],
    queryFn: () => getPlatformSettings({ category: 'AI' })
  })
  const llmEnabled = aiSettings.some(s => s.settingKey === 'ai.llm.enabled' && s.settingValue === 'true')

  const { data: folderTemplates = [] } = useQuery({
    queryKey: ['folder-templates-picker'],
    queryFn: getFolderTemplates,
    staleTime: 300_000
  })

  const f = (key, val) => setForm(prev => ({ ...prev, [key]: val }))

  const handleProtocolChange = (protocol) => {
    f('protocol', protocol)
    f('internalPort', DEFAULT_PORTS[protocol] || 2222)
  }

  // Auto-suggest DMZ proxy when proxy is enabled and DMZ is running
  useEffect(() => {
    if (form.useProxy && dmzRunning && !form.proxyHost) {
      setForm(prev => ({
        ...prev,
        proxyHost: prev.proxyHost || 'dmz-proxy',
        proxyPort: prev.proxyPort || 8088
      }))
    }
  }, [form.useProxy, dmzRunning])

  return (
    <form onSubmit={e => { e.preventDefault(); onSubmit() }} className="space-y-4 max-h-[75vh] overflow-y-auto pr-1">
      {/* Protocol selector */}
      <div>
        <label>Protocol</label>
        <select value={form.protocol} onChange={e => handleProtocolChange(e.target.value)}>
          {PROTOCOLS.map(p => <option key={p} value={p}>{PROTOCOL_LABELS[p]}</option>)}
        </select>
      </div>

      {showInstanceId && (
        <div>
          <label>Instance ID</label>
          <input value={form.instanceId} onChange={e => f('instanceId', e.target.value)} required
            placeholder={`${form.protocol.toLowerCase()}-3`} pattern="[a-z0-9\-]+" title="Lowercase letters, numbers, hyphens" />
          <p className="text-xs text-gray-400 mt-1">Unique identifier (e.g., sftp-3, ftp-eu-west, ftpweb-2)</p>
        </div>
      )}
      <div className="grid grid-cols-2 gap-4">
        <div><label>Name</label><input value={form.name} onChange={e => f('name', e.target.value)} required placeholder="EU West Server" /></div>
        <div><label>Max Connections</label><input type="number" value={form.maxConnections} onChange={e => f('maxConnections', parseInt(e.target.value))} /></div>
      </div>
      <div><label>Description</label><input value={form.description} onChange={e => f('description', e.target.value)} placeholder="Production server for EU region" /></div>

      {/* ============ Storage Mode ============ */}
      <div className="pt-2">
        <p className="text-xs text-gray-500 uppercase tracking-wider font-semibold mb-2">Storage Mode</p>
        <div className="grid grid-cols-2 gap-3">
          {STORAGE_MODES.map(mode => {
            const Icon = mode.icon
            const selected = form.defaultStorageMode === mode.value
            return (
              <button key={mode.value} type="button"
                onClick={() => f('defaultStorageMode', mode.value)}
                className={`p-4 rounded-xl border-2 text-left transition-all ${
                  selected
                    ? mode.color === 'purple' ? 'border-purple-500 bg-purple-50' : 'border-blue-500 bg-blue-50'
                    : 'border-gray-200 hover:border-gray-300'
                }`}>
                <div className="flex items-center gap-2 mb-1">
                  <Icon className={`w-5 h-5 ${selected ? (mode.color === 'purple' ? 'text-purple-600' : 'text-blue-600') : 'text-gray-400'}`} />
                  <span className={`font-semibold text-sm ${selected ? (mode.color === 'purple' ? 'text-purple-700' : 'text-blue-700') : 'text-gray-700'}`}>
                    {mode.label}
                  </span>
                </div>
                <p className="text-xs text-gray-500">{mode.desc}</p>
              </button>
            )
          })}
        </div>
        {form.defaultStorageMode === 'VIRTUAL' && (
          <div className="mt-2 bg-purple-50 border border-purple-100 rounded-lg p-2.5 text-xs text-purple-700">
            <span className="font-medium">VFS Mode:</span> Accounts on this server will use phantom folders with content-addressed storage.
            Small files (&lt;64KB) stored inline in DB for sub-millisecond access. No physical disk provisioning needed.
          </div>
        )}
      </div>

      {/* ============ Folder Template ============ */}
      <div className="pt-2">
        <p className="text-xs text-gray-500 uppercase tracking-wider font-semibold mb-1">Folder Template</p>
        <p className="text-xs text-gray-400 mb-3">
          Directory structure users see when connecting — inbox, outbox, archive, etc.
        </p>

        {folderTemplates.length > 0 ? (
          <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
            {/* None option */}
            <button type="button"
              onClick={() => { f('folderTemplateId', null); f('clearFolderTemplate', true) }}
              className={`w-full text-left p-3 rounded-lg border-2 transition-all ${
                !form.folderTemplateId ? 'border-gray-400 bg-gray-50' : 'border-gray-200 hover:border-gray-300'
              }`}>
              <p className={`text-sm font-medium ${!form.folderTemplateId ? 'text-gray-900' : 'text-gray-500'}`}>
                No folder template
              </p>
              <p className="text-xs text-gray-400">Accounts use a flat home directory with no predefined structure</p>
            </button>

            {/* Template cards */}
            {folderTemplates.map(t => {
              const isSelected = form.folderTemplateId === t.id
              return (
                <button key={t.id} type="button"
                  onClick={() => { f('folderTemplateId', t.id); f('clearFolderTemplate', false) }}
                  className={`w-full text-left p-3 rounded-lg border-2 transition-all ${
                    isSelected ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-200' : 'border-gray-200 hover:border-gray-300'
                  }`}>
                  <div className="flex items-center gap-2 mb-1">
                    <p className={`text-sm font-medium ${isSelected ? 'text-blue-900' : 'text-gray-900'}`}>{t.name}</p>
                    {t.builtIn && (
                      <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-blue-100 text-blue-600">
                        <LockClosedIcon className="w-2.5 h-2.5" /> Built-in
                      </span>
                    )}
                    <span className="text-[10px] text-gray-400 ml-auto">{t.folders.length} folder{t.folders.length !== 1 ? 's' : ''}</span>
                  </div>
                  {t.description && <p className="text-xs text-gray-500 mb-1.5">{t.description}</p>}
                  <div className="flex flex-wrap gap-1">
                    {t.folders.map((fd, i) => (
                      <span key={i} className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-mono ${
                        isSelected ? 'bg-blue-100/70 text-blue-700' : 'bg-gray-100 text-gray-600'
                      }`}>
                        <FolderIcon className="w-3 h-3 text-yellow-500" /> {fd.path}
                      </span>
                    ))}
                  </div>
                </button>
              )
            })}
          </div>
        ) : (
          <div className="bg-gray-50 rounded-lg p-3 text-xs text-gray-500 border border-gray-200">
            No folder templates available. Create templates on the <span className="font-medium">Folder Templates</span> page first.
          </div>
        )}
      </div>

      {/* Internal connection */}
      <p className="text-xs text-gray-500 uppercase tracking-wider font-semibold pt-2">Internal Connection (Docker/Host)</p>
      <div className="grid grid-cols-2 gap-4">
        <div><label>Host</label><input value={form.internalHost} onChange={e => f('internalHost', e.target.value)} required placeholder={`${form.protocol.toLowerCase()}-service-3`} /></div>
        <div><label>Port</label><input type="number" value={form.internalPort} onChange={e => f('internalPort', parseInt(e.target.value))} /></div>
      </div>

      {/* External connection */}
      <p className="text-xs text-gray-500 uppercase tracking-wider font-semibold pt-2">External Connection (Client-Facing)</p>
      <div className="grid grid-cols-2 gap-4">
        <div><label>External Host</label><input value={form.externalHost} onChange={e => f('externalHost', e.target.value)} placeholder="files.example.com" /></div>
        <div><label>External Port</label><input type="number" value={form.externalPort} onChange={e => f('externalPort', e.target.value ? parseInt(e.target.value) : '')} placeholder={String(DEFAULT_PORTS[form.protocol])} /></div>
      </div>

      {/* Protocol Security — TLS/SSH certificates & keys */}
      <ProtocolSecurityConfig
        protocol={form.protocol}
        credentials={form.protocolCredentials}
        onCredentialsChange={creds => f('protocolCredentials', creds)}
        context="server"
      />

      {/* Reverse proxy */}
      <div className="flex items-center gap-3 pt-2">
        <input type="checkbox" id="useProxy" checked={form.useProxy} onChange={e => f('useProxy', e.target.checked)}
          className="w-4 h-4 text-blue-600 rounded border-gray-300" />
        <label htmlFor="useProxy" className="text-sm font-medium text-gray-700">Use Reverse Proxy</label>
      </div>
      {form.useProxy && (
        <>
          <div className="grid grid-cols-2 gap-4 pl-7">
            <div>
              <label>Proxy Host</label>
              <input value={form.proxyHost} onChange={e => f('proxyHost', e.target.value)} placeholder="proxy.example.com" />
            </div>
            <div>
              <label>Proxy Port</label>
              <input type="number" value={form.proxyPort} onChange={e => f('proxyPort', e.target.value ? parseInt(e.target.value) : '')} placeholder="2222" />
            </div>
          </div>

          {/* Dynamic proxy detection */}
          {dmzRunning && form.proxyHost === 'dmz-proxy' && (
            <div className="pl-7">
              <p className="text-xs text-green-600 flex items-center gap-1">
                <span className="w-2 h-2 rounded-full bg-green-400 inline-block" />
                DMZ Proxy detected and running on port 8088
              </p>
            </div>
          )}
          {!dmzRunning && form.proxyHost === 'dmz-proxy' && (
            <div className="pl-7">
              <p className="text-xs text-amber-600 flex items-center gap-1">
                <span className="w-2 h-2 rounded-full bg-amber-400 inline-block" />
                DMZ Proxy is not running — proxy routing may fail
              </p>
            </div>
          )}

          {/* Proxy QoS Policy */}
          <div className="pl-7 border-t border-gray-100 pt-3 mt-2">
            <div className="flex items-center gap-3 mb-3">
              <input type="checkbox" id="proxyQosEnabled" checked={form.proxyQos?.enabled || false}
                onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, enabled: e.target.checked } }))}
                className="w-4 h-4 text-blue-600 rounded border-gray-300" />
              <label htmlFor="proxyQosEnabled" className="text-sm font-medium text-gray-700">Enable Proxy QoS</label>
            </div>
            {form.proxyQos?.enabled && (
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-gray-500">Max Bandwidth (MB/s)</label>
                  <input type="number" min="0"
                    value={form.proxyQos.maxBytesPerSecond ? Math.round(form.proxyQos.maxBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, maxBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                  <p className="text-xs text-gray-400 mt-0.5">Aggregate for all connections through this mapping</p>
                </div>
                <div>
                  <label className="text-xs text-gray-500">Per-Connection Limit (MB/s)</label>
                  <input type="number" min="0"
                    value={form.proxyQos.perConnectionMaxBytesPerSecond ? Math.round(form.proxyQos.perConnectionMaxBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, perConnectionMaxBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label className="text-xs text-gray-500">Priority (1=Highest, 10=Lowest)</label>
                  <input type="number" min="1" max="10"
                    value={form.proxyQos.priority || 5}
                    onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, priority: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label className="text-xs text-gray-500">Burst Allowance (%)</label>
                  <input type="number" min="0" max="100"
                    value={form.proxyQos.burstAllowancePercent || 20}
                    onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, burstAllowancePercent: Number(e.target.value) } }))} />
                </div>
              </div>
            )}
          </div>

        </>
      )}

      {/* Security Tier — always visible, applies to all server instances */}
      <div className="pt-2">
        <SecurityTierSelector
          tier={form.securityTier}
          onTierChange={tier => f('securityTier', tier)}
          showAiTiers={true}
          policy={form.securityPolicy}
          onPolicyChange={policy => f('securityPolicy', policy)}
          llmEnabled={llmEnabled}
        />
      </div>

      <div className="flex gap-3 justify-end pt-4 border-t">
        <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{submitLabel}</button>
      </div>
    </form>
  )
}
