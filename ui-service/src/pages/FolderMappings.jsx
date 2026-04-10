import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getFolderMappings, createFolderMapping, updateFolderMapping, deleteFolderMapping, getAccounts } from '../api/accounts'
import Modal from '../components/Modal'
import StoragePerformanceEstimator from '../components/StoragePerformanceEstimator'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilSquareIcon, ArrowRightIcon,
  ServerStackIcon, FolderIcon, SparklesIcon, DocumentDuplicateIcon,
  FunnelIcon
} from '@heroicons/react/24/outline'

const emptyForm = { sourceAccountId: '', sourcePath: '/inbox', destinationAccountId: '', destinationPath: '/outbox', filenamePattern: '', encryptionOption: 'NONE' }

const PROTOCOL_TABS = ['All', 'SFTP', 'FTP', 'FTP_WEB', 'AS2', 'HTTPS']

const PROTOCOL_COLORS = {
  SFTP: { bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200', badge: 'bg-blue-100 text-blue-700' },
  FTP: { bg: 'bg-amber-50', text: 'text-amber-700', border: 'border-amber-200', badge: 'bg-amber-100 text-amber-700' },
  FTP_WEB: { bg: 'bg-purple-50', text: 'text-purple-700', border: 'border-purple-200', badge: 'bg-purple-100 text-purple-700' },
  FTPS: { bg: 'bg-amber-50', text: 'text-amber-700', border: 'border-amber-200', badge: 'bg-amber-100 text-amber-700' },
  AS2: { bg: 'bg-green-50', text: 'text-green-700', border: 'border-green-200', badge: 'bg-green-100 text-green-700' },
  AS4: { bg: 'bg-green-50', text: 'text-green-700', border: 'border-green-200', badge: 'bg-green-100 text-green-700' },
  HTTPS: { bg: 'bg-cyan-50', text: 'text-cyan-700', border: 'border-cyan-200', badge: 'bg-cyan-100 text-cyan-700' },
}

const SAMPLE_MAPPINGS = [
  {
    id: 'sftp-inbound',
    protocol: 'SFTP',
    title: 'SFTP Inbound Routing',
    description: 'Partner uploads to SFTP inbox, files routed to internal processing folder',
    sourcePath: '/inbox',
    destinationPath: '/processing/inbound',
    filenamePattern: '',
    encryptionOption: 'NONE',
    sourceHint: 'Partner SFTP account',
    destHint: 'Internal SFTP account',
  },
  {
    id: 'sftp-outbound',
    protocol: 'SFTP',
    title: 'SFTP Outbound Delivery',
    description: 'Internal system writes to outbox, files delivered to partner pickup folder',
    sourcePath: '/outbox',
    destinationPath: '/pickup',
    filenamePattern: '',
    encryptionOption: 'NONE',
    sourceHint: 'Internal SFTP account',
    destHint: 'Partner SFTP account',
  },
  {
    id: 'sftp-encrypted',
    protocol: 'SFTP',
    title: 'SFTP Encrypted Transfer',
    description: 'PGP-encrypt files before forwarding to partner for compliance',
    sourcePath: '/secure/outbox',
    destinationPath: '/inbox',
    filenamePattern: '.*\\.(csv|xlsx|pdf)$',
    encryptionOption: 'ENCRYPT_BEFORE_FORWARD',
    sourceHint: 'Internal SFTP account',
    destHint: 'Partner SFTP account',
  },
  {
    id: 'ftp-vendor-drop',
    protocol: 'FTP',
    title: 'FTP Vendor File Drop',
    description: 'Vendor drops CSV/EDI files via FTP, auto-routed to SFTP processing',
    sourcePath: '/drop',
    destinationPath: '/vendor-files/inbound',
    filenamePattern: '.*\\.(csv|edi|txt)$',
    encryptionOption: 'NONE',
    sourceHint: 'Vendor FTP account',
    destHint: 'Internal SFTP or FTP account',
  },
  {
    id: 'ftp-batch-pickup',
    protocol: 'FTP',
    title: 'FTP Batch Pickup',
    description: 'Internal batch process writes reports, partner picks up via FTP',
    sourcePath: '/reports/ready',
    destinationPath: '/outbox',
    filenamePattern: '.*\\.pdf$',
    encryptionOption: 'NONE',
    sourceHint: 'Internal FTP account',
    destHint: 'Partner FTP account',
  },
  {
    id: 'ftpweb-upload',
    protocol: 'FTP_WEB',
    title: 'FTP-Web Browser Upload',
    description: 'Users upload files via browser, routed to SFTP server for processing',
    sourcePath: '/uploads',
    destinationPath: '/web-submissions',
    filenamePattern: '',
    encryptionOption: 'NONE',
    sourceHint: 'FTP-Web user account',
    destHint: 'Internal SFTP account',
  },
  {
    id: 'ftpweb-download',
    protocol: 'FTP_WEB',
    title: 'FTP-Web Download Portal',
    description: 'Internal files staged for partner download via browser portal',
    sourcePath: '/staging/portal',
    destinationPath: '/downloads',
    filenamePattern: '',
    encryptionOption: 'NONE',
    sourceHint: 'Internal account',
    destHint: 'FTP-Web user account',
  },
  {
    id: 'as2-edi-inbound',
    protocol: 'AS2',
    title: 'AS2 EDI Inbound',
    description: 'Receive EDI documents via AS2, auto-convert and route to processing',
    sourcePath: '/as2/inbound',
    destinationPath: '/edi/processing',
    filenamePattern: '.*\\.(edi|x12|edifact)$',
    encryptionOption: 'DECRYPT_THEN_FORWARD',
    sourceHint: 'AS2 partner account',
    destHint: 'Internal processing account',
  },
  {
    id: 'as2-edi-outbound',
    protocol: 'AS2',
    title: 'AS2 EDI Outbound',
    description: 'Send EDI documents to trading partner via AS2 with encryption',
    sourcePath: '/edi/outbound',
    destinationPath: '/as2/send',
    filenamePattern: '.*\\.(edi|x12)$',
    encryptionOption: 'ENCRYPT_BEFORE_FORWARD',
    sourceHint: 'Internal EDI account',
    destHint: 'AS2 partner account',
  },
  {
    id: 'https-api-ingest',
    protocol: 'HTTPS',
    title: 'HTTPS API File Ingest',
    description: 'Files received via REST API, stored and routed to internal processing',
    sourcePath: '/api/ingest',
    destinationPath: '/inbox',
    filenamePattern: '',
    encryptionOption: 'NONE',
    sourceHint: 'HTTPS API account',
    destHint: 'Internal SFTP account',
  },
]

export default function FolderMappings() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [editingMapping, setEditingMapping] = useState(null)
  const [form, setForm] = useState({ ...emptyForm })
  const [protocolFilter, setProtocolFilter] = useState('All')
  const [showSamples, setShowSamples] = useState(true)

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

  const openCreateFromSample = (sample) => {
    setForm({
      sourceAccountId: '',
      sourcePath: sample.sourcePath,
      destinationAccountId: '',
      destinationPath: sample.destinationPath,
      filenamePattern: sample.filenamePattern,
      encryptionOption: sample.encryptionOption,
    })
    setShowCreate(true)
  }

  const openCreate = () => {
    setForm({ ...emptyForm })
    setShowCreate(true)
  }

  // Build lookup for account protocols
  const accountProtocol = {}
  accounts.forEach(a => { accountProtocol[a.id] = a.protocol })

  // Filter mappings by protocol
  const filteredMappings = protocolFilter === 'All'
    ? mappings
    : mappings.filter(m => {
        const srcProto = accountProtocol[m.sourceAccountId]
        const dstProto = accountProtocol[m.destinationAccountId]
        return srcProto === protocolFilter || dstProto === protocolFilter
      })

  // Filter samples by protocol
  const filteredSamples = protocolFilter === 'All'
    ? SAMPLE_MAPPINGS
    : SAMPLE_MAPPINGS.filter(s => s.protocol === protocolFilter)

  // Protocol counts
  const protocolCounts = {}
  mappings.forEach(m => {
    const srcProto = accountProtocol[m.sourceAccountId]
    if (srcProto) protocolCounts[srcProto] = (protocolCounts[srcProto] || 0) + 1
  })

  if (isLoading) return <LoadingSpinner />

  const renderForm = (onSubmit, isPending, submitLabel, pendingLabel) => {
    // Group accounts by protocol for easier selection
    const groupedAccounts = {}
    accounts.forEach(a => {
      if (!groupedAccounts[a.protocol]) groupedAccounts[a.protocol] = []
      groupedAccounts[a.protocol].push(a)
    })
    const protocols = Object.keys(groupedAccounts).sort()

    return (
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="grid grid-cols-2 gap-6">
          <div className="space-y-3">
            <h4 className="font-semibold text-primary flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-blue-500" /> Source
            </h4>
            <div><label>Account</label>
              <select value={form.sourceAccountId} onChange={e => setForm(f => ({...f, sourceAccountId: e.target.value}))} required>
                <option value="">Select source account...</option>
                {protocols.map(proto => (
                  <optgroup key={proto} label={proto}>
                    {groupedAccounts[proto].map(a => (
                      <option key={a.id} value={a.id}>{a.username} ({proto})</option>
                    ))}
                  </optgroup>
                ))}
              </select></div>
            <div><label>Path</label>
              <input value={form.sourcePath} onChange={e => setForm(f => ({...f, sourcePath: e.target.value}))} placeholder="/inbox" className="font-mono text-sm" /></div>
          </div>
          <div className="space-y-3">
            <h4 className="font-semibold text-primary flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-green-500" /> Destination
            </h4>
            <div><label>Account</label>
              <select value={form.destinationAccountId} onChange={e => setForm(f => ({...f, destinationAccountId: e.target.value}))} required>
                <option value="">Select destination account...</option>
                {protocols.map(proto => (
                  <optgroup key={proto} label={proto}>
                    {groupedAccounts[proto].map(a => (
                      <option key={a.id} value={a.id}>{a.username} ({proto})</option>
                    ))}
                  </optgroup>
                ))}
              </select></div>
            <div><label>Path</label>
              <input value={form.destinationPath} onChange={e => setForm(f => ({...f, destinationPath: e.target.value}))} placeholder="/outbox" className="font-mono text-sm" /></div>
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
  }

  const getProtocolColor = (proto) => PROTOCOL_COLORS[proto] || { bg: 'bg-canvas', text: 'text-primary', border: 'border-border', badge: 'bg-hover text-primary' }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">Folder Mappings</h1>
          <p className="text-secondary text-sm">Route files between accounts across SFTP, FTP, FTP-Web, AS2, and HTTPS servers</p>
        </div>
        <button className="btn-primary" onClick={openCreate}>
          <PlusIcon className="w-4 h-4" /> New Mapping
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-4 gap-4">
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Total Mappings</p>
          <p className="text-2xl font-bold text-primary mt-1">{mappings.length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Active</p>
          <p className="text-2xl font-bold text-green-600 mt-1">{mappings.filter(m => m.active).length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Encrypted</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{mappings.filter(m => m.encryptionOption && m.encryptionOption !== 'NONE').length}</p>
        </div>
        <div className="card p-4">
          <p className="text-xs text-secondary uppercase tracking-wider">Accounts</p>
          <p className="text-2xl font-bold text-purple-600 mt-1">{accounts.length}</p>
        </div>
      </div>

      {/* Protocol Filter Tabs */}
      <div className="flex items-center gap-1 bg-hover rounded-lg p-1">
        {PROTOCOL_TABS.map(tab => {
          const count = tab === 'All' ? mappings.length : (protocolCounts[tab] || 0)
          const isActive = protocolFilter === tab
          return (
            <button
              key={tab}
              onClick={() => setProtocolFilter(tab)}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-all ${
                isActive
                  ? 'bg-surface text-primary shadow-sm'
                  : 'text-secondary hover:text-primary'
              }`}
            >
              {tab === 'All' ? 'All' : tab.replace('_', '-')}
              {count > 0 && (
                <span className={`text-xs px-1.5 py-0.5 rounded-full ${
                  isActive ? 'bg-blue-100 text-blue-700' : 'bg-gray-200 text-secondary'
                }`}>{count}</span>
              )}
            </button>
          )
        })}
      </div>

      {/* Existing Mappings */}
      {filteredMappings.length > 0 && (
        <div className="space-y-3">
          <h2 className="text-sm font-semibold text-primary uppercase tracking-wider">Active Mappings</h2>
          {filteredMappings.map(m => {
            const srcAccount = accounts.find(a => a.id === m.sourceAccountId)
            const dstAccount = accounts.find(a => a.id === m.destinationAccountId)
            const srcColor = getProtocolColor(srcAccount?.protocol)
            const dstColor = getProtocolColor(dstAccount?.protocol)
            return (
              <div key={m.id} className="card flex items-center gap-4">
                <div className="flex-1">
                  <div className="flex items-center gap-2 text-sm flex-wrap">
                    {srcAccount?.protocol && (
                      <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium ${srcColor.badge}`}>
                        {srcAccount.protocol.replace('_', '-')}
                      </span>
                    )}
                    <span className="font-mono font-semibold text-blue-700">{srcAccount?.username || m.sourceAccountId?.substring(0,8)}</span>
                    <span className="text-muted font-mono text-xs">{m.sourcePath}</span>
                    <ArrowRightIcon className="w-4 h-4 text-muted flex-shrink-0" />
                    {dstAccount?.protocol && (
                      <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium ${dstColor.badge}`}>
                        {dstAccount.protocol.replace('_', '-')}
                      </span>
                    )}
                    <span className="font-mono font-semibold text-green-700">{dstAccount?.username || m.destinationAccountId?.substring(0,8)}</span>
                    <span className="text-muted font-mono text-xs">{m.destinationPath}</span>
                  </div>
                  {m.filenamePattern && <p className="text-xs text-muted font-mono mt-1">Pattern: {m.filenamePattern}</p>}
                </div>
                <span className={`badge ${m.active ? 'badge-green' : 'badge-red'}`}>{m.active ? 'Active' : 'Disabled'}</span>
                <span className="badge badge-blue">{m.encryptionOption || 'NONE'}</span>
                <button onClick={() => openEdit(m)} title="Edit mapping"
                  className="p-1.5 rounded hover:bg-blue-50 text-blue-500 transition-colors"><PencilSquareIcon className="w-4 h-4" /></button>
                <button onClick={() => { if(confirm('Delete this mapping?')) deleteMut.mutate(m.id) }} title="Delete mapping"
                  className="p-1.5 rounded hover:bg-red-50 text-red-500"><TrashIcon className="w-4 h-4" /></button>
              </div>
            )
          })}
        </div>
      )}

      {/* Empty State for filtered view */}
      {filteredMappings.length === 0 && mappings.length > 0 && (
        <div className="card">
          <EmptyState
            title={`No ${protocolFilter.replace('_', '-')} mappings`}
            description={`No folder mappings found for ${protocolFilter.replace('_', '-')} protocol. Use a sample template below to create one.`}
          />
        </div>
      )}

      {/* Sample Configurations */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <button
            onClick={() => setShowSamples(!showSamples)}
            className="flex items-center gap-2 text-sm font-semibold text-primary uppercase tracking-wider hover:text-primary transition-colors"
          >
            <SparklesIcon className="w-4 h-4 text-amber-500" />
            Sample Configurations
            <span className="text-xs font-normal text-muted lowercase">
              ({filteredSamples.length} templates)
            </span>
            <svg className={`w-4 h-4 text-muted transition-transform ${showSamples ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" strokeWidth={2} stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" d="m19.5 8.25-7.5 7.5-7.5-7.5" />
            </svg>
          </button>
        </div>

        {showSamples && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {filteredSamples.map(sample => {
              const color = getProtocolColor(sample.protocol)
              return (
                <div key={sample.id} className={`rounded-xl border ${color.border} ${color.bg} p-4 space-y-3`}>
                  <div className="flex items-start justify-between">
                    <div>
                      <div className="flex items-center gap-2 mb-1">
                        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${color.badge}`}>
                          {sample.protocol.replace('_', '-')}
                        </span>
                        <h3 className="font-semibold text-primary text-sm">{sample.title}</h3>
                      </div>
                      <p className="text-xs text-gray-600">{sample.description}</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 text-xs">
                    <div className="flex items-center gap-1.5 bg-surface/70 rounded-lg px-2.5 py-1.5">
                      <FolderIcon className="w-3.5 h-3.5 text-blue-500" />
                      <span className="font-mono text-primary">{sample.sourcePath}</span>
                      <span className="text-muted text-[10px]">({sample.sourceHint})</span>
                    </div>
                    <ArrowRightIcon className="w-3.5 h-3.5 text-muted flex-shrink-0" />
                    <div className="flex items-center gap-1.5 bg-surface/70 rounded-lg px-2.5 py-1.5">
                      <FolderIcon className="w-3.5 h-3.5 text-green-500" />
                      <span className="font-mono text-primary">{sample.destinationPath}</span>
                      <span className="text-muted text-[10px]">({sample.destHint})</span>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    {sample.filenamePattern && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-surface/70 text-xs font-mono text-gray-600">
                        <FunnelIcon className="w-3 h-3" /> {sample.filenamePattern}
                      </span>
                    )}
                    {sample.encryptionOption !== 'NONE' && (
                      <span className="inline-flex items-center px-2 py-0.5 rounded bg-surface/70 text-xs font-medium text-gray-600">
                        {sample.encryptionOption === 'ENCRYPT_BEFORE_FORWARD' ? 'Encrypt' : 'Decrypt'}
                      </span>
                    )}
                  </div>

                  <button
                    onClick={() => openCreateFromSample(sample)}
                    className={`w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg border ${color.border} bg-surface text-sm font-medium ${color.text} hover:shadow-sm transition-all`}
                  >
                    <DocumentDuplicateIcon className="w-4 h-4" />
                    Use This Template
                  </button>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* Empty state when truly no mappings */}
      {mappings.length === 0 && (
        <div className="card border-dashed border-2 border-border bg-canvas/50">
          <div className="text-center py-6">
            <ServerStackIcon className="w-10 h-10 text-gray-300 mx-auto mb-3" />
            <h3 className="text-lg font-semibold text-primary">No Folder Mappings Yet</h3>
            <p className="text-sm text-secondary mt-1 max-w-md mx-auto">
              Folder mappings route files from source accounts to destinations. Choose a sample template above
              to get started, or create a custom mapping.
            </p>
            <button className="btn-primary mt-4" onClick={openCreate}>
              <PlusIcon className="w-4 h-4" /> Create Custom Mapping
            </button>
          </div>
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
