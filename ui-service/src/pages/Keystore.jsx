import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate, Link } from 'react-router-dom'
import toast from 'react-hot-toast'
import { format, formatDistanceToNow } from 'date-fns'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import {
  KeyIcon, PlusIcon, ArrowPathIcon, ArrowDownTrayIcon,
  MagnifyingGlassIcon, ShieldCheckIcon, ExclamationTriangleIcon,
  ClipboardDocumentIcon, TrashIcon, EyeIcon, EyeSlashIcon,
  ServerIcon, LockClosedIcon, CommandLineIcon, DocumentArrowUpIcon,
  CheckCircleIcon, ArrowUpTrayIcon, FingerPrintIcon, ClockIcon,
  InformationCircleIcon, XMarkIcon
} from '@heroicons/react/24/outline'
import {
  getKeys, getKeyStats, getExpiringKeys, generateSshHost, generateSshUser,
  generateAes, generateTls, generateHmac, generatePgp, importKey,
  rotateKey, deactivateKey, getPublicKey, getDownloadUrl
} from '../api/keystore'

/* ─── Key type metadata for UX ─── */
const KEY_TYPE_META = {
  SSH_HOST_KEY:    { label: 'SSH Host Key',     icon: ServerIcon,        color: 'blue',   category: 'Protocol' },
  SSH_USER_KEY:    { label: 'SSH User Key',     icon: CommandLineIcon,   color: 'indigo', category: 'Protocol' },
  PGP_PUBLIC:      { label: 'PGP Public',       icon: LockClosedIcon,    color: 'green',  category: 'Encryption' },
  PGP_PRIVATE:     { label: 'PGP Private',      icon: KeyIcon,           color: 'red',    category: 'Encryption' },
  PGP_KEYPAIR:     { label: 'PGP Keypair',      icon: ShieldCheckIcon,   color: 'purple', category: 'Encryption' },
  AES_SYMMETRIC:   { label: 'AES-256',          icon: LockClosedIcon,    color: 'amber',  category: 'Encryption' },
  TLS_CERTIFICATE: { label: 'TLS Certificate',  icon: ShieldCheckIcon,   color: 'green',  category: 'Certificate' },
  TLS_PRIVATE_KEY: { label: 'TLS Private Key',  icon: KeyIcon,           color: 'red',    category: 'Certificate' },
  TLS_KEYSTORE:    { label: 'TLS Keystore',     icon: ServerIcon,        color: 'gray',   category: 'Certificate' },
  HMAC_SECRET:     { label: 'HMAC Secret',      icon: FingerPrintIcon,   color: 'yellow', category: 'Signing' },
  API_KEY:         { label: 'API Key',           icon: CommandLineIcon,   color: 'gray',   category: 'Signing' },
}

/* ─── Generation type definitions ─── */
const GEN_TYPES = [
  {
    id: 'ssh-host', label: 'SSH Host Key', description: 'EC P-256 server identity key for SFTP/SSH',
    icon: ServerIcon, color: 'blue',
    fields: [
      { name: 'alias', label: 'Key Alias', placeholder: 'sftp-host-prod', required: true },
      { name: 'ownerService', label: 'Owner Service', placeholder: 'sftp-service' },
    ]
  },
  {
    id: 'ssh-user', label: 'SSH User Key', description: 'RSA key for partner/user authentication',
    icon: CommandLineIcon, color: 'indigo',
    fields: [
      { name: 'alias', label: 'Key Alias', placeholder: 'partner-acme-ssh', required: true },
      { name: 'partnerAccount', label: 'Partner Account', placeholder: 'acme-corp' },
      { name: 'keySize', label: 'Key Size (bits)', placeholder: '4096', type: 'select', options: ['2048', '4096'] },
    ]
  },
  {
    id: 'pgp', label: 'PGP Keypair', description: 'RSA-4096 keypair for signing & encryption',
    icon: ShieldCheckIcon, color: 'purple',
    fields: [
      { name: 'alias', label: 'Key Alias', placeholder: 'partner-acme-pgp', required: true },
      { name: 'identity', label: 'Identity (name/email)', placeholder: 'Acme Corp <security@acme.com>', required: true },
      { name: 'passphrase', label: 'Passphrase', placeholder: 'Strong passphrase', type: 'password' },
    ]
  },
  {
    id: 'aes', label: 'AES-256 Key', description: 'Symmetric encryption key for file-level encryption',
    icon: LockClosedIcon, color: 'amber',
    fields: [
      { name: 'alias', label: 'Key Alias', placeholder: 'encryption-prod', required: true },
      { name: 'ownerService', label: 'Owner Service', placeholder: 'encryption-service' },
    ]
  },
  {
    id: 'tls', label: 'TLS Certificate', description: 'Self-signed X.509 certificate for HTTPS/TLS',
    icon: ShieldCheckIcon, color: 'green',
    fields: [
      { name: 'alias', label: 'Key Alias', placeholder: 'tls-gateway-prod', required: true },
      { name: 'cn', label: 'Common Name (domain)', placeholder: 'mft.company.com', required: true },
      { name: 'validDays', label: 'Validity (days)', placeholder: '365', type: 'number' },
    ]
  },
  {
    id: 'hmac', label: 'HMAC Secret', description: 'HMAC-SHA256 secret for message signing',
    icon: FingerPrintIcon, color: 'yellow',
    fields: [
      { name: 'alias', label: 'Key Alias', placeholder: 'webhook-hmac', required: true },
      { name: 'ownerService', label: 'Owner Service', placeholder: 'gateway-service' },
    ]
  },
]

