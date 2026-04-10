import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getServerInstances, createServerInstance, updateServerInstance, deleteServerInstance } from '../api/accounts'
import {
  getServerAccounts, revokeServerAccess,
  updateServerAssignment, toggleMaintenance,
} from '../api/servers'
import { getFolderTemplates } from '../api/config'
import { getPlatformSettings } from '../api/platformSettings'
import { getProxyGroups } from '../api/proxyGroups'
import { configApi } from '../api/client'
import { useServices } from '../context/ServiceContext'
import SecurityTierSelector from '../components/SecurityTierSelector'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import { friendlyError } from '../components/FormField'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilIcon, ServerStackIcon, SignalIcon, SignalSlashIcon,
  FolderIcon, CircleStackIcon, LockClosedIcon, UsersIcon,
  WrenchScrewdriverIcon, ArrowPathIcon, XMarkIcon, CheckIcon,
  ChevronDownIcon, ChevronUpIcon,
} from '@heroicons/react/24/outline'
import { useState, useEffect } from 'react'

const PROTOCOLS = ['SFTP', 'FTP', 'FTP_WEB', 'HTTPS', 'AS2', 'AS4']

const DEFAULT_PORTS = {
  SFTP: 2222,
  FTP: 21,
  FTP_WEB: 8083,
  HTTPS: 443,
  AS2: 8094,
  AS4: 8094,
}

const PROTOCOL_LABELS = {
  SFTP: 'SFTP',
  FTP: 'FTP',
  FTP_WEB: 'FTP-Web (HTTP/S)',
  HTTPS: 'HTTPS',
  AS2: 'AS2',
  AS4: 'AS4',
}

const SECURITY_BADGES = {
  RULES: { badge: 'badge-yellow', label: 'Rules' },
  AI: { badge: 'badge-blue', label: 'AI' },
  AI_LLM: { badge: 'badge-purple', label: 'AI+LLM' },
}

const STORAGE_MODES = [
  { value: 'PHYSICAL', label: 'Physical Storage', desc: 'Traditional filesystem -- files stored directly on disk at home directory paths', icon: FolderIcon, color: 'blue' },
  { value: 'VIRTUAL', label: 'Virtual File System (VFS)', desc: 'Phantom folders -- zero-cost provisioning, content-addressed storage, inline small files', icon: CircleStackIcon, color: 'purple' },
]

const isSshProtocol = (p) => p === 'SFTP'

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
  complianceProfileId: '',
  securityTier: 'RULES',
  // SSH / session
  sshBannerMessage: '',
  maxAuthAttempts: 3,
  idleTimeoutSeconds: 300,
  sessionMaxDurationSeconds: 86400,
  allowedCiphers: '',
  allowedMacs: '',
  allowedKex: '',
  // Proxy
  proxyGroupName: '',
  // Maintenance
  maintenanceMode: false,
  maintenanceMessage: '',
  active: true,
}

// ── Section wrapper ──────────────────────────────────────────────────────────

function FormSection({ title, subtitle, children, defaultOpen = true }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div style={{ border: '1px solid rgb(var(--border, 229 231 235))', borderRadius: '0.75rem', overflow: 'hidden' }}>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between px-4 py-3 text-left transition-colors"
        style={{ background: 'rgb(var(--hover, 249 250 251))' }}
      >
        <div>
          <p className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary, 17 24 39))' }}>{title}</p>
          {subtitle && <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted, 156 163 175))' }}>{subtitle}</p>}
        </div>
        {open
          ? <ChevronUpIcon className="w-4 h-4 flex-shrink-0" style={{ color: 'rgb(var(--tx-muted, 156 163 175))' }} />
          : <ChevronDownIcon className="w-4 h-4 flex-shrink-0" style={{ color: 'rgb(var(--tx-muted, 156 163 175))' }} />
        }
      </button>
      <div
        className="transition-all duration-200 ease-in-out"
        style={{
          maxHeight: open ? '2000px' : '0',
          opacity: open ? 1 : 0,
          overflow: 'hidden',
        }}
      >
        <div className="px-4 py-4 space-y-4" style={{ borderTop: '1px solid rgb(var(--border, 229 231 235))' }}>
          {children}
        </div>
      </div>
    </div>
  )
}

// ── AccountsPanel ──────────────────────────────────────────────────────────────

