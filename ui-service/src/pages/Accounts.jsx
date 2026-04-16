import { useState, useMemo, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import { getAccounts, createAccount, updateAccount, deleteAccount, toggleAccount, getServerInstancesActive } from '../api/accounts'
import { onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import FormField, { friendlyError } from '../components/FormField'
import useGentleValidation from '../hooks/useGentleValidation'
import useEnterAdvances from '../hooks/useEnterAdvances'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import { LazyExecutionDetailDrawer as ExecutionDetailDrawer, LazyFileDownloadButton as FileDownloadButton } from '../components/LazyShared'
import ColumnSettingsButton from '../components/ColumnSettingsButton'
import useColumnPrefs from '../hooks/useColumnPrefs'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, PencilSquareIcon, MagnifyingGlassIcon, ClockIcon, XMarkIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const PROTOCOLS = ['SFTP', 'FTP', 'FTP_WEB']

const QOS_PRESETS = {
  HIGH:     { uploadBytesPerSecond: 52428800,  downloadBytesPerSecond: 52428800,  maxConcurrentSessions: 10, priority: 3, burstAllowancePercent: 20 },
  MEDIUM:   { uploadBytesPerSecond: 10485760,  downloadBytesPerSecond: 10485760,  maxConcurrentSessions: 3,  priority: 5, burstAllowancePercent: 10 },
  LOW:      { uploadBytesPerSecond: 1048576,   downloadBytesPerSecond: 1048576,   maxConcurrentSessions: 2,  priority: 8, burstAllowancePercent: 5 },
}

function formatBps(bps) {
  if (!bps || bps === 0) return 'Unlimited'
  if (bps >= 1048576) return `${(bps / 1048576).toFixed(0)} MB/s`
  if (bps >= 1024) return `${(bps / 1024).toFixed(0)} KB/s`
  return `${bps} B/s`
}

function getQosTier(acc) {
  const up = acc.qosUploadBytesPerSecond
  if (!up || up === 0) return { label: 'Unlimited', color: 'badge-purple' }
  if (up >= 52428800) return { label: 'HIGH', color: 'badge-green' }
  if (up >= 10485760) return { label: 'MEDIUM', color: 'badge-blue' }
  return { label: 'LOW', color: 'badge-yellow' }
}

const defaultForm = { protocol: 'SFTP', username: '', password: '', confirmPassword: '', homeDir: '', serverInstance: '', qos: { ...QOS_PRESETS.MEDIUM } }
const defaultEditQos = { uploadBytesPerSecond: '', downloadBytesPerSecond: '', maxConcurrentSessions: '', priority: '', burstAllowancePercent: '' }

// ── Column universe for the accounts table ──────────────────────────────
const ACCOUNT_COLUMNS = [
  { key: 'username',   label: 'Username' },
  { key: 'protocol',   label: 'Protocol' },
  { key: 'server',     label: 'Server' },
  { key: 'qos',        label: 'QoS Tier' },
  { key: 'bandwidth',  label: 'Bandwidth' },
  { key: 'sessions',   label: 'Sessions' },
  { key: 'active',     label: 'Status' },
  { key: 'createdAt',  label: 'Created' },
  { key: 'actions',    label: 'Actions' },
]
const ACCOUNT_COLUMN_KEYS = ACCOUNT_COLUMNS.map(c => c.key)
// Hide verbose QoS detail columns by default — they're available via the edit dialog.
const ACCOUNT_DEFAULT_VISIBLE = ['username', 'protocol', 'server', 'qos', 'active', 'actions']

export default function Accounts() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  // URL params for entity cross-linking (Phase 2): /accounts?partnerId=X or ?serverInstance=Y
  // Principle: Flexibility — filters bound to URL so Partner Detail / Server Instances links share state.
  const [searchParams, setSearchParams] = useSearchParams()
  const partnerIdFilter = searchParams.get('partnerId')
  const serverInstanceFilter = searchParams.get('serverInstance')
  const keyAliasFilter = searchParams.get('keyAlias')
  const clearUrlFilter = (key) => {
    const next = new URLSearchParams(searchParams)
    next.delete(key)
    setSearchParams(next, { replace: true })
  }
  const [showCreate, setShowCreate] = useState(false)
  const [editAccount, setEditAccount] = useState(null)
  const [detailAccount, setDetailAccount] = useState(null)
  const [drawerTrackId, setDrawerTrackId] = useState(null)
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState('username')
  const [sortDir, setSortDir] = useState('asc')
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [form, setForm] = useState({ ...defaultForm })
  const [editQos, setEditQos] = useState({ ...defaultEditQos })
  const [selected, setSelected] = useState(new Set())
  const [showBulkConfirm, setShowBulkConfirm] = useState(null) // 'enable' | 'disable' | 'delete'
  const [bulkLoading, setBulkLoading] = useState(false)
  // lastClickedId powers shift-click range select (nice-to-have). Kept in a ref
  // so it never triggers re-renders.
  const lastClickedIdRef = useRef(null)

  const { data: accounts = [], isLoading } = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })
  const { data: serverInstances = [] } = useQuery({ queryKey: ['server-instances-active'], queryFn: getServerInstancesActive })

  // Column visibility preferences for the accounts table.
  const { isVisible, toggle: toggleColumn, resetToDefaults: resetColumns, visibleKeys: visibleColumnKeys } =
    useColumnPrefs('accounts-table', ACCOUNT_DEFAULT_VISIBLE, ACCOUNT_COLUMN_KEYS)
  const createMut = useMutation({ mutationFn: createAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); setShowCreate(false); setForm({ ...defaultForm }); clearAllErrors(); toast.success('Account created') },
    onError: err => toast.error(friendlyError(err)) })
  const updateMut = useMutation({ mutationFn: ({ id, data }) => updateAccount(id, data),
    onSuccess: () => { qc.invalidateQueries(['accounts']); setEditAccount(null); toast.success('Account updated') },
    onError: err => toast.error(friendlyError(err)) })
  const deleteMut = useMutation({ mutationFn: deleteAccount,
    onSuccess: () => { qc.invalidateQueries(['accounts']); toast.success('Account deleted') },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to delete account — the item may be in use') })
  const toggleMut = useMutation({ mutationFn: ({ id, active }) => toggleAccount(id, active),
    onSuccess: () => qc.invalidateQueries(['accounts']) })

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  const filtered = useMemo(() => {
    // Phase 2 cross-link filters applied client-side (Speed principle — no refetch).
    // If the account payload doesn't carry a matching field we silently fall through
    // rather than hiding everything (Stability — never white-screen via over-filter).
    const list = accounts.filter(a => {
      if (partnerIdFilter && a.partnerId && String(a.partnerId) !== String(partnerIdFilter)) return false
      if (serverInstanceFilter && a.serverInstance && String(a.serverInstance) !== String(serverInstanceFilter)) return false
      if (keyAliasFilter && a.keyAlias && String(a.keyAlias) !== String(keyAliasFilter)) return false
      return (
        a.username?.toLowerCase().includes(search.toLowerCase()) ||
        a.protocol?.toLowerCase().includes(search.toLowerCase())
      )
    })
    const arr = [...list]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'active') {
        va = a.active ? 1 : 0
        vb = b.active ? 1 : 0
      } else {
        va = a[sortBy] ?? ''
        vb = b[sortBy] ?? ''
      }
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [accounts, search, sortBy, sortDir, partnerIdFilter, serverInstanceFilter, keyAliasFilter])

  // Clear selection when the filter surface changes (search / URL filters).
  // Stability principle — selection is preserved across react-query background
  // refetches, but a user-initiated filter change clears stale selections.
  useEffect(() => {
    setSelected(new Set())
    lastClickedIdRef.current = null
  }, [search, partnerIdFilter, serverInstanceFilter, keyAliasFilter])

  const toggleSelect = (id, event) => {
    // Shift-click range select across the currently filtered list (Flexibility).
    if (event?.shiftKey && lastClickedIdRef.current != null) {
      const ids = filtered.map(a => a.id)
      const a = ids.indexOf(lastClickedIdRef.current)
      const b = ids.indexOf(id)
      if (a !== -1 && b !== -1) {
        const [lo, hi] = a < b ? [a, b] : [b, a]
        const range = ids.slice(lo, hi + 1)
        setSelected(s => {
          const n = new Set(s)
          range.forEach(rid => n.add(rid))
          return n
        })
        lastClickedIdRef.current = id
        return
      }
    }
    setSelected(s => {
      const n = new Set(s)
      n.has(id) ? n.delete(id) : n.add(id)
      return n
    })
    lastClickedIdRef.current = id
  }
  const toggleSelectAll = () => {
    if (selected.size === filtered.length) setSelected(new Set())
    else setSelected(new Set(filtered.map(a => a.id)))
  }

  // Counts used to show Activate only when there is something inactive in the
  // selection, Deactivate only when there's something active (Guidance).
  const selectedAccounts = useMemo(
    () => filtered.filter(a => selected.has(a.id)),
    [filtered, selected],
  )
  const hasInactiveSelected = selectedAccounts.some(a => !a.active)
  const hasActiveSelected = selectedAccounts.some(a => a.active)

  const handleBulkAction = async (action) => {
    const ids = [...selected]
    if (ids.length === 0) return
    setBulkLoading(true)
    try {
      // NOTE: the accounts API exposes a single PATCH /api/accounts/{id} with
      // { active: bool } rather than dedicated /activate /deactivate endpoints
      // (see api/accounts.js → toggleAccount). We fan that out for bulk.
      let results, label, noun = 'accounts'
      if (action === 'delete') {
        results = await Promise.allSettled(ids.map(id => deleteAccount(id)))
        label = 'Deleted'
      } else {
        const active = action === 'enable'
        results = await Promise.allSettled(ids.map(id => toggleAccount(id, active)))
        label = active ? 'Activated' : 'Deactivated'
      }
      const succeeded = results.filter(r => r.status === 'fulfilled').length
      const failed = results.filter(r => r.status === 'rejected').length
      if (failed === 0) {
        toast.success(`${label} ${succeeded} ${noun}`)
      } else {
        toast.error(`${label} ${succeeded} of ${ids.length} ${noun} — ${failed} failed`)
      }
      setSelected(new Set())
      setShowBulkConfirm(null)
      qc.invalidateQueries(['accounts'])
    } finally {
      setBulkLoading(false)
    }
  }

  const filteredInstances = serverInstances.filter(s => s.protocol === form.protocol)

  // VIP validation — required fields + gentle confirm-match rule for password.
  // Server instance is only required when the current protocol has at least one
  // active instance to pick from (preserves the existing "Any" behaviour when
  // there's nothing to restrict to).
  const createRules = useMemo(() => {
    const rules = [
      { field: 'username', label: 'Username', required: true },
      {
        field: 'password',
        label: 'Password',
        required: true,
        validate: (v) => (v && v.length < 8 ? 'Make the password at least 8 characters so it holds up' : null),
      },
      {
        field: 'confirmPassword',
        label: 'Password confirmation',
        required: true,
        validate: (v, f) => (v !== f.password ? 'Retype the password so they match' : null),
      },
    ]
    if (filteredInstances.length > 0) {
      rules.push({ field: 'serverInstance', label: 'Server instance', required: true })
    }
    return rules
  }, [filteredInstances.length])

  const {
    errors: createErrors,
    handleSubmit: handleCreateSubmit,
    clearFieldError,
    clearAllErrors,
  } = useGentleValidation({
    rules: createRules,
    onValid: (f) => {
      const { confirmPassword, ...payload } = f
      createMut.mutate(payload)
    },
    recordKind: 'account',
  })

  const onCreateKeyDown = useEnterAdvances({
    onSubmit: () => handleCreateSubmit(form)(null),
  })

  const applyPreset = (tier) => {
    setForm(f => ({ ...f, qos: { ...QOS_PRESETS[tier] } }))
  }

  const openEdit = (acc) => {
    setEditAccount(acc)
    setEditQos({
      uploadBytesPerSecond: acc.qosUploadBytesPerSecond || '',
      downloadBytesPerSecond: acc.qosDownloadBytesPerSecond || '',
      maxConcurrentSessions: acc.qosMaxConcurrentSessions || '',
      priority: acc.qosPriority || '',
      burstAllowancePercent: acc.qosBurstAllowancePercent || '',
    })
  }

  const handleEditSubmit = (e) => {
    e.preventDefault()
    const qos = {}
    if (editQos.uploadBytesPerSecond !== '') qos.uploadBytesPerSecond = Number(editQos.uploadBytesPerSecond)
    if (editQos.downloadBytesPerSecond !== '') qos.downloadBytesPerSecond = Number(editQos.downloadBytesPerSecond)
    if (editQos.maxConcurrentSessions !== '') qos.maxConcurrentSessions = Number(editQos.maxConcurrentSessions)
    if (editQos.priority !== '') qos.priority = Number(editQos.priority)
    if (editQos.burstAllowancePercent !== '') qos.burstAllowancePercent = Number(editQos.burstAllowancePercent)
    updateMut.mutate({ id: editAccount.id, data: { qos } })
  }

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">Transfer Accounts</h1>
          <p className="text-secondary text-sm">{accounts.length} accounts configured</p>
        </div>
        <button className="btn-primary" onClick={() => { setForm({ ...defaultForm }); setShowCreate(true) }}>
          <PlusIcon className="w-4 h-4" /> New Account
        </button>
      </div>

      <div className="card">
        <div className="mb-4 flex items-center gap-3">
          <div className="relative max-w-sm flex-1">
            <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted pointer-events-none" />
            <input placeholder="Search by username or protocol..." value={search} onChange={e => setSearch(e.target.value)} className="pl-10 pr-3 py-2 text-sm border rounded-lg w-full focus:ring-2 focus:ring-accent" />
          </div>
          <ColumnSettingsButton
            tableKey="accounts-table"
            columns={ACCOUNT_COLUMNS}
            visibleKeys={visibleColumnKeys}
            toggle={toggleColumn}
            resetToDefaults={resetColumns}
          />
        </div>

        {/*
          Phase 2 — URL-param filter chips. Shown only when incoming cross-link is active
          (Information transparency + Resilience — user always sees why the list is filtered
          and gets a visible ✕ to clear it).
        */}
        {(partnerIdFilter || serverInstanceFilter || keyAliasFilter) && (
          <div className="flex items-center gap-2 flex-wrap mb-4">
            <span className="text-xs text-muted">Active filters:</span>
            {partnerIdFilter && (
              <button onClick={() => clearUrlFilter('partnerId')} className="inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-700 hover:bg-blue-200" title="Clear partner filter">
                Partner: <span className="font-mono">{partnerIdFilter}</span>
                <XMarkIcon className="w-3 h-3" />
              </button>
            )}
            {serverInstanceFilter && (
              <button onClick={() => clearUrlFilter('serverInstance')} className="inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-700 hover:bg-blue-200" title="Clear server filter">
                Server: <span className="font-mono">{serverInstanceFilter}</span>
                <XMarkIcon className="w-3 h-3" />
              </button>
            )}
            {keyAliasFilter && (
              <button onClick={() => clearUrlFilter('keyAlias')} className="inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-700 hover:bg-blue-200" title="Clear key filter">
                Key: <span className="font-mono">{keyAliasFilter}</span>
                <XMarkIcon className="w-3 h-3" />
              </button>
            )}
          </div>
        )}

        {/* Bulk Action Toolbar — sticky above table, only visible when a
            selection exists (Guidance + Information transparency). */}
        {selected.size > 0 && (
          <div
            className="sticky top-0 z-10 flex items-center gap-3 bg-[rgba(100,140,255,0.12)] border border-[rgba(100,140,255,0.25)] rounded-xl px-4 py-2 mb-4 backdrop-blur"
          >
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-[rgba(100,140,255,0.22)] text-primary">
              {selected.size} selected
            </span>
            <button className="text-xs text-secondary hover:text-primary underline" onClick={() => setSelected(new Set())}>Clear</button>
            <div className="flex-1" />
            {hasInactiveSelected && (
              <button className="btn-secondary text-sm" onClick={() => setShowBulkConfirm('enable')}>Activate</button>
            )}
            {hasActiveSelected && (
              <button className="btn-secondary text-sm" onClick={() => setShowBulkConfirm('disable')}>Deactivate</button>
            )}
            <button
              className="btn-secondary text-sm"
              style={{ background: 'rgba(220,38,38,0.15)', color: 'rgb(248,113,113)', border: '1px solid rgba(220,38,38,0.4)' }}
              onClick={() => setShowBulkConfirm('delete')}
            >
              Delete
            </button>
          </div>
        )}

        {filtered.length === 0 ? (
          <EmptyState title="No accounts found" description="Create your first transfer account to get started." action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />New Account</button>} />
        ) : (
          <>
          <p className="text-xs text-muted mb-2">Tip: Click any row to view account detail with recent transfers. Use the edit button for QoS changes.</p>
          <table className="w-full">
            <thead>
              <tr className="border-b border-border">
                <th className="table-header w-8"><input type="checkbox" checked={selected.size === filtered.length && filtered.length > 0} onChange={toggleSelectAll} /></th>
                {isVisible('username') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('username')} aria-sort={sortBy === 'username' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Username {sortBy === 'username' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                {isVisible('protocol') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('protocol')} aria-sort={sortBy === 'protocol' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Protocol {sortBy === 'protocol' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                {isVisible('server') && <th className="table-header">Server</th>}
                {isVisible('qos') && <th className="table-header">QoS</th>}
                {isVisible('bandwidth') && <th className="table-header">Bandwidth</th>}
                {isVisible('sessions') && <th className="table-header">Sessions</th>}
                {isVisible('active') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('active')} aria-sort={sortBy === 'active' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Status {sortBy === 'active' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                {isVisible('createdAt') && <th className="table-header">Created</th>}
                {isVisible('actions') && <th className="table-header">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {filtered.map(acc => {
                const tier = getQosTier(acc)
                return (
                <tr key={acc.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => setDetailAccount(acc)} onDoubleClick={(e) => { e.stopPropagation(); openEdit(acc) }}>
                  <td className="table-cell" onClick={e => e.stopPropagation()}><input type="checkbox" checked={selected.has(acc.id)} onClick={e => toggleSelect(acc.id, e)} onChange={() => {}} /></td>
                  {/*
                    Phase 2 — If the account payload carries a partnerId, surface it as a
                    deep-link back to PartnerDetail. Falls through to plain username otherwise
                    so we never break layouts on accounts that aren't partner-bound.
                  */}
                  {isVisible('username') && (
                    <td className="table-cell font-medium">
                      {acc.partnerId ? (
                        <span className="flex items-center gap-2">
                          <span>{acc.username}</span>
                          <Link
                            to={`/partners/${acc.partnerId}`}
                            onClick={e => e.stopPropagation()}
                            title="Open partner detail"
                            className="text-[11px] font-normal text-blue-500 hover:text-blue-400 hover:underline"
                          >
                            partner ↗
                          </Link>
                        </span>
                      ) : (
                        acc.username
                      )}
                    </td>
                  )}
                  {isVisible('protocol') && <td className="table-cell"><span className="badge badge-blue">{acc.protocol}</span></td>}
                  {isVisible('server') && <td className="table-cell text-xs text-secondary">{acc.serverInstance || <span className="text-muted">Any</span>}</td>}
                  {isVisible('qos') && <td className="table-cell"><span className={`badge ${tier.color}`}>{tier.label}</span></td>}
                  {isVisible('bandwidth') && (
                    <td className="table-cell text-xs text-secondary">
                      <span title="Upload">&uarr;{formatBps(acc.qosUploadBytesPerSecond)}</span>
                      {' / '}
                      <span title="Download">&darr;{formatBps(acc.qosDownloadBytesPerSecond)}</span>
                    </td>
                  )}
                  {isVisible('sessions') && (
                    <td className="table-cell text-xs text-secondary">
                      {acc.qosMaxConcurrentSessions || '-'}
                    </td>
                  )}
                  {isVisible('active') && (
                    <td className="table-cell">
                      <button onClick={(e) => { e.stopPropagation(); toggleMut.mutate({ id: acc.id, active: !acc.active }) }}
                        className={`badge cursor-pointer ${acc.active ? 'badge-green' : 'badge-red'}`}>
                        {acc.active ? 'Active' : 'Disabled'}
                      </button>
                    </td>
                  )}
                  {isVisible('createdAt') && <td className="table-cell text-secondary text-xs">{acc.createdAt ? format(new Date(acc.createdAt), 'MMM d, yyyy') : '-'}</td>}
                  {isVisible('actions') && (
                  <td className="table-cell">
                    <div className="flex gap-1">
                      {/*
                        Phase 2 — "View Executions" deep-link: jumps to the unified
                        /operations/activity monitor filtered by this account's username.
                        Uses ClockIcon for quick visual language consistency.
                      */}
                      <Link
                        to={`/operations/activity?sourceUsername=${encodeURIComponent(acc.username || '')}`}
                        onClick={e => e.stopPropagation()}
                        title="View recent executions for this account"
                        aria-label="View recent executions for this account"
                        className="p-1.5 rounded hover:bg-[rgba(100,140,255,0.1)] text-blue-400 hover:text-blue-300 transition-colors"
                      >
                        <ClockIcon className="w-4 h-4" />
                      </Link>
                      <button onClick={(e) => { e.stopPropagation(); openEdit(acc) }} title="Edit account" aria-label="Edit account"
                        className="p-1.5 rounded hover:bg-accent-soft text-accent hover:text-accent transition-colors">
                        <PencilSquareIcon className="w-4 h-4" />
                      </button>
                      <button onClick={(e) => { e.stopPropagation(); setConfirmDelete(acc) }} title="Delete account" aria-label="Delete account"
                        className="p-1.5 rounded hover:bg-[rgb(60,20,20)] text-[rgb(240,120,120)] hover:text-[rgb(255,140,140)] transition-colors">
                        <TrashIcon className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                  )}
                </tr>
              )})}
            </tbody>
          </table>
          </>
        )}
      </div>

      {/* Create Account Modal */}
      {showCreate && (
        <Modal title="Create Transfer Account" onClose={() => { setShowCreate(false); clearAllErrors() }}>
          <form
            onSubmit={handleCreateSubmit(form)}
            onKeyDown={onCreateKeyDown}
            className="space-y-4"
          >
            <FormField
              label="Protocol"
              name="protocol"
              helper="Which kind of connection this account accepts — pick the one the partner will use."
              tooltip="SFTP for files over SSH, FTP for legacy TLS/cleartext transfers, FTP_WEB for browser-based uploads. Switching protocols resets the server instance selection."
            >
              <select value={form.protocol} onChange={e => setForm(f => ({ ...f, protocol: e.target.value, serverInstance: '' }))}>
                {PROTOCOLS.map(p => <option key={p}>{p}</option>)}
              </select>
            </FormField>
            <FormField
              label="Storage Mode"
              name="storageMode"
              helper="VIRTUAL (default): files stored in content-addressed storage, accessible from any service. PHYSICAL: legacy local filesystem."
            >
              <select value={form.storageMode || 'VIRTUAL'} onChange={e => setForm(f => ({ ...f, storageMode: e.target.value }))}>
                <option value="VIRTUAL">Virtual (VFS — distributed, recommended)</option>
                <option value="PHYSICAL">Physical (local filesystem — legacy)</option>
              </select>
            </FormField>
            <FormField
              label="Username"
              required
              name="username"
              error={createErrors.username}
              valid={!createErrors.username && !!form.username}
              helper="The login name the partner will use to connect"
              samples={['partner_acme', 'globalsupply_prod', 'firstfed_sftp', 'svc_pacific']}
              onSampleClick={(val) => { setForm(f => ({ ...f, username: val })); clearFieldError('username') }}
            >
              <input
                value={form.username}
                onChange={e => { setForm(f => ({ ...f, username: e.target.value })); clearFieldError('username') }}
                placeholder="e.g. partner_acme"
              />
            </FormField>
            <FormField
              label="Password"
              required
              name="password"
              error={createErrors.password}
              valid={!createErrors.password && form.password.length >= 8}
              helper="We'll never show this again — make it strong (min 8 chars)"
            >
              <input
                type="password"
                value={form.password}
                onChange={e => { setForm(f => ({ ...f, password: e.target.value })); clearFieldError('password') }}
              />
            </FormField>
            <FormField
              label="Confirm password"
              required
              name="confirmPassword"
              error={createErrors.confirmPassword}
              valid={!createErrors.confirmPassword && !!form.confirmPassword && form.confirmPassword === form.password}
              helper="Type it once more so we know it's right"
            >
              <input
                type="password"
                value={form.confirmPassword}
                onChange={e => { setForm(f => ({ ...f, confirmPassword: e.target.value })); clearFieldError('confirmPassword') }}
              />
            </FormField>
            <FormField
              label="Home directory"
              name="homeDir"
              helper="The root folder this account lands in after login. Leave blank to use the server default."
            >
              <input
                value={form.homeDir}
                onChange={e => setForm(f => ({ ...f, homeDir: e.target.value }))}
                placeholder="/data/sftp/partner_acme"
              />
            </FormField>
            {filteredInstances.length > 0 && (
              <FormField
                label="Server instance"
                required
                name="serverInstance"
                error={createErrors.serverInstance}
                valid={!createErrors.serverInstance && !!form.serverInstance}
                helper="Which of the running SFTP/FTP servers this account lives on"
                tooltip={`Each server instance has its own host, port, and TLS config. Restrict this account to one ${form.protocol} instance or leave empty to skip in "Any" mode (currently required because there are active instances to pick from).`}
              >
                <select
                  value={form.serverInstance}
                  onChange={e => { setForm(f => ({ ...f, serverInstance: e.target.value })); clearFieldError('serverInstance') }}
                >
                  <option value="">Pick a server instance…</option>
                  {filteredInstances.map(s => (
                    <option key={s.instanceId} value={s.instanceId}>{s.name} ({s.instanceId})</option>
                  ))}
                </select>
              </FormField>
            )}

            {/* QoS Configuration */}
            <div className="border-t border-border pt-4 mt-4">
              <div className="flex items-center justify-between mb-3">
                <label className="text-sm font-semibold text-primary">Quality of Service</label>
                <div className="flex gap-1.5">
                  {Object.keys(QOS_PRESETS).map(tier => (
                    <button key={tier} type="button" onClick={() => applyPreset(tier)}
                      className="px-2.5 py-1 rounded text-xs font-medium bg-hover hover:bg-surface text-primary transition-colors">
                      {tier}
                    </button>
                  ))}
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="acc-qos-upload" className="text-xs text-secondary">Upload Limit (MB/s)</label>
                  <input id="acc-qos-upload" type="number" min="0" value={form.qos.uploadBytesPerSecond ? Math.round(form.qos.uploadBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, uploadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label htmlFor="acc-qos-download" className="text-xs text-secondary">Download Limit (MB/s)</label>
                  <input id="acc-qos-download" type="number" min="0" value={form.qos.downloadBytesPerSecond ? Math.round(form.qos.downloadBytesPerSecond / 1048576) : ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, downloadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 } }))}
                    placeholder="0 = unlimited" />
                </div>
                <div>
                  <label htmlFor="acc-qos-sessions" className="text-xs text-secondary">Max Concurrent Sessions</label>
                  <input id="acc-qos-sessions" type="number" min="1" max="100" value={form.qos.maxConcurrentSessions || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, maxConcurrentSessions: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label htmlFor="acc-qos-priority" className="text-xs text-secondary">Priority (1=Highest, 10=Lowest)</label>
                  <input id="acc-qos-priority" type="number" min="1" max="10" value={form.qos.priority || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, priority: Number(e.target.value) } }))} />
                </div>
                <div>
                  <label htmlFor="acc-qos-burst" className="text-xs text-secondary">Burst Allowance (%)</label>
                  <input id="acc-qos-burst" type="number" min="0" max="100" value={form.qos.burstAllowancePercent || ''}
                    onChange={e => setForm(f => ({ ...f, qos: { ...f.qos, burstAllowancePercent: Number(e.target.value) } }))} />
                </div>
              </div>
            </div>

            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => { setShowCreate(false); clearAllErrors() }}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
                {createMut.isPending ? 'Creating...' : 'Create Account'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Edit Account QoS Modal */}
      {editAccount && (
        <Modal title={`Edit QoS: ${editAccount.username}`} onClose={() => setEditAccount(null)}>
          <form onSubmit={handleEditSubmit} className="space-y-4">
            <div className="flex gap-1.5 mb-3">
              {Object.entries(QOS_PRESETS).map(([tier, preset]) => (
                <button key={tier} type="button" onClick={() => setEditQos({
                  uploadBytesPerSecond: preset.uploadBytesPerSecond,
                  downloadBytesPerSecond: preset.downloadBytesPerSecond,
                  maxConcurrentSessions: preset.maxConcurrentSessions,
                  priority: preset.priority,
                  burstAllowancePercent: preset.burstAllowancePercent,
                })}
                  className="px-2.5 py-1 rounded text-xs font-medium bg-hover hover:bg-surface text-primary transition-colors">
                  {tier}
                </button>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="edit-qos-upload" className="text-xs text-secondary">Upload Limit (MB/s)</label>
                <input id="edit-qos-upload" type="number" min="0" value={editQos.uploadBytesPerSecond ? Math.round(editQos.uploadBytesPerSecond / 1048576) : ''}
                  onChange={e => setEditQos(q => ({ ...q, uploadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 }))}
                  placeholder="0 = unlimited" />
              </div>
              <div>
                <label htmlFor="edit-qos-download" className="text-xs text-secondary">Download Limit (MB/s)</label>
                <input id="edit-qos-download" type="number" min="0" value={editQos.downloadBytesPerSecond ? Math.round(editQos.downloadBytesPerSecond / 1048576) : ''}
                  onChange={e => setEditQos(q => ({ ...q, downloadBytesPerSecond: e.target.value ? Number(e.target.value) * 1048576 : 0 }))}
                  placeholder="0 = unlimited" />
              </div>
              <div>
                <label htmlFor="edit-qos-sessions" className="text-xs text-secondary">Max Concurrent Sessions</label>
                <input id="edit-qos-sessions" type="number" min="1" max="100" value={editQos.maxConcurrentSessions || ''}
                  onChange={e => setEditQos(q => ({ ...q, maxConcurrentSessions: Number(e.target.value) }))} />
              </div>
              <div>
                <label htmlFor="edit-qos-priority" className="text-xs text-secondary">Priority (1=Highest, 10=Lowest)</label>
                <input id="edit-qos-priority" type="number" min="1" max="10" value={editQos.priority || ''}
                  onChange={e => setEditQos(q => ({ ...q, priority: Number(e.target.value) }))} />
              </div>
              <div>
                <label htmlFor="edit-qos-burst" className="text-xs text-secondary">Burst Allowance (%)</label>
                <input id="edit-qos-burst" type="number" min="0" max="100" value={editQos.burstAllowancePercent || ''}
                  onChange={e => setEditQos(q => ({ ...q, burstAllowancePercent: Number(e.target.value) }))} />
              </div>
            </div>
            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setEditAccount(null)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={updateMut.isPending}>
                {updateMut.isPending ? 'Saving...' : 'Save QoS'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Account Detail Modal with Recent Transfers */}
      {detailAccount && (
        <Modal title={`Account: ${detailAccount.username}`} size="xl" onClose={() => setDetailAccount(null)}>
          <div className="space-y-5">
            {/* Account Info */}
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <span className="text-xs text-secondary">Username</span>
                <p className="font-medium text-primary">{detailAccount.username}</p>
              </div>
              <div>
                <span className="text-xs text-secondary">Protocol</span>
                <p className="font-medium text-primary"><span className="badge badge-blue">{detailAccount.protocol}</span></p>
              </div>
              <div>
                <span className="text-xs text-secondary">Status</span>
                <p><span className={`badge ${detailAccount.active ? 'badge-green' : 'badge-red'}`}>{detailAccount.active ? 'Active' : 'Disabled'}</span></p>
              </div>
              <div>
                <span className="text-xs text-secondary">QoS Tier</span>
                <p><span className={`badge ${getQosTier(detailAccount).color}`}>{getQosTier(detailAccount).label}</span></p>
              </div>
              <div>
                <span className="text-xs text-secondary">Bandwidth</span>
                <p className="text-xs text-primary">&uarr;{formatBps(detailAccount.qosUploadBytesPerSecond)} / &darr;{formatBps(detailAccount.qosDownloadBytesPerSecond)}</p>
              </div>
              <div>
                <span className="text-xs text-secondary">Created</span>
                <p className="text-xs text-primary">{detailAccount.createdAt ? format(new Date(detailAccount.createdAt), 'MMM d, yyyy HH:mm') : '-'}</p>
              </div>
            </div>

            {/* Recent Transfers */}
            <div className="border-t border-border pt-4">
              <h4 className="text-sm font-semibold text-primary mb-3">Recent Transfers</h4>
              <AccountTransfers username={detailAccount.username} navigate={navigate} onTrackClick={setDrawerTrackId} />
            </div>
          </div>
        </Modal>
      )}

      <ConfirmDialog
        open={!!confirmDelete}
        variant="danger"
        title="Delete account?"
        message={confirmDelete ? `Are you sure you want to delete account "${confirmDelete.username}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteMut.isPending}
        onConfirm={() => { deleteMut.mutate(confirmDelete.id); setConfirmDelete(null) }}
        onCancel={() => setConfirmDelete(null)}
      />

      {/* Bulk Confirm — ConfirmDialog primitive (danger for delete, warning
          otherwise). Title includes count for transparency. */}
      <ConfirmDialog
        open={!!showBulkConfirm}
        variant={showBulkConfirm === 'delete' ? 'danger' : 'warning'}
        title={
          showBulkConfirm === 'delete'
            ? `Delete ${selected.size} account${selected.size !== 1 ? 's' : ''}?`
            : showBulkConfirm === 'enable'
              ? `Activate ${selected.size} account${selected.size !== 1 ? 's' : ''}?`
              : showBulkConfirm === 'disable'
                ? `Deactivate ${selected.size} account${selected.size !== 1 ? 's' : ''}?`
                : ''
        }
        message={
          showBulkConfirm === 'delete'
            ? 'This will permanently remove the selected accounts and their folder mappings.'
            : showBulkConfirm === 'enable'
              ? 'Selected accounts will accept new logins and transfers.'
              : 'Selected accounts will be blocked from new logins and transfers.'
        }
        confirmLabel={
          showBulkConfirm === 'delete' ? 'Delete all'
          : showBulkConfirm === 'enable' ? 'Activate all'
          : 'Deactivate all'
        }
        onConfirm={() => handleBulkAction(showBulkConfirm)}
        onCancel={() => setShowBulkConfirm(null)}
        loading={bulkLoading}
      />

      <ExecutionDetailDrawer trackId={drawerTrackId} open={!!drawerTrackId} onClose={() => setDrawerTrackId(null)} />
    </div>
  )
}

/* Sub-component: fetches and displays recent transfers for an account */
function AccountTransfers({ username, navigate, onTrackClick }) {
  const { data, isLoading } = useQuery({
    queryKey: ['account-transfers', username],
    queryFn: () => onboardingApi.get('/api/activity-monitor', {
      params: { sourceUsername: username, size: 10 }
    }).then(r => r.data),
    enabled: !!username,
    staleTime: 10_000,
  })

  const transfers = data?.content || []

  if (isLoading) {
    return (
      <div className="flex justify-center py-6">
        <div className="w-5 h-5 border-2 border-accent border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  if (transfers.length === 0) {
    return <p className="text-sm text-secondary text-center py-4">No recent transfers for this account.</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border">
            <th className="table-header">Filename</th>
            <th className="table-header">Status</th>
            <th className="table-header">Date</th>
            <th className="table-header">Track ID</th>
            <th className="table-header">File</th>
          </tr>
        </thead>
        <tbody>
          {transfers.map((t, i) => (
            <tr key={t.trackId || i} className="table-row hover:bg-[rgba(100,140,255,0.06)]">
              <td className="table-cell text-xs truncate max-w-[160px]" title={t.filename}>{t.filename || '--'}</td>
              <td className="table-cell">
                <span className={`badge ${t.status === 'MOVED_TO_SENT' ? 'badge-green' : t.status === 'FAILED' ? 'badge-red' : t.status === 'PENDING' ? 'badge-yellow' : 'badge-blue'}`}>
                  {t.status?.replace(/_/g, ' ') || '--'}
                </span>
              </td>
              <td className="table-cell text-xs text-secondary font-mono">{t.uploadedAt ? format(new Date(t.uploadedAt), 'MM/dd HH:mm') : '--'}</td>
              <td className="table-cell">
                {t.trackId ? (
                  <button
                    onClick={() => navigate(`/journey?trackId=${encodeURIComponent(t.trackId)}`)}
                    className="text-blue-600 hover:text-blue-800 hover:underline font-mono text-xs truncate max-w-[100px] block"
                    title={t.trackId}
                  >
                    {t.trackId.length > 10 ? t.trackId.substring(0, 10) + '...' : t.trackId}
                  </button>
                ) : <span className="text-muted text-xs">--</span>}
              </td>
              <td className="table-cell">
                {t.trackId && <FileDownloadButton trackId={t.trackId} filename={t.filename} size={t.fileSizeBytes} />}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