const GENERATE_FNS = {
  'ssh-host': generateSshHost, 'ssh-user': generateSshUser, 'pgp': generatePgp,
  'aes': generateAes, 'tls': generateTls, 'hmac': generateHmac,
}

/* ─── Import key types ─── */
const IMPORT_TYPES = [
  'SSH_HOST_KEY', 'SSH_USER_KEY', 'PGP_PUBLIC', 'PGP_PRIVATE', 'PGP_KEYPAIR',
  'AES_SYMMETRIC', 'TLS_CERTIFICATE', 'TLS_PRIVATE_KEY', 'TLS_KEYSTORE', 'HMAC_SECRET', 'API_KEY',
]

/* ─── Filter tabs ─── */
const FILTER_TABS = [
  { key: 'all', label: 'All Keys' },
  { key: 'Protocol', label: 'Protocol' },
  { key: 'Encryption', label: 'Encryption' },
  { key: 'Certificate', label: 'Certificates' },
  { key: 'Signing', label: 'Signing' },
]

export default function Keystore() {
  const qc = useQueryClient()
  const navigate = useNavigate()

  // State
  const [activeTab, setActiveTab] = useState('all')
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState('alias')
  const [sortDir, setSortDir] = useState('asc')
  const [showGenerate, setShowGenerate] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [showDetail, setShowDetail] = useState(null) // key object
  const [showRotateConfirm, setShowRotateConfirm] = useState(null)
  const [showDeactivateConfirm, setShowDeactivateConfirm] = useState(null)
  const [genType, setGenType] = useState(null)
  const [genForm, setGenForm] = useState({})
  const [importForm, setImportForm] = useState({ alias: '', keyType: 'PGP_PUBLIC', keyMaterial: '', description: '', ownerService: '', partnerAccount: '' })
  const [showPublicKey, setShowPublicKey] = useState(null) // { alias, material }
  const [copied, setCopied] = useState(false)

  // Queries
  const { data: keys = [], isLoading } = useQuery({ queryKey: ['keys'], queryFn: () => getKeys() })
  const { data: stats } = useQuery({ queryKey: ['keyStats'], queryFn: getKeyStats })
  const { data: expiringKeys = [] } = useQuery({ queryKey: ['expiringKeys'], queryFn: () => getExpiringKeys(30) })

  // Mutations
  const generateMut = useMutation({
    mutationFn: ({ type, data }) => GENERATE_FNS[type](data),
    onSuccess: (key) => {
      qc.invalidateQueries({ queryKey: ['keys'] })
      qc.invalidateQueries({ queryKey: ['keyStats'] })
      setShowGenerate(false); setGenType(null); setGenForm({})
      toast.success(`Key "${key.alias}" generated successfully`)
    },
    onError: (err) => toast.error(err.response?.data?.message || err.response?.data?.error || 'Generation failed')
  })

  const importMut = useMutation({
    mutationFn: importKey,
    onSuccess: (key) => {
      qc.invalidateQueries({ queryKey: ['keys'] })
      qc.invalidateQueries({ queryKey: ['keyStats'] })
      setShowImport(false); setImportForm({ alias: '', keyType: 'PGP_PUBLIC', keyMaterial: '', description: '', ownerService: '', partnerAccount: '' })
      toast.success(`Key "${key.alias}" imported successfully`)
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Import failed')
  })

  const rotateMut = useMutation({
    mutationFn: (alias) => rotateKey(alias),
    onSuccess: (key) => {
      qc.invalidateQueries({ queryKey: ['keys'] })
      qc.invalidateQueries({ queryKey: ['keyStats'] })
      setShowRotateConfirm(null)
      toast.success(`Key rotated → ${key.alias}`)
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Rotation failed')
  })

  const deactivateMut = useMutation({
    mutationFn: deactivateKey,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['keys'] })
      qc.invalidateQueries({ queryKey: ['keyStats'] })
      setShowDeactivateConfirm(null); setShowDetail(null)
      toast.success('Key deactivated')
    },
    onError: (err) => toast.error(err.response?.data?.message || 'Deactivation failed')
  })

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  // Filtered + sorted keys
  const filteredKeys = useMemo(() => {
    let result = keys
    if (activeTab !== 'all') {
      result = result.filter(k => KEY_TYPE_META[k.keyType]?.category === activeTab)
    }
    if (search) {
      const q = search.toLowerCase()
      result = result.filter(k =>
        k.alias?.toLowerCase().includes(q) ||
        k.keyType?.toLowerCase().includes(q) ||
        k.ownerService?.toLowerCase().includes(q) ||
        k.partnerAccount?.toLowerCase().includes(q) ||
        k.description?.toLowerCase().includes(q)
      )
    }
    const arr = [...result]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'type') {
        va = a.keyType ?? ''; vb = b.keyType ?? ''
      } else if (sortBy === 'status') {
        va = a.status ?? ''; vb = b.status ?? ''
      } else {
        va = a[sortBy] ?? ''; vb = b[sortBy] ?? ''
      }
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [keys, activeTab, search, sortBy, sortDir])

  // Copy to clipboard helper
  const copyToClipboard = async (text) => {
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
    toast.success('Copied to clipboard')
  }

  // Fetch and show public key
  const viewPublicKey = async (alias) => {
    try {
      const material = await getPublicKey(alias)
      setShowPublicKey({ alias, material })
    } catch {
      toast.error('Could not retrieve public key')
    }
  }

  if (isLoading) return <LoadingSpinner text="Loading keystore..." />

  return (
    <div className="space-y-6">
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">Keystore Manager</h1>
          <p className="text-secondary text-sm mt-0.5">
            Central key & certificate management — {keys.length} active key{keys.length !== 1 ? 's' : ''}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button className="btn-secondary" onClick={() => setShowImport(true)}>
            <ArrowUpTrayIcon className="w-4 h-4" /> Import Key
          </button>
          <button className="btn-primary" onClick={() => setShowGenerate(true)}>
            <PlusIcon className="w-4 h-4" /> Generate Key
          </button>
        </div>
      </div>

      {/* ─── Stats Cards ─── */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        <div className="card !p-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-blue-50"><KeyIcon className="w-5 h-5 text-blue-600" /></div>
            <div>
              <p className="text-2xl font-bold text-primary">{stats?.totalActive || keys.length}</p>
              <p className="text-xs text-secondary">Total Active</p>
            </div>
          </div>
        </div>
        {[
          { type: 'Protocol', icon: ServerIcon, color: 'indigo', types: ['SSH_HOST_KEY', 'SSH_USER_KEY'] },
          { type: 'Encryption', icon: LockClosedIcon, color: 'amber', types: ['PGP_PUBLIC', 'PGP_PRIVATE', 'PGP_KEYPAIR', 'AES_SYMMETRIC'] },
          { type: 'Certificates', icon: ShieldCheckIcon, color: 'green', types: ['TLS_CERTIFICATE', 'TLS_PRIVATE_KEY', 'TLS_KEYSTORE'] },
          { type: 'Signing', icon: FingerPrintIcon, color: 'purple', types: ['HMAC_SECRET', 'API_KEY'] },
        ].map(cat => {
          const count = cat.types.reduce((n, t) => n + (stats?.byType?.[t] || 0), 0)
          return (
            <div key={cat.type} className="card !p-4">
              <div className="flex items-center gap-3">
                <div className={`p-2 rounded-lg bg-${cat.color}-50`}><cat.icon className={`w-5 h-5 text-${cat.color}-600`} /></div>
                <div>
                  <p className="text-2xl font-bold text-primary">{count}</p>
                  <p className="text-xs text-secondary">{cat.type}</p>
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* ─── Expiry Warning Banner ─── */}
      {expiringKeys.length > 0 && (
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
          <h3 className="font-semibold text-amber-800 flex items-center gap-2 text-sm">
            <ExclamationTriangleIcon className="w-4 h-4" /> {expiringKeys.length} key{expiringKeys.length > 1 ? 's' : ''} expiring within 30 days
          </h3>
          <div className="mt-2 space-y-1">
            {expiringKeys.map(k => (
              <div key={k.alias} className="flex items-center justify-between text-sm">
                <span className="font-mono text-amber-900">{k.alias}</span>
                <span className="text-amber-600 text-xs">
                  Expires {formatDistanceToNow(new Date(k.expiresAt), { addSuffix: true })}
                </span>
                <button onClick={() => setShowRotateConfirm(k)} className="text-xs text-amber-700 hover:text-amber-900 font-medium">
                  Rotate Now
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ─── Filter Tabs + Search ─── */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1">
          {FILTER_TABS.map(tab => (
            <button key={tab.key} onClick={() => setActiveTab(tab.key)}
              className={`px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                activeTab === tab.key
                  ? 'bg-blue-100 text-blue-700'
                  : 'text-secondary hover:text-primary hover:bg-hover'
              }`}>
              {tab.label}
              {tab.key !== 'all' && (
                <span className="ml-1.5 text-xs opacity-70">
                  {keys.filter(k => KEY_TYPE_META[k.keyType]?.category === tab.key).length}
                </span>
              )}
            </button>
          ))}
        </div>
        <div className="relative">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted pointer-events-none" />
          <input
            value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Search keys..."
            className="pl-10 pr-3 py-1.5 text-sm border rounded-lg w-64 focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          />
        </div>
      </div>

      {/* ─── Keys Table ─── */}
      {filteredKeys.length === 0 ? (
        <EmptyState
          title={search ? 'No keys match your search' : 'No keys yet'}
          description={search
            ? 'Try adjusting your search or filter.'
            : 'Keys are used by accounts (SSH, TLS) and flows (PGP, AES, HMAC). Import your first key or generate a new one.'}
          action={!search && (
            <div className="flex items-center gap-2">
              <button className="btn-secondary" onClick={() => setShowImport(true)}>
                <ArrowUpTrayIcon className="w-4 h-4" /> Import Key
              </button>
              <button className="btn-primary" onClick={() => setShowGenerate(true)}>
                <PlusIcon className="w-4 h-4" /> Generate Key
              </button>
            </div>
          )}
        />
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b bg-canvas/50">
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('alias')} aria-sort={sortBy === 'alias' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Key {sortBy === 'alias' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('type')} aria-sort={sortBy === 'type' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Type {sortBy === 'type' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                <th className="table-header">Algorithm</th>
                <th className="table-header">Owner / Partner</th>
                <th className="table-header">Fingerprint</th>
                <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('status')} aria-sort={sortBy === 'status' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Expires {sortBy === 'status' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
                <th className="table-header">Created</th>
                <th className="table-header text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredKeys.map(k => {
                const meta = KEY_TYPE_META[k.keyType] || { label: k.keyType, color: 'gray', icon: KeyIcon }
                const isExpiring = k.expiresAt && new Date(k.expiresAt) < new Date(Date.now() + 30 * 86400000)
                return (
                  <tr key={k.id} className="table-row cursor-pointer group" onClick={() => setShowDetail(k)}>
                    <td className="table-cell">
                      <div className="flex items-center gap-2.5">
                        <div className={`p-1.5 rounded-lg bg-${meta.color}-50`}>
                          <meta.icon className={`w-4 h-4 text-${meta.color}-600`} />
                        </div>
                        <div>
                          <p className="font-mono text-xs font-bold text-primary">{k.alias}</p>
                          {k.description && <p className="text-xs text-muted truncate max-w-[200px]">{k.description}</p>}
                        </div>
                      </div>
                    </td>
                    <td className="table-cell">
                      <span className={`badge badge-${meta.color}`}>{meta.label}</span>
                    </td>
                    <td className="table-cell text-xs text-secondary font-mono">{k.algorithm || '—'}</td>
                    <td className="table-cell text-xs">
                      {k.ownerService && <span className="badge badge-gray">{k.ownerService}</span>}
                      {k.partnerAccount && <span className="badge badge-blue ml-1">{k.partnerAccount}</span>}
                      {!k.ownerService && !k.partnerAccount && <span className="text-muted">—</span>}
                    </td>
                    <td className="table-cell">
                      <span className="font-mono text-xs text-muted">{k.fingerprint?.substring(0, 16)}...</span>
                    </td>
                    <td className="table-cell text-xs">
                      {k.expiresAt ? (
                        <span className={isExpiring ? 'text-amber-600 font-semibold' : 'text-secondary'}>
                          {isExpiring && <ExclamationTriangleIcon className="w-3.5 h-3.5 inline mr-1" />}
                          {format(new Date(k.expiresAt), 'MMM d, yyyy')}
                        </span>
                      ) : (
                        <span className="text-muted">No expiry</span>
                      )}
                    </td>
                    <td className="table-cell text-xs text-secondary">
                      {format(new Date(k.createdAt), 'MMM d, yyyy')}
                    </td>
                    <td className="table-cell text-right" onClick={e => e.stopPropagation()}>
                      <div className="flex items-center justify-end gap-1">
                        {/*
                          Phase 2 — "View Usage" deep-link. Points at the Accounts page
                          filtered by keyAlias so operators can audit which accounts reference
                          the key before rotating or deactivating it (Guidance + Information
                          transparency).
                          TODO: when keystore-manager exposes a usage-count endpoint (e.g.
                          GET /api/keys/{alias}/usage), show a badge with the live reference
                          count next to this link. Today no such endpoint exists, so we avoid
                          an inaccurate mock count (principle #2 — Accuracy over guessing).
                        */}
                        <Link
                          to={`/accounts?keyAlias=${encodeURIComponent(k.alias)}`}
                          title="View accounts referencing this key"
                          aria-label="View usage"
                          className="p-1.5 rounded-lg hover:bg-hover text-muted hover:text-purple-600"
                        >
                          <MagnifyingGlassIcon className="w-4 h-4" />
                        </Link>
                        {k.publicKeyMaterial && (
                          <button onClick={() => viewPublicKey(k.alias)}
                            className="p-1.5 rounded-lg hover:bg-hover text-muted hover:text-blue-600" title="View Public Key" aria-label="View Public Key">
                            <EyeIcon className="w-4 h-4" />
                          </button>
                        )}
                        <a href={getDownloadUrl(k.alias, 'public')} download
                          className="p-1.5 rounded-lg hover:bg-hover text-muted hover:text-green-600" title="Download" aria-label="Download">
                          <ArrowDownTrayIcon className="w-4 h-4" />
                        </a>
                        {['AES_SYMMETRIC', 'SSH_HOST_KEY', 'HMAC_SECRET'].includes(k.keyType) && (
                          <button onClick={() => setShowRotateConfirm(k)}
                            className="p-1.5 rounded-lg hover:bg-hover text-muted hover:text-amber-600" title="Rotate" aria-label="Rotate">
                            <ArrowPathIcon className="w-4 h-4" />
                          </button>
                        )}
                        <button onClick={() => setShowDeactivateConfirm(k)}
                          className="p-1.5 rounded-lg hover:bg-hover text-muted hover:text-red-600" title="Deactivate" aria-label="Deactivate">
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

      {/* ═══════════════════════════════════════════════════════════════
          MODAL: Generate Key
         ═══════════════════════════════════════════════════════════════ */}
      {showGenerate && (
        <Modal title={genType ? `Generate ${GEN_TYPES.find(t => t.id === genType)?.label}` : 'Generate Key'} onClose={() => { setShowGenerate(false); setGenType(null); setGenForm({}) }} size="lg">
          {!genType ? (
            /* Step 1: Choose key type */
            <div className="space-y-4">
              <p className="text-sm text-secondary">Select the type of key to generate:</p>
              <div className="grid grid-cols-2 gap-3">
                {GEN_TYPES.map(t => (
                  <button key={t.id} onClick={() => setGenType(t.id)}
                    className={`p-4 rounded-xl border-2 border-border hover:border-${t.color}-400 hover:bg-${t.color}-50/50 text-left transition-all group`}>
                    <div className="flex items-center gap-3">
                      <div className={`p-2 rounded-lg bg-${t.color}-100 text-${t.color}-600`}>
                        <t.icon className="w-5 h-5" />
                      </div>
                      <div>
                        <p className="font-semibold text-primary text-sm">{t.label}</p>
                        <p className="text-xs text-secondary mt-0.5">{t.description}</p>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            /* Step 2: Fill form */
            <form onSubmit={e => { e.preventDefault(); generateMut.mutate({ type: genType, data: genForm }) }} className="space-y-4">
              <button type="button" onClick={() => { setGenType(null); setGenForm({}) }} className="text-sm text-blue-600 hover:text-blue-800 flex items-center gap-1">
                ← Back to key types
              </button>
              {GEN_TYPES.find(t => t.id === genType)?.fields.map(f => (
                <div key={f.name}>
                  <label className="block text-sm font-medium text-primary mb-1">
                    {f.label} {f.required && <span className="text-red-500">*</span>}
                  </label>
                  {f.type === 'select' ? (
                    <select value={genForm[f.name] || f.options[f.options.length - 1]}
                      onChange={e => setGenForm(p => ({ ...p, [f.name]: e.target.value }))}>
                      {f.options.map(o => <option key={o} value={o}>{o}</option>)}
                    </select>
                  ) : (
                    <input
                      type={f.type || 'text'}
                      value={genForm[f.name] || ''}
                      onChange={e => setGenForm(p => ({ ...p, [f.name]: e.target.value }))}
                      placeholder={f.placeholder}
                      required={f.required}
                    />
                  )}
                </div>
              ))}
              <div className="flex gap-3 justify-end pt-3 border-t">
                <button type="button" className="btn-secondary" onClick={() => { setShowGenerate(false); setGenType(null); setGenForm({}) }}>Cancel</button>
                <button type="submit" className="btn-primary" disabled={generateMut.isPending}>
                  {generateMut.isPending ? (
                    <><ArrowPathIcon className="w-4 h-4 animate-spin" /> Generating...</>
                  ) : (
                    <><PlusIcon className="w-4 h-4" /> Generate Key</>
                  )}
                </button>
              </div>
            </form>
          )}
        </Modal>
      )}

      {/* ═══════════════════════════════════════════════════════════════
          MODAL: Import Key
         ═══════════════════════════════════════════════════════════════ */}
      {showImport && (
        <Modal title="Import Key" onClose={() => setShowImport(false)} size="lg">
          <form onSubmit={e => { e.preventDefault(); importMut.mutate(importForm) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Key Alias <span className="text-red-500">*</span></label>
                <input value={importForm.alias} onChange={e => setImportForm(p => ({ ...p, alias: e.target.value }))}
                  placeholder="partner-acme-pgp-pub" required />
              </div>
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Key Type <span className="text-red-500">*</span></label>
                <select value={importForm.keyType} onChange={e => setImportForm(p => ({ ...p, keyType: e.target.value }))}>
                  {IMPORT_TYPES.map(t => (
                    <option key={t} value={t}>{KEY_TYPE_META[t]?.label || t}</option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-primary mb-1">
                Key Material <span className="text-red-500">*</span>
              </label>
              <p className="text-xs text-muted mb-2">Paste PEM-encoded key, certificate, or Base64 material</p>
              <textarea
                value={importForm.keyMaterial}
                onChange={e => setImportForm(p => ({ ...p, keyMaterial: e.target.value }))}
                rows={8}
                className="font-mono text-xs"
                placeholder={"-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...\n-----END PUBLIC KEY-----"}
                required
              />
              <div className="mt-2">
                <label className="inline-flex items-center gap-2 text-sm text-blue-600 hover:text-blue-800 cursor-pointer">
                  <DocumentArrowUpIcon className="w-4 h-4" />
                  <span>Or upload a file</span>
                  <input type="file" className="hidden" accept=".pem,.pub,.key,.crt,.cer,.asc,.gpg,.p12,.jks"
                    onChange={e => {
                      const file = e.target.files?.[0]
                      if (!file) return
                      const reader = new FileReader()
                      reader.onload = () => {
                        const text = reader.result
                        setImportForm(p => ({ ...p, keyMaterial: text, alias: p.alias || file.name.replace(/\.[^.]+$/, '') }))
                      }
                      reader.readAsText(file)
                    }}
                  />
                </label>
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-primary mb-1">Description</label>
              <input value={importForm.description} onChange={e => setImportForm(p => ({ ...p, description: e.target.value }))}
                placeholder="PGP public key from ACME Corp" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Owner Service</label>
                <input value={importForm.ownerService} onChange={e => setImportForm(p => ({ ...p, ownerService: e.target.value }))}
                  placeholder="sftp-service" />
              </div>
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Partner Account</label>
                <input value={importForm.partnerAccount} onChange={e => setImportForm(p => ({ ...p, partnerAccount: e.target.value }))}
                  placeholder="acme-corp" />
              </div>
            </div>
            <div className="flex gap-3 justify-end pt-3 border-t">
              <button type="button" className="btn-secondary" onClick={() => setShowImport(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={importMut.isPending}>
                {importMut.isPending ? (
                  <><ArrowPathIcon className="w-4 h-4 animate-spin" /> Importing...</>
                ) : (
                  <><ArrowUpTrayIcon className="w-4 h-4" /> Import Key</>
                )}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* ═══════════════════════════════════════════════════════════════
          MODAL: Key Detail
         ═══════════════════════════════════════════════════════════════ */}
      {showDetail && (
        <Modal title="Key Details" onClose={() => setShowDetail(null)} size="lg">
          {(() => {
            const k = showDetail
            const meta = KEY_TYPE_META[k.keyType] || { label: k.keyType, color: 'gray', icon: KeyIcon }
            const isExpiring = k.expiresAt && new Date(k.expiresAt) < new Date(Date.now() + 30 * 86400000)
            return (
              <div className="space-y-5">
                {/* Header */}
                <div className="flex items-start gap-4">
                  <div className={`p-3 rounded-xl bg-${meta.color}-100`}>
                    <meta.icon className={`w-6 h-6 text-${meta.color}-600`} />
                  </div>
                  <div className="flex-1">
                    <h3 className="font-mono font-bold text-lg text-primary">{k.alias}</h3>
                    <div className="flex items-center gap-2 mt-1">
                      <span className={`badge badge-${meta.color}`}>{meta.label}</span>
                      {k.algorithm && <span className="badge badge-gray">{k.algorithm}</span>}
                      {k.keySizeBits && <span className="text-xs text-muted">{k.keySizeBits}-bit</span>}
                    </div>
                  </div>
                </div>

                {/* Info Grid */}
                <div className="grid grid-cols-2 gap-4 bg-canvas rounded-xl p-4">
                  {[
                    { label: 'Owner Service', value: k.ownerService },
                    { label: 'Partner Account', value: k.partnerAccount },
                    { label: 'Created', value: k.createdAt ? format(new Date(k.createdAt), 'MMM d, yyyy HH:mm') : null },
                    { label: 'Updated', value: k.updatedAt ? format(new Date(k.updatedAt), 'MMM d, yyyy HH:mm') : null },
                    { label: 'Valid From', value: k.validFrom ? format(new Date(k.validFrom), 'MMM d, yyyy') : null },
                    { label: 'Expires', value: k.expiresAt ? format(new Date(k.expiresAt), 'MMM d, yyyy') : null, warn: isExpiring },
                    { label: 'Subject DN', value: k.subjectDn },
                    { label: 'Issuer DN', value: k.issuerDn },
                  ].filter(f => f.value).map(f => (
                    <div key={f.label}>
                      <p className="text-xs text-secondary">{f.label}</p>
                      <p className={`text-sm font-medium ${f.warn ? 'text-amber-600' : 'text-primary'}`}>
                        {f.warn && <ExclamationTriangleIcon className="w-3.5 h-3.5 inline mr-1" />}
                        {f.value}
                      </p>
                    </div>
                  ))}
                </div>

                {/* Description */}
                {k.description && (
                  <div>
                    <p className="text-xs text-secondary mb-1">Description</p>
                    <p className="text-sm text-primary">{k.description}</p>
                  </div>
                )}

                {/* Fingerprint */}
                <div>
                  <p className="text-xs text-secondary mb-1">SHA-256 Fingerprint</p>
                  <div className="flex items-center gap-2">
                    <code className="text-xs bg-hover px-3 py-2 rounded-lg font-mono text-primary flex-1 break-all">
                      {k.fingerprint}
                    </code>
                    <button onClick={() => copyToClipboard(k.fingerprint)}
                      className="p-2 rounded-lg hover:bg-hover text-muted hover:text-blue-600" title="Copy" aria-label="Copy">
                      <ClipboardDocumentIcon className="w-4 h-4" />
                    </button>
                  </div>
                </div>

                {/* Public key preview */}
                {k.publicKeyMaterial && (
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <p className="text-xs text-secondary">Public Key</p>
                      <button onClick={() => copyToClipboard(k.publicKeyMaterial)}
                        className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
                        <ClipboardDocumentIcon className="w-3.5 h-3.5" /> Copy
                      </button>
                    </div>
                    <pre className="text-xs bg-hover px-3 py-2 rounded-lg font-mono text-secondary max-h-32 overflow-auto whitespace-pre-wrap break-all">
                      {k.publicKeyMaterial}
                    </pre>
                  </div>
                )}

                {/* Actions */}
                <div className="flex items-center gap-3 pt-3 border-t">
                  <a href={getDownloadUrl(k.alias, 'public')} download className="btn-secondary">
                    <ArrowDownTrayIcon className="w-4 h-4" /> Download Public
                  </a>
                  {k.publicKeyMaterial !== k.keyMaterial && (
                    <a href={getDownloadUrl(k.alias, 'private')} download className="btn-secondary">
                      <ArrowDownTrayIcon className="w-4 h-4" /> Download Private
                    </a>
                  )}
                  {['AES_SYMMETRIC', 'SSH_HOST_KEY', 'HMAC_SECRET'].includes(k.keyType) && (
                    <button onClick={() => { setShowDetail(null); setShowRotateConfirm(k) }} className="btn-secondary">
                      <ArrowPathIcon className="w-4 h-4" /> Rotate
                    </button>
                  )}
                  <button onClick={() => { setShowDetail(null); setShowDeactivateConfirm(k) }} className="btn-danger ml-auto">
                    <TrashIcon className="w-4 h-4" /> Deactivate
                  </button>
                </div>
              </div>
            )
          })()}
        </Modal>
      )}

      {/* ═══════════════════════════════════════════════════════════════
          MODAL: Public Key Viewer
         ═══════════════════════════════════════════════════════════════ */}
      {showPublicKey && (
        <Modal title={`Public Key — ${showPublicKey.alias}`} onClose={() => setShowPublicKey(null)}>
          <div className="space-y-4">
            <pre className="text-xs bg-canvas border px-4 py-3 rounded-lg font-mono text-primary max-h-64 overflow-auto whitespace-pre-wrap break-all">
              {showPublicKey.material}
            </pre>
            <div className="flex gap-3 justify-end">
              <button onClick={() => copyToClipboard(showPublicKey.material)} className="btn-secondary">
                <ClipboardDocumentIcon className="w-4 h-4" /> {copied ? 'Copied!' : 'Copy to Clipboard'}
              </button>
              <a href={getDownloadUrl(showPublicKey.alias, 'public')} download className="btn-primary">
                <ArrowDownTrayIcon className="w-4 h-4" /> Download
              </a>
            </div>
          </div>
        </Modal>
      )}

      {/* ═══════════════════════════════════════════════════════════════
          MODAL: Rotate Confirmation
         ═══════════════════════════════════════════════════════════════ */}
      {showRotateConfirm && (
        <Modal title="Rotate Key" onClose={() => setShowRotateConfirm(null)} size="sm">
          <div className="space-y-4">
            <div className="bg-amber-50 border border-amber-200 rounded-lg p-3">
              <p className="text-sm text-amber-800">
                <ExclamationTriangleIcon className="w-4 h-4 inline mr-1" />
                This will deactivate <strong className="font-mono">{showRotateConfirm.alias}</strong> and generate a new replacement key.
                Any service using this key will need to reload.
              </p>
            </div>
            <div className="flex gap-3 justify-end">
              <button className="btn-secondary" onClick={() => setShowRotateConfirm(null)}>Cancel</button>
              <button className="btn-primary" onClick={() => rotateMut.mutate(showRotateConfirm.alias)}
                disabled={rotateMut.isPending}>
                {rotateMut.isPending ? 'Rotating...' : 'Confirm Rotation'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* ═══════════════════════════════════════════════════════════════
          MODAL: Deactivate Confirmation
         ═══════════════════════════════════════════════════════════════ */}
      {showDeactivateConfirm && (
        <Modal title="Deactivate Key" onClose={() => setShowDeactivateConfirm(null)} size="sm">
          <div className="space-y-4">
            <div className="bg-red-50 border border-red-200 rounded-lg p-3">
              <p className="text-sm text-red-800">
                <ExclamationTriangleIcon className="w-4 h-4 inline mr-1" />
                This will deactivate <strong className="font-mono">{showDeactivateConfirm.alias}</strong>.
                It will no longer be available to any service. This action cannot be undone.
              </p>
            </div>
            <div className="flex gap-3 justify-end">
              <button className="btn-secondary" onClick={() => setShowDeactivateConfirm(null)}>Cancel</button>
              <button className="btn-danger" onClick={() => deactivateMut.mutate(showDeactivateConfirm.alias)}
                disabled={deactivateMut.isPending}>
                {deactivateMut.isPending ? 'Deactivating...' : 'Deactivate Key'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