function AccountsPanel({ server, onClose }) {
  const qc = useQueryClient()
  const [assignUsername, setAssignUsername] = useState('')
  const [editAssignment, setEditAssignment] = useState(null)

  const { data: assignments = [], isLoading } = useQuery({
    queryKey: ['server-accounts', server.id],
    queryFn: () => getServerAccounts(server.id),
    refetchInterval: 15000,
  })

  const revokeMut = useMutation({
    mutationFn: ({ accountId }) => revokeServerAccess(server.id, accountId),
    onSuccess: () => { qc.invalidateQueries(['server-accounts', server.id]); toast.success('Access revoked') },
    onError: () => toast.error('Revoke failed'),
  })

  const toggleEnabled = useMutation({
    mutationFn: ({ accountId, enabled }) => updateServerAssignment(server.id, accountId, { enabled }),
    onSuccess: () => { qc.invalidateQueries(['server-accounts', server.id]) },
  })

  return (
    <Modal
      title={`Accounts — ${server.name}`}
      size="lg"
      onClose={onClose}
    >
      <div className="space-y-4">
        {/* Header info */}
        <div className="flex items-center gap-3 px-3 py-2 rounded-lg text-xs"
          style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-secondary))' }}>
          <ServerStackIcon className="w-4 h-4 flex-shrink-0" style={{ color: 'rgb(var(--accent))' }} />
          <span>
            <strong>{server.instanceId}</strong> · {server.clientHost}:{server.clientPort}
            {server.proxyGroupName && <> · Group: <strong>{server.proxyGroupName}</strong></>}
            {server.maintenanceMode && <span className="ml-2 font-bold text-yellow-600">MAINTENANCE</span>}
          </span>
        </div>

        {/* Assigned accounts */}
        {isLoading ? (
          <div className="py-6 text-center text-sm" style={{ color: 'rgb(var(--tx-muted))' }}>Loading...</div>
        ) : assignments.length === 0 ? (
          <div className="py-8 text-center">
            <UsersIcon className="w-10 h-10 mx-auto mb-2" style={{ color: 'rgb(var(--tx-muted))' }} />
            <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>No accounts assigned to this server</p>
          </div>
        ) : (
          <div className="space-y-2 max-h-72 overflow-y-auto">
            {assignments.map(a => (
              <div key={a.id}
                className="flex items-center gap-3 px-3 py-2.5 rounded-lg"
                style={{
                  background: a.enabled ? 'rgb(var(--surface))' : 'rgb(var(--hover))',
                  border: '1px solid rgb(var(--border))',
                  opacity: a.enabled ? 1 : 0.65,
                }}>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-semibold font-mono" style={{ color: 'rgb(var(--tx-primary))' }}>
                      {a.username}
                    </span>
                    <span className={`badge text-[10px] ${a.protocol === 'SFTP' ? 'badge-blue' : 'badge-green'}`}>
                      {a.protocol}
                    </span>
                    {!a.enabled && <span className="badge badge-yellow text-[10px]">Disabled</span>}
                    {a.homeFolderOverride && (
                      <span className="text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
                        {a.homeFolderOverride}
                      </span>
                    )}
                  </div>
                  <div className="flex gap-2 mt-0.5 text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
                    {[['R', a.canRead], ['W', a.canWrite], ['D', a.canDelete]].map(([l, v]) =>
                      v != null ? (
                        <span key={l} style={{ color: v ? '#22c55e' : '#ef4444' }}>{v ? 'Y' : 'N'} {l}</span>
                      ) : null
                    )}
                    {a.maxConcurrentSessions != null && <span>max {a.maxConcurrentSessions} sess</span>}
                    <span>added {new Date(a.createdAt).toLocaleDateString()}</span>
                  </div>
                </div>
                <div className="flex items-center gap-1 flex-shrink-0">
                  <button
                    onClick={() => toggleEnabled.mutate({ accountId: a.accountId, enabled: !a.enabled })}
                    title={a.enabled ? 'Disable access' : 'Enable access'}
                    className="p-1 rounded transition-colors"
                    style={{ color: a.enabled ? '#22c55e' : '#f59e0b' }}
                    onMouseEnter={e => e.currentTarget.style.opacity = '0.7'}
                    onMouseLeave={e => e.currentTarget.style.opacity = '1'}
                  >
                    {a.enabled ? <CheckIcon className="w-4 h-4" /> : <ArrowPathIcon className="w-4 h-4" />}
                  </button>
                  <button
                    onClick={() => { if (confirm(`Revoke ${a.username}'s access to this server?`)) revokeMut.mutate({ accountId: a.accountId }) }}
                    title="Revoke access"
                    className="p-1 rounded transition-colors"
                    style={{ color: 'rgb(var(--tx-muted))' }}
                    onMouseEnter={e => e.currentTarget.style.color = '#ef4444'}
                    onMouseLeave={e => e.currentTarget.style.color = 'rgb(var(--tx-muted))'}
                  >
                    <XMarkIcon className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Note about assigning via account page */}
        <div className="text-xs px-3 py-2 rounded-lg" style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-muted))' }}>
          To assign an account to this server, use the <strong>Transfer Accounts</strong> page, select an account, then use the Servers tab,
          or call <code className="px-1 py-0.5 rounded" style={{ background: 'rgb(var(--surface))' }}>
            POST /api/servers/{server.id}/accounts/{'{accountId}'}
          </code>
        </div>

        <div className="flex justify-end pt-2">
          <button className="btn-secondary" onClick={onClose}>Close</button>
        </div>
      </div>
    </Modal>
  )
}

export default function ServerInstances() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editServer, setEditServer] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [protocolFilter, setProtocolFilter] = useState('ALL')
  const [accountsServer, setAccountsServer] = useState(null)

  const { data: servers = [], isLoading } = useQuery({ queryKey: ['server-instances'], queryFn: getServerInstances })

  const createMut = useMutation({
    mutationFn: createServerInstance,
    onSuccess: () => { qc.invalidateQueries(['server-instances']); setShowCreate(false); setForm(emptyForm); toast.success('Server instance created') },
    onError: err => toast.error(friendlyError(err))
  })

  const updateMut = useMutation({
    mutationFn: ({ id, data }) => updateServerInstance(id, data),
    onSuccess: () => { qc.invalidateQueries(['server-instances']); setEditServer(null); toast.success('Server updated') },
    onError: err => toast.error(friendlyError(err))
  })

  const deleteMut = useMutation({
    mutationFn: deleteServerInstance,
    onSuccess: () => { qc.invalidateQueries(['server-instances']); toast.success('Server deactivated') }
  })

  const toggleMut = useMutation({
    mutationFn: ({ id, active }) => updateServerInstance(id, { active }),
    onSuccess: () => { qc.invalidateQueries(['server-instances']); toast.success('Status updated') }
  })

  const maintenanceMut = useMutation({
    mutationFn: ({ id, enable }) => toggleMaintenance(id, enable, enable ? 'Server under maintenance' : ''),
    onSuccess: (_, { enable }) => {
      qc.invalidateQueries(['server-instances'])
      toast.success(enable ? 'Maintenance mode ON -- new connections blocked' : 'Maintenance mode OFF')
    },
    onError: () => toast.error('Failed to toggle maintenance mode'),
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
      complianceProfileId: s.complianceProfileId || '',
      securityTier: s.securityTier || 'RULES',
      // SSH / session
      sshBannerMessage: s.sshBannerMessage || '',
      maxAuthAttempts: s.maxAuthAttempts ?? 3,
      idleTimeoutSeconds: s.idleTimeoutSeconds ?? 300,
      sessionMaxDurationSeconds: s.sessionMaxDurationSeconds ?? 86400,
      allowedCiphers: s.allowedCiphers || '',
      allowedMacs: s.allowedMacs || '',
      allowedKex: s.allowedKex || '',
      // Proxy
      proxyGroupName: s.proxyGroupName || '',
      // Maintenance
      maintenanceMode: s.maintenanceMode || false,
      maintenanceMessage: s.maintenanceMessage || '',
      active: s.active ?? true,
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
          <h1 className="text-2xl font-bold text-primary">Server Instances</h1>
          <p className="text-secondary text-sm">Manage server instances across all protocols and assign users to specific servers</p>
        </div>
        <button className="btn-primary" onClick={() => { setForm(emptyForm); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> Add Server
        </button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-5 gap-4">
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Total Servers</p>
          <p className="text-2xl font-bold text-primary mt-1">{servers.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Active</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{servers.filter(s => s.active).length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Physical Storage</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{servers.filter(s => !s.defaultStorageMode || s.defaultStorageMode === 'PHYSICAL').length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">VFS (Virtual)</p>
          <p className="text-2xl font-bold text-purple-600 mt-1">{servers.filter(s => s.defaultStorageMode === 'VIRTUAL').length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">With Proxy</p>
          <p className="text-2xl font-bold text-amber-600 mt-1">{servers.filter(s => s.useProxy).length}</p>
        </div>
      </div>

      {/* Protocol filter tabs */}
      <div className="flex gap-2 flex-wrap">
        <button
          onClick={() => setProtocolFilter('ALL')}
          className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
            protocolFilter === 'ALL' ? 'bg-gray-900 text-white' : 'bg-hover text-secondary hover:bg-gray-200'
          }`}>
          All ({servers.length})
        </button>
        {PROTOCOLS.map(p => (
          protocolCounts[p] > 0 || protocolFilter === p ? (
            <button key={p}
              onClick={() => setProtocolFilter(p)}
              className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
                protocolFilter === p ? 'bg-gray-900 text-white' : 'bg-hover text-secondary hover:bg-gray-200'
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
              <tr className="border-b border-border">
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
                        s.protocol === 'FTP_WEB' ? 'badge-purple' :
                        s.protocol === 'AS2' || s.protocol === 'AS4' ? 'badge-blue' : 'badge-yellow'
                      }`}>{PROTOCOL_LABELS[s.protocol] || s.protocol}</span>
                    </td>
                    <td className="table-cell">
                      <div>
                        <p className="font-medium text-primary">{s.name}</p>
                        {s.description && <p className="text-xs text-muted">{s.description}</p>}
                      </div>
                    </td>
                    <td className="table-cell">
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${
                        isVFS ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'
                      }`}>
                        {isVFS ? <><CircleStackIcon className="w-3 h-3" /> VFS</> : <><FolderIcon className="w-3 h-3" /> Physical</>}
                      </span>
                    </td>
                    <td className="table-cell font-mono text-xs text-secondary">{s.internalHost}:{s.internalPort}</td>
                    <td className="table-cell font-mono text-xs text-primary">
                      {s.clientHost}:{s.clientPort}
                      {s.useProxy && (
                        <span className="ml-1 text-[10px] text-blue-500">(proxy)</span>
                      )}
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${tierInfo.badge}`}>{tierInfo.label}</span>
                    </td>
                    <td className="table-cell">
                      <div className="flex items-center gap-1 flex-wrap">
                        <button onClick={() => toggleMut.mutate({ id: s.id, active: !s.active })}
                          className={`inline-flex items-center gap-1 px-2 py-1 rounded text-xs font-medium ${
                            s.active ? 'bg-green-50 text-green-700 hover:bg-green-100' : 'bg-red-50 text-red-700 hover:bg-red-100'
                          }`}>
                          {s.active ? <><SignalIcon className="w-3 h-3" /> Active</> : <><SignalSlashIcon className="w-3 h-3" /> Inactive</>}
                        </button>
                        {s.maintenanceMode && (
                          <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-bold bg-yellow-100 text-yellow-700">
                            <WrenchScrewdriverIcon className="w-3 h-3" /> MAINT
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="table-cell">
                      <div className="flex gap-1">
                        {/* Accounts button */}
                        <button
                          onClick={() => setAccountsServer(s)}
                          title="Manage accounts on this server"
                          className="p-1.5 rounded hover:bg-purple-50 text-purple-400 hover:text-purple-600 transition-colors relative">
                          <UsersIcon className="w-4 h-4" />
                          {s.assignedAccountCount > 0 && (
                            <span className="absolute -top-0.5 -right-0.5 w-4 h-4 rounded-full bg-purple-500 text-white text-[9px] font-bold flex items-center justify-center">
                              {s.assignedAccountCount > 9 ? '9+' : s.assignedAccountCount}
                            </span>
                          )}
                        </button>
                        {/* Maintenance toggle */}
                        <button
                          onClick={() => maintenanceMut.mutate({ id: s.id, enable: !s.maintenanceMode })}
                          title={s.maintenanceMode ? 'Disable maintenance mode' : 'Enable maintenance mode'}
                          className={`p-1.5 rounded transition-colors ${s.maintenanceMode ? 'text-yellow-500 hover:bg-yellow-50' : 'text-gray-300 hover:bg-yellow-50 hover:text-yellow-500'}`}>
                          <WrenchScrewdriverIcon className="w-4 h-4" />
                        </button>
                        <button onClick={() => openEdit(s)} title="Edit server" className="p-1.5 rounded hover:bg-blue-50 text-blue-500">
                          <PencilIcon className="w-4 h-4" />
                        </button>
                        <button onClick={() => { if(confirm('Deactivate this server?')) deleteMut.mutate(s.id) }} title="Deactivate server"
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

      {/* Accounts Panel */}
      {accountsServer && (
        <AccountsPanel server={accountsServer} onClose={() => setAccountsServer(null)} />
      )}
    </div>
  )
}

// ── ServerForm ──────────────────────────────────────────────────────────────────

function ServerForm({ form, setForm, onSubmit, isPending, onCancel, submitLabel, showInstanceId }) {
  const { services } = useServices() || { services: {} }
  const dmzRunning = services?.dmz !== false
  const [showSshCrypto, setShowSshCrypto] = useState(false)

  const { data: proxyGroups = [] } = useQuery({
    queryKey: ['proxy-groups-simple'],
    queryFn: getProxyGroups,
    staleTime: 60000,
    retry: false,
  })

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

  const { data: complianceProfiles = [] } = useQuery({
    queryKey: ['compliance-profiles-picker'],
    queryFn: () => configApi.get('/api/compliance/profiles').then(r => r.data).catch(() => []),
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

  const isSSH = isSshProtocol(form.protocol)

  return (
    <form onSubmit={e => { e.preventDefault(); onSubmit() }} className="space-y-4 max-h-[75vh] overflow-y-auto pr-1">

      {/* ═══════ Section 1: Basic Information ═══════ */}
      <FormSection title="Basic Information" subtitle="Server identity and protocol">
        <div>
          <label>Protocol *</label>
          <select value={form.protocol} onChange={e => handleProtocolChange(e.target.value)}>
            {PROTOCOLS.map(p => <option key={p} value={p}>{PROTOCOL_LABELS[p]}</option>)}
          </select>
          <p className="text-xs text-muted mt-1">Determines the file transfer protocol this server instance handles</p>
        </div>

        {showInstanceId && (
          <div>
            <label>Instance ID *</label>
            <input value={form.instanceId} onChange={e => f('instanceId', e.target.value)} required
              placeholder={`${form.protocol.toLowerCase()}-3`} pattern="[a-z0-9\-]+" title="Lowercase letters, numbers, hyphens" />
            <p className="text-xs text-muted mt-1">Unique identifier (e.g., sftp-3, ftp-eu-west, ftpweb-2)</p>
          </div>
        )}

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label>Name *</label>
            <input value={form.name} onChange={e => f('name', e.target.value)} required placeholder="EU West Server" />
            <p className="text-xs text-muted mt-1">Human-readable name for this server</p>
          </div>
          <div>
            <label>Description</label>
            <input value={form.description} onChange={e => f('description', e.target.value)} placeholder="Production server for EU region" />
          </div>
        </div>

        <div className="flex items-center gap-3">
          <input type="checkbox" id="activeToggle" checked={form.active !== false} onChange={e => f('active', e.target.checked)}
            className="w-4 h-4 text-blue-600 rounded border-gray-300" />
          <label htmlFor="activeToggle" className="text-sm font-medium text-primary mb-0">Active</label>
          <p className="text-xs text-muted">Inactive servers will not accept connections</p>
        </div>
      </FormSection>

      {/* ═══════ Section 2: Network Configuration ═══════ */}
      <FormSection title="Network Configuration" subtitle="Internal and external connection endpoints">
        <div>
          <p className="text-xs text-secondary uppercase tracking-wider font-semibold mb-2">Internal Connection (Docker / Host)</p>
          <p className="text-xs text-muted mb-3">Where the platform connects to this server internally</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label>Internal Host *</label>
              <input value={form.internalHost} onChange={e => f('internalHost', e.target.value)} required placeholder={`${form.protocol.toLowerCase()}-service-3`} />
            </div>
            <div>
              <label>Internal Port *</label>
              <input type="number" value={form.internalPort} onChange={e => f('internalPort', parseInt(e.target.value))} required />
            </div>
          </div>
        </div>

        <div>
          <p className="text-xs text-secondary uppercase tracking-wider font-semibold mb-2">External Connection (Client-Facing)</p>
          <p className="text-xs text-muted mb-3">What partners/clients use to connect. Leave blank if same as internal.</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label>External Host</label>
              <input value={form.externalHost} onChange={e => f('externalHost', e.target.value)} placeholder="files.example.com" />
            </div>
            <div>
              <label>External Port</label>
              <input type="number" value={form.externalPort} onChange={e => f('externalPort', e.target.value ? parseInt(e.target.value) : '')} placeholder={String(DEFAULT_PORTS[form.protocol])} />
            </div>
          </div>
        </div>
      </FormSection>

      {/* ═══════ Section 3: Security ═══════ */}
      <FormSection title="Security" subtitle="Compliance, security tier, and authentication settings">
        {/* Compliance Profile dropdown */}
        <div>
          <label>Compliance Profile</label>
          <select value={form.complianceProfileId || ''} onChange={e => f('complianceProfileId', e.target.value || '')}>
            <option value="">-- No compliance enforcement --</option>
            {complianceProfiles.map(p => (
              <option key={p.id} value={p.id}>{p.name}{p.description ? ` -- ${p.description}` : ''}</option>
            ))}
          </select>
          <p className="text-xs text-muted mt-1">Assign a compliance profile to enforce data rules on transfers through this server</p>
        </div>

        {/* Security Tier */}
        <div>
          <SecurityTierSelector
            tier={form.securityTier}
            onTierChange={tier => f('securityTier', tier)}
            showAiTiers={true}
            llmEnabled={llmEnabled}
          />
          <p className="text-xs text-muted mt-1">RULES = pattern matching only, AI = machine-learning threat detection, AI+LLM = deep content analysis</p>
        </div>

        {/* Max Auth Attempts */}
        <div>
          <label>Max Authentication Attempts</label>
          <input type="number" min={1} max={10} value={form.maxAuthAttempts}
            onChange={e => setForm(prev => ({ ...prev, maxAuthAttempts: Number(e.target.value) }))} />
          <p className="text-xs text-muted mt-1">Disconnect after this many failed login attempts (1-10)</p>
        </div>

        {/* SSH-specific fields */}
        {isSSH && (
          <>
            <div>
              <label>SSH Banner Message</label>
              <textarea rows={2} value={form.sshBannerMessage || ''} placeholder="Authorized access only. Connections are monitored."
                onChange={e => setForm(prev => ({ ...prev, sshBannerMessage: e.target.value }))} />
              <p className="text-xs text-muted mt-1">Shown to SSH clients immediately after connecting (SFTP only)</p>
            </div>

            {/* SSH Crypto Accordion */}
            <div style={{ border: '1px solid rgb(var(--border, 229 231 235))', borderRadius: '0.5rem' }}>
              <button
                type="button"
                onClick={() => setShowSshCrypto(o => !o)}
                className="w-full flex items-center justify-between px-3 py-2 text-left text-xs font-medium text-secondary"
                style={{ background: 'rgb(var(--hover, 249 250 251))', borderRadius: '0.5rem' }}
              >
                <span>SSH Cipher / Algorithm Allowlists (Advanced)</span>
                {showSshCrypto
                  ? <ChevronUpIcon className="w-3.5 h-3.5" />
                  : <ChevronDownIcon className="w-3.5 h-3.5" />
                }
              </button>
              <div
                className="transition-all duration-200 ease-in-out"
                style={{ maxHeight: showSshCrypto ? '500px' : '0', opacity: showSshCrypto ? 1 : 0, overflow: 'hidden' }}
              >
                <div className="px-3 py-3 space-y-3" style={{ borderTop: '1px solid rgb(var(--border, 229 231 235))' }}>
                  <p className="text-xs text-muted">Comma-separated allowlists. Leave blank to use server defaults.</p>
                  <div className="grid grid-cols-3 gap-2">
                    <div>
                      <label className="text-xs">Ciphers</label>
                      <input value={form.allowedCiphers || ''} placeholder="aes256-gcm@openssh.com,..."
                        onChange={e => setForm(prev => ({ ...prev, allowedCiphers: e.target.value }))} />
                    </div>
                    <div>
                      <label className="text-xs">MACs</label>
                      <input value={form.allowedMacs || ''} placeholder="hmac-sha2-256-etm@openssh.com,..."
                        onChange={e => setForm(prev => ({ ...prev, allowedMacs: e.target.value }))} />
                    </div>
                    <div>
                      <label className="text-xs">KEX</label>
                      <input value={form.allowedKex || ''} placeholder="curve25519-sha256,..."
                        onChange={e => setForm(prev => ({ ...prev, allowedKex: e.target.value }))} />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </>
        )}
      </FormSection>

      {/* ═══════ Section 4: Session & Limits ═══════ */}
      <FormSection title="Session & Limits" subtitle="Connection limits, timeouts, and storage mode">
        <div className="grid grid-cols-3 gap-4">
          <div>
            <label>Max Connections</label>
            <input type="number" value={form.maxConnections} onChange={e => f('maxConnections', parseInt(e.target.value))} />
            <p className="text-xs text-muted mt-1">Maximum simultaneous connections</p>
          </div>
          <div>
            <label>Idle Timeout (seconds)</label>
            <input type="number" min={0} value={form.idleTimeoutSeconds}
              onChange={e => setForm(prev => ({ ...prev, idleTimeoutSeconds: Number(e.target.value) }))} />
            <p className="text-xs text-muted mt-1">0 = no timeout</p>
          </div>
          <div>
            <label>Max Session Duration (seconds)</label>
            <input type="number" min={0} value={form.sessionMaxDurationSeconds}
              onChange={e => setForm(prev => ({ ...prev, sessionMaxDurationSeconds: Number(e.target.value) }))} />
            <p className="text-xs text-muted mt-1">0 = unlimited</p>
          </div>
        </div>

        {/* Storage Mode */}
        <div>
          <p className="text-xs text-secondary uppercase tracking-wider font-semibold mb-2">Default Storage Mode</p>
          <p className="text-xs text-muted mb-3">How files are stored for accounts on this server</p>
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
                      : 'border-border hover:border-gray-300'
                  }`}>
                  <div className="flex items-center gap-2 mb-1">
                    <Icon className={`w-5 h-5 ${selected ? (mode.color === 'purple' ? 'text-purple-600' : 'text-blue-600') : 'text-muted'}`} />
                    <span className={`font-semibold text-sm ${selected ? (mode.color === 'purple' ? 'text-purple-700' : 'text-blue-700') : 'text-primary'}`}>
                      {mode.label}
                    </span>
                  </div>
                  <p className="text-xs text-secondary">{mode.desc}</p>
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
      </FormSection>

      {/* ═══════ Section 5: Proxy Configuration ═══════ */}
      <FormSection
        title="Proxy Configuration"
        subtitle="Reverse proxy and QoS settings"
        defaultOpen={form.useProxy}
      >
        <div className="flex items-center gap-3">
          <input type="checkbox" id="useProxy" checked={form.useProxy} onChange={e => f('useProxy', e.target.checked)}
            className="w-4 h-4 text-blue-600 rounded border-gray-300" />
          <label htmlFor="useProxy" className="text-sm font-medium text-primary mb-0">Use Reverse Proxy</label>
          <p className="text-xs text-muted">Route connections through the DMZ proxy for security</p>
        </div>

        <div
          className="transition-all duration-200 ease-in-out"
          style={{ maxHeight: form.useProxy ? '1500px' : '0', opacity: form.useProxy ? 1 : 0, overflow: 'hidden' }}
        >
          <div className="space-y-4 pt-2">
            <div className="grid grid-cols-2 gap-4">
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
              <p className="text-xs text-green-600 flex items-center gap-1">
                <span className="w-2 h-2 rounded-full bg-green-400 inline-block" />
                DMZ Proxy detected and running on port 8088
              </p>
            )}
            {!dmzRunning && form.proxyHost === 'dmz-proxy' && (
              <p className="text-xs text-amber-600 flex items-center gap-1">
                <span className="w-2 h-2 rounded-full bg-amber-400 inline-block" />
                DMZ Proxy is not running -- proxy routing may fail
              </p>
            )}

            {/* Proxy Group */}
            <div>
              <label>Proxy Group</label>
              <select value={form.proxyGroupName || ''} onChange={e => setForm(prev => ({ ...prev, proxyGroupName: e.target.value }))}>
                <option value="">-- Default routing (no specific group) --</option>
                {proxyGroups.filter(g => g.active !== false).map(g => (
                  <option key={g.name} value={g.name}>{g.name} ({g.type})</option>
                ))}
              </select>
              <p className="text-xs text-muted mt-1">Route inbound connections through a specific proxy group (internal / external / partner)</p>
            </div>

            {/* Proxy QoS Policy */}
            <div style={{ borderTop: '1px solid rgb(var(--border, 229 231 235))', paddingTop: '0.75rem' }}>
              <div className="flex items-center gap-3 mb-3">
                <input type="checkbox" id="proxyQosEnabled" checked={form.proxyQos?.enabled || false}
                  onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, enabled: e.target.checked } }))}
                  className="w-4 h-4 text-blue-600 rounded border-gray-300" />
                <label htmlFor="proxyQosEnabled" className="text-sm font-medium text-primary mb-0">Enable Proxy QoS</label>
                <p className="text-xs text-muted">Bandwidth throttling and priority control</p>
              </div>
              <div
                className="transition-all duration-200 ease-in-out"
                style={{ maxHeight: form.proxyQos?.enabled ? '500px' : '0', opacity: form.proxyQos?.enabled ? 1 : 0, overflow: 'hidden' }}
              >
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="text-xs text-secondary">Max Bandwidth (MB/s)</label>
                    <input type="number" min="0"
                      value={form.proxyQos.maxBytesPerSecond ? Math.round(form.proxyQos.maxBytesPerSecond / 1048576) : ''}
                      onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, maxBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                      placeholder="0 = unlimited" />
                    <p className="text-xs text-muted mt-0.5">Aggregate for all connections through this mapping</p>
                  </div>
                  <div>
                    <label className="text-xs text-secondary">Per-Connection Limit (MB/s)</label>
                    <input type="number" min="0"
                      value={form.proxyQos.perConnectionMaxBytesPerSecond ? Math.round(form.proxyQos.perConnectionMaxBytesPerSecond / 1048576) : ''}
                      onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, perConnectionMaxBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                      placeholder="0 = unlimited" />
                  </div>
                  <div>
                    <label className="text-xs text-secondary">Priority (1=Highest, 10=Lowest)</label>
                    <input type="number" min="1" max="10"
                      value={form.proxyQos.priority || 5}
                      onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, priority: Number(e.target.value) } }))} />
                  </div>
                  <div>
                    <label className="text-xs text-secondary">Burst Allowance (%)</label>
                    <input type="number" min="0" max="100"
                      value={form.proxyQos.burstAllowancePercent || 20}
                      onChange={e => setForm(prev => ({ ...prev, proxyQos: { ...prev.proxyQos, burstAllowancePercent: Number(e.target.value) } }))} />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </FormSection>

      {/* ═══════ Section 6: Templates & Maintenance ═══════ */}
      <FormSection title="Templates & Maintenance" subtitle="Folder structure and maintenance mode" defaultOpen={true}>
        {/* Folder Template */}
        <div>
          <p className="text-xs text-secondary uppercase tracking-wider font-semibold mb-1">Folder Template</p>
          <p className="text-xs text-muted mb-3">
            Directory structure users see when connecting -- inbox, outbox, archive, etc.
          </p>

          {folderTemplates.length > 0 ? (
            <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
              {/* None option */}
              <button type="button"
                onClick={() => { f('folderTemplateId', null); f('clearFolderTemplate', true) }}
                className={`w-full text-left p-3 rounded-lg border-2 transition-all ${
                  !form.folderTemplateId ? 'border-gray-400 bg-canvas' : 'border-border hover:border-gray-300'
                }`}>
                <p className={`text-sm font-medium ${!form.folderTemplateId ? 'text-primary' : 'text-secondary'}`}>
                  No folder template
                </p>
                <p className="text-xs text-muted">Accounts use a flat home directory with no predefined structure</p>
              </button>

              {/* Template cards */}
              {folderTemplates.map(t => {
                const isSelected = form.folderTemplateId === t.id
                return (
                  <button key={t.id} type="button"
                    onClick={() => { f('folderTemplateId', t.id); f('clearFolderTemplate', false) }}
                    className={`w-full text-left p-3 rounded-lg border-2 transition-all ${
                      isSelected ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-200' : 'border-border hover:border-gray-300'
                    }`}>
                    <div className="flex items-center gap-2 mb-1">
                      <p className={`text-sm font-medium ${isSelected ? 'text-blue-900' : 'text-primary'}`}>{t.name}</p>
                      {t.builtIn && (
                        <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-full text-[10px] font-medium bg-blue-100 text-blue-600">
                          <LockClosedIcon className="w-2.5 h-2.5" /> Built-in
                        </span>
                      )}
                      <span className="text-[10px] text-muted ml-auto">{t.folders.length} folder{t.folders.length !== 1 ? 's' : ''}</span>
                    </div>
                    {t.description && <p className="text-xs text-secondary mb-1.5">{t.description}</p>}
                    <div className="flex flex-wrap gap-1">
                      {t.folders.map((fd, i) => (
                        <span key={i} className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-mono ${
                          isSelected ? 'bg-blue-100/70 text-blue-700' : 'bg-hover text-secondary'
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
            <div className="bg-canvas rounded-lg p-3 text-xs text-secondary border border-border">
              No folder templates available. Create templates on the <span className="font-medium">Folder Templates</span> page first.
            </div>
          )}
        </div>

        {/* Maintenance Mode */}
        <div style={{ borderTop: '1px solid rgb(var(--border, 229 231 235))', paddingTop: '0.75rem' }}>
          <div className="flex items-center gap-2">
            <input type="checkbox" id="maintenanceMode" checked={form.maintenanceMode}
              onChange={e => setForm(prev => ({ ...prev, maintenanceMode: e.target.checked }))} className="w-4 h-4 text-yellow-600 rounded border-gray-300" />
            <label htmlFor="maintenanceMode" className="text-sm font-medium text-yellow-700 mb-0">
              Maintenance Mode
            </label>
            <p className="text-xs text-muted">New connections will be rejected while enabled</p>
          </div>
          <div
            className="transition-all duration-200 ease-in-out"
            style={{ maxHeight: form.maintenanceMode ? '200px' : '0', opacity: form.maintenanceMode ? 1 : 0, overflow: 'hidden' }}
          >
            <div className="mt-3">
              <label>Maintenance Message</label>
              <input value={form.maintenanceMessage || ''} placeholder="Server under maintenance, back in 30 min"
                onChange={e => setForm(prev => ({ ...prev, maintenanceMessage: e.target.value }))} />
              <p className="text-xs text-muted mt-1">Shown to clients attempting to connect during maintenance</p>
            </div>
          </div>
        </div>
      </FormSection>

      {/* Submit */}
      <div className="flex gap-3 justify-end pt-4 border-t">
        <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>{submitLabel}</button>
      </div>
    </form>
  )
}
