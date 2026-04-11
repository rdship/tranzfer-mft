import { useState, useMemo, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { getPartners, createPartner, deletePartner, activatePartner, suspendPartner, getPartnerStats } from '../api/partners'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import Skeleton, { useDelayedFlag } from '../components/Skeleton'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import FormField, { friendlyError } from '../components/FormField'
import ColumnSettingsButton from '../components/ColumnSettingsButton'
import useColumnPrefs from '../hooks/useColumnPrefs'
import {
  PlusIcon,
  TrashIcon,
  BuildingOfficeIcon,
  CheckCircleIcon,
  ClockIcon,
  ExclamationTriangleIcon,
  MagnifyingGlassIcon,
  EyeIcon,
  PlayIcon,
  PauseIcon,
  EllipsisVerticalIcon,
} from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const INDUSTRIES = [
  'Financial Services',
  'Healthcare',
  'Retail',
  'Manufacturing',
  'Technology',
  'Logistics',
  'Government',
  'Other',
]

const PARTNER_TYPES = ['EXTERNAL', 'INTERNAL', 'VENDOR', 'CLIENT']
const PROTOCOLS = ['SFTP', 'FTP', 'AS2', 'AS4', 'HTTPS']
const SLA_TIERS = ['BASIC', 'STANDARD', 'PREMIUM', 'ENTERPRISE']

const STATUS_TABS = [
  { label: 'All', value: '' },
  { label: 'Active', value: 'ACTIVE' },
  { label: 'Pending', value: 'PENDING' },
  { label: 'Suspended', value: 'SUSPENDED' },
  { label: 'Offboarded', value: 'OFFBOARDED' },
]

// ── Column universe for the partner table ────────────────────────────────
// `key` is the stable id persisted in localStorage; never rename without
// bumping the tableKey so stale entries are invalidated.
const PARTNER_COLUMNS = [
  { key: 'company',    label: 'Company' },
  { key: 'type',       label: 'Type' },
  { key: 'protocols',  label: 'Protocols' },
  { key: 'status',     label: 'Status' },
  { key: 'phase',      label: 'Phase' },
  { key: 'slaTier',    label: 'SLA Tier' },
  { key: 'accounts',   label: 'Accounts' },
  { key: 'createdAt',  label: 'Created' },
  { key: 'actions',    label: 'Actions' },
]
const PARTNER_COLUMN_KEYS = PARTNER_COLUMNS.map(c => c.key)
// Hide rarely-needed columns (phase, createdAt) by default — operators can
// opt in via the gear popover. Actions column is always on by default.
const PARTNER_DEFAULT_VISIBLE = ['company', 'type', 'protocols', 'status', 'slaTier', 'accounts', 'actions']

const EMPTY_FORM = {
  companyName: '',
  displayName: '',
  industry: '',
  website: '',
  partnerType: 'EXTERNAL',
  protocolsEnabled: [],
  slaTier: 'STANDARD',
  notes: '',
  primaryContactName: '',
  primaryContactEmail: '',
  primaryContactPhone: '',
}

function parseProtocols(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function statusBadge(status) {
  switch (status) {
    case 'ACTIVE': return 'badge badge-green'
    case 'PENDING': return 'badge badge-yellow'
    case 'SUSPENDED': return 'badge badge-red'
    case 'OFFBOARDED': return 'badge badge-gray'
    default: return 'badge badge-gray'
  }
}

function typeBadge(type) {
  switch (type) {
    case 'EXTERNAL': return 'badge badge-blue'
    case 'INTERNAL': return 'badge badge-purple'
    case 'VENDOR': return 'badge badge-green'
    case 'CLIENT': return 'badge badge-yellow'
    default: return 'badge badge-gray'
  }
}

function slaBadge(tier) {
  switch (tier) {
    case 'ENTERPRISE': return 'badge badge-purple'
    case 'PREMIUM': return 'badge badge-blue'
    case 'STANDARD': return 'badge badge-green'
    case 'BASIC': return 'badge badge-gray'
    default: return 'badge badge-gray'
  }
}

export default function Partners() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [showCreate, setShowCreate] = useState(false)
  const [statusFilter, setStatusFilter] = useState('')
  const [search, setSearch] = useState('')
  const [form, setForm] = useState(EMPTY_FORM)
  const [openActions, setOpenActions] = useState(null)
  const [formErrors, setFormErrors] = useState({})
  const [selected, setSelected] = useState(new Set())
  // 'activate' | 'suspend' | 'delete'. Phase is future — see TODO below.
  const [showBulkConfirm, setShowBulkConfirm] = useState(null)
  const [bulkLoading, setBulkLoading] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState(null)
  const lastClickedIdRef = useRef(null)
  const [sortBy, setSortBy] = useState('companyName')
  const [sortDir, setSortDir] = useState('asc')

  // Column visibility preferences — persisted per-table via localStorage.
  const { isVisible, toggle: toggleColumn, resetToDefaults: resetColumns, visibleKeys: visibleColumnKeys } =
    useColumnPrefs('partners-table', PARTNER_DEFAULT_VISIBLE, PARTNER_COLUMN_KEYS)

  const { data: partners, isLoading } = useQuery({
    queryKey: ['partners', statusFilter],
    queryFn: () => getPartners(statusFilter || undefined),
  })
  // Skeleton only on first fetch; 100ms flash guard (Stability).
  const isFirstLoad = isLoading && !partners
  const showSkeleton = useDelayedFlag(isFirstLoad, 100)
  const partnersList = partners || []

  const { data: stats = {} } = useQuery({
    queryKey: ['partner-stats'],
    queryFn: getPartnerStats,
  })

  const createMut = useMutation({
    mutationFn: createPartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      setShowCreate(false)
      setForm(EMPTY_FORM)
      setFormErrors({})
      toast.success('Partner created')
    },
    onError: err => toast.error(friendlyError(err)),
  })

  const deleteMut = useMutation({
    mutationFn: deletePartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      toast.success('Partner deleted')
    },
    onError: err => toast.error(friendlyError(err)),
  })

  const activateMut = useMutation({
    mutationFn: activatePartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      toast.success('Partner activated')
    },
    onError: err => toast.error(friendlyError(err)),
  })

  const suspendMut = useMutation({
    mutationFn: suspendPartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      toast.success('Partner suspended')
    },
    onError: err => toast.error(friendlyError(err)),
  })

  const totalPartners = (stats.ACTIVE || 0) + (stats.PENDING || 0) + (stats.SUSPENDED || 0) + (stats.OFFBOARDED || 0)

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  const filtered = useMemo(() => {
    const list = partnersList.filter(p =>
      p.companyName?.toLowerCase().includes(search.toLowerCase()) ||
      p.displayName?.toLowerCase().includes(search.toLowerCase())
    )
    const arr = [...list]
    arr.sort((a, b) => {
      const va = a[sortBy] ?? ''
      const vb = b[sortBy] ?? ''
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [partnersList, search, sortBy, sortDir])

  const handleProtocolToggle = (protocol) => {
    setForm(prev => {
      const current = prev.protocolsEnabled
      if (current.includes(protocol)) {
        return { ...prev, protocolsEnabled: current.filter(p => p !== protocol) }
      }
      return { ...prev, protocolsEnabled: [...current, protocol] }
    })
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    const errs = {}
    if (!form.companyName.trim()) errs.companyName = 'Company name is required'
    if (form.primaryContactEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.primaryContactEmail)) {
      errs.primaryContactEmail = 'Please enter a valid email address'
    }
    if (form.website && form.website.trim()) {
      try { new URL(form.website) } catch { errs.website = 'Please enter a valid URL' }
    }
    setFormErrors(errs)
    if (Object.keys(errs).length > 0) return
    createMut.mutate({
      ...form,
      protocolsEnabled: JSON.stringify(form.protocolsEnabled),
    })
  }

  // Clear selection on filter / search change (Stability — selection survives
  // background react-query refetches, but clears on intentional filter).
  useEffect(() => {
    setSelected(new Set())
    lastClickedIdRef.current = null
  }, [statusFilter, search])

  const toggleSelect = (id, event) => {
    // Shift-click range select across the currently filtered list.
    if (event?.shiftKey && lastClickedIdRef.current != null) {
      const ids = filtered.map(p => p.id)
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
    else setSelected(new Set(filtered.map(p => p.id)))
  }

  // Selection-aware flags (Guidance): Activate visible only when something
  // is not already active; Deactivate visible only when something is active.
  const selectedPartners = useMemo(
    () => filtered.filter(p => selected.has(p.id)),
    [filtered, selected],
  )
  const hasNonActiveSelected = selectedPartners.some(p => p.status !== 'ACTIVE')
  const hasActivePartnerSelected = selectedPartners.some(p => p.status === 'ACTIVE')

  const handleBulkAction = async (action) => {
    const ids = [...selected]
    if (ids.length === 0) return
    setBulkLoading(true)
    try {
      let results, label
      if (action === 'activate') {
        results = await Promise.allSettled(ids.map(id => activatePartner(id)))
        label = 'Activated'
      } else if (action === 'suspend') {
        // There is no dedicated /deactivate partner endpoint — suspendPartner
        // (POST /api/partners/{id}/suspend) is the canonical deactivate path.
        results = await Promise.allSettled(ids.map(id => suspendPartner(id)))
        label = 'Suspended'
      } else if (action === 'delete') {
        results = await Promise.allSettled(ids.map(id => deletePartner(id)))
        label = 'Deleted'
      } else {
        return
      }
      const succeeded = results.filter(r => r.status === 'fulfilled').length
      const failed = results.filter(r => r.status === 'rejected').length
      if (failed === 0) {
        toast.success(`${label} ${succeeded} partner${succeeded !== 1 ? 's' : ''}`)
      } else {
        toast.error(`${label} ${succeeded} of ${ids.length} partners — ${failed} failed`)
      }
      setSelected(new Set())
      setShowBulkConfirm(null)
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
    } finally {
      setBulkLoading(false)
    }
  }
  // TODO(bulk): "Change phase" bulk action — intended to call
  // PUT /api/partners/{id}/phase with a phase dropdown (ACTIVE / INACTIVE /
  // SUSPENDED). Endpoint does not exist in api/partners.js today (only
  // activate + suspend are exposed), so this button is intentionally skipped
  // until the onboarding service surfaces a phase-transition route.

  const handleDelete = (e, id) => {
    e.stopPropagation()
    setDeleteTarget(id)
    setOpenActions(null)
  }

  const handleActivate = (e, id) => {
    e.stopPropagation()
    activateMut.mutate(id)
    setOpenActions(null)
  }

  const handleSuspend = (e, id) => {
    e.stopPropagation()
    suspendMut.mutate(id)
    setOpenActions(null)
  }

  const handleView = (e, id) => {
    e.stopPropagation()
    navigate(`/partners/${id}`)
    setOpenActions(null)
  }

  // Note: removed early return for LoadingSpinner — header/filters stay visible
  // and the table body renders a Skeleton.Table instead (Speed/Guidance).

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-primary">Partner Management</h1>
          <p className="text-secondary text-sm">{totalPartners} trading partners configured</p>
        </div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}>
          <PlusIcon className="w-4 h-4" /> New Partner
        </button>
      </div>

      {/* Stats Bar */}
      <div className="grid grid-cols-4 gap-4">
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-accent-soft rounded-xl flex items-center justify-center">
            <BuildingOfficeIcon className="w-5 h-5 text-accent" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{totalPartners}</p>
            <p className="text-xs text-secondary">Total Partners</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-[rgb(20,60,40)] rounded-xl flex items-center justify-center">
            <CheckCircleIcon className="w-5 h-5 text-[rgb(120,220,160)]" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{stats.ACTIVE || 0}</p>
            <p className="text-xs text-secondary">Active</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-[rgb(60,50,20)] rounded-xl flex items-center justify-center">
            <ClockIcon className="w-5 h-5 text-[rgb(240,200,100)]" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{stats.PENDING || 0}</p>
            <p className="text-xs text-secondary">Pending Setup</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-[rgb(60,20,20)] rounded-xl flex items-center justify-center">
            <ExclamationTriangleIcon className="w-5 h-5 text-[rgb(240,120,120)]" />
          </div>
          <div>
            <p className="text-2xl font-bold text-primary">{stats.SUSPENDED || 0}</p>
            <p className="text-xs text-secondary">Suspended</p>
          </div>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="flex items-center gap-2">
        {STATUS_TABS.map(tab => (
          <button
            key={tab.value}
            onClick={() => setStatusFilter(tab.value)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              statusFilter === tab.value
                ? 'bg-accent text-white'
                : 'bg-surface text-secondary hover:bg-hover'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Search + Column settings */}
      <div className="flex items-center gap-3">
        <div className="relative max-w-sm flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted pointer-events-none" />
          <input
            placeholder="Search by company name..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="pl-10 pr-3 py-2 text-sm border rounded-lg w-full max-w-sm"
          />
        </div>
        <ColumnSettingsButton
          tableKey="partners-table"
          columns={PARTNER_COLUMNS}
          visibleKeys={visibleColumnKeys}
          toggle={toggleColumn}
          resetToDefaults={resetColumns}
        />
      </div>

      {/* Bulk Action Bar */}
      {selected.size > 0 && (
        <div className="sticky top-0 z-10 flex items-center gap-3 bg-[rgba(100,140,255,0.12)] border border-[rgba(100,140,255,0.25)] rounded-xl px-4 py-2 backdrop-blur">
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-[rgba(100,140,255,0.22)] text-primary">
            {selected.size} selected
          </span>
          <button className="text-xs text-secondary hover:text-primary underline" onClick={() => setSelected(new Set())}>Clear</button>
          <div className="flex-1" />
          {hasNonActiveSelected && (
            <button className="btn-secondary text-sm" onClick={() => setShowBulkConfirm('activate')}>Activate</button>
          )}
          {hasActivePartnerSelected && (
            <button className="btn-secondary text-sm" onClick={() => setShowBulkConfirm('suspend')}>Deactivate</button>
          )}
          {/* Change phase intentionally disabled — backing endpoint does not
              exist in api/partners.js yet. See TODO(bulk) above. */}
          <button
            className="btn-secondary text-sm"
            disabled
            title="Change phase — backend endpoint not yet available"
            style={{ opacity: 0.4, cursor: 'not-allowed' }}
          >
            Change phase
          </button>
          <button
            className="btn-secondary text-sm"
            style={{ background: 'rgba(220,38,38,0.15)', color: 'rgb(248,113,113)', border: '1px solid rgba(220,38,38,0.4)' }}
            onClick={() => setShowBulkConfirm('delete')}
          >
            Delete
          </button>
        </div>
      )}

      {/* Partner Table */}
      {isFirstLoad ? (
        <div className="card !p-0 overflow-hidden">
          {showSkeleton ? (
            <Skeleton.Table
              rows={8}
              cols={[24, 180, 80, 140, 80, 100, 80, 60, 100, 48]}
              rowHeight={56}
            />
          ) : (
            <div style={{ minHeight: '480px' }} aria-hidden="true" />
          )}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState
          title="No partners found"
          description={search || statusFilter ? 'Try adjusting your search or filter criteria.' : 'Add your first trading partner to start managing file transfers.'}
          action={
            !search && !statusFilter ? (
              <button className="btn-primary" onClick={() => setShowCreate(true)}>
                <PlusIcon className="w-4 h-4" /> Add First Partner
              </button>
            ) : null
          }
        />
      ) : (
        <div className="card !p-0 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border">
                  <th className="table-header w-8"><input type="checkbox" checked={selected.size === filtered.length && filtered.length > 0} onChange={toggleSelectAll} /></th>
                  {isVisible('company') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('companyName')} aria-sort={sortBy === 'companyName' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Company {sortBy === 'companyName' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                  {isVisible('type') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('partnerType')} aria-sort={sortBy === 'partnerType' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Type {sortBy === 'partnerType' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                  {isVisible('protocols') && <th className="table-header">Protocols</th>}
                  {isVisible('status') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('status')} aria-sort={sortBy === 'status' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Status {sortBy === 'status' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                  {isVisible('phase') && <th className="table-header">Phase</th>}
                  {isVisible('slaTier') && <th className="table-header">SLA Tier</th>}
                  {isVisible('accounts') && <th className="table-header">Accounts</th>}
                  {isVisible('createdAt') && <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('createdAt')} aria-sort={sortBy === 'createdAt' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Created {sortBy === 'createdAt' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>}
                  {isVisible('actions') && <th className="table-header">Actions</th>}
                </tr>
              </thead>
              <tbody>
                {filtered.map(partner => {
                  const protocols = parseProtocols(partner.protocolsEnabled)
                  return (
                    <tr
                      key={partner.id}
                      className="table-row cursor-pointer"
                      onClick={() => navigate(`/partners/${partner.id}`)}
                    >
                      <td className="table-cell" onClick={e => e.stopPropagation()}><input type="checkbox" checked={selected.has(partner.id)} onClick={e => toggleSelect(partner.id, e)} onChange={() => {}} /></td>
                      {isVisible('company') && (
                        <td className="table-cell">
                          <div>
                            <p className="font-semibold text-primary">{partner.companyName}</p>
                            <p className="text-xs text-muted">{partner.slug}</p>
                          </div>
                        </td>
                      )}
                      {isVisible('type') && (
                        <td className="table-cell">
                          <span className={typeBadge(partner.partnerType)}>{partner.partnerType}</span>
                        </td>
                      )}
                      {isVisible('protocols') && (
                        <td className="table-cell">
                          <div className="flex flex-wrap gap-1">
                            {protocols.length > 0 ? protocols.map(proto => (
                              <span key={proto} className="badge badge-gray text-xs">{proto}</span>
                            )) : (
                              <span className="text-xs text-muted">None</span>
                            )}
                          </div>
                        </td>
                      )}
                      {isVisible('status') && (
                        <td className="table-cell">
                          <span className={statusBadge(partner.status)}>{partner.status}</span>
                        </td>
                      )}
                      {isVisible('phase') && (
                        <td className="table-cell">
                          <span className="text-xs text-secondary">{partner.onboardingPhase || '-'}</span>
                        </td>
                      )}
                      {isVisible('slaTier') && (
                        <td className="table-cell">
                          <span className={slaBadge(partner.slaTier)}>{partner.slaTier || '-'}</span>
                        </td>
                      )}
                      {isVisible('accounts') && (
                        <td className="table-cell text-center">
                          <span className="text-sm text-primary">{partner.accountCount ?? 0}</span>
                        </td>
                      )}
                      {isVisible('createdAt') && (
                        <td className="table-cell text-xs text-secondary">
                          {partner.createdAt ? format(new Date(partner.createdAt), 'MMM d, yyyy') : '-'}
                        </td>
                      )}
                      {isVisible('actions') && (
                      <td className="table-cell">
                        <div className="relative">
                          <button
                            onClick={(e) => {
                              e.stopPropagation()
                              setOpenActions(openActions === partner.id ? null : partner.id)
                            }}
                            className="p-1 rounded hover:bg-hover transition-colors"
                          >
                            <EllipsisVerticalIcon className="w-5 h-5 text-muted" />
                          </button>
                          {openActions === partner.id && (
                            <div className="absolute right-0 top-8 z-20 w-44 bg-surface rounded-lg shadow-lg border border-border py-1">
                              <button
                                onClick={(e) => handleView(e, partner.id)}
                                className="w-full flex items-center gap-2 px-4 py-2 text-sm text-primary hover:bg-hover"
                              >
                                <EyeIcon className="w-4 h-4" /> View Details
                              </button>
                              {partner.status !== 'ACTIVE' && partner.status !== 'OFFBOARDED' && (
                                <button
                                  onClick={(e) => handleActivate(e, partner.id)}
                                  className="w-full flex items-center gap-2 px-4 py-2 text-sm text-[rgb(120,220,160)] hover:bg-hover"
                                >
                                  <PlayIcon className="w-4 h-4" /> Activate
                                </button>
                              )}
                              {partner.status === 'ACTIVE' && (
                                <button
                                  onClick={(e) => handleSuspend(e, partner.id)}
                                  className="w-full flex items-center gap-2 px-4 py-2 text-sm text-[rgb(240,200,100)] hover:bg-hover"
                                >
                                  <PauseIcon className="w-4 h-4" /> Suspend
                                </button>
                              )}
                              <button
                                onClick={(e) => handleDelete(e, partner.id)}
                                className="w-full flex items-center gap-2 px-4 py-2 text-sm text-[rgb(240,120,120)] hover:bg-hover"
                              >
                                <TrashIcon className="w-4 h-4" /> Delete
                              </button>
                            </div>
                          )}
                        </div>
                      </td>
                      )}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Create Partner Modal */}
      {showCreate && (
        <Modal title="New Trading Partner" onClose={() => { setShowCreate(false); setForm(EMPTY_FORM); setFormErrors({}) }} size="lg">
          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Company Information */}
            <div>
              <h3 className="text-sm font-semibold text-primary mb-3">Company Information</h3>
              <div className="grid grid-cols-2 gap-4">
                <FormField label="Company Name" required error={formErrors.companyName} helper="Legal entity name used in reports and compliance.">
                  <input
                    placeholder="Acme Corporation"
                    value={form.companyName}
                    onChange={e => { setForm({ ...form, companyName: e.target.value }); setFormErrors(prev => { const n = {...prev}; delete n.companyName; return n }) }}
                    className={formErrors.companyName ? 'border-red-300 focus:ring-red-500' : ''}
                  />
                </FormField>
                <FormField label="Display Name" helper="Short name shown in dashboards. Defaults to company name.">
                  <input
                    placeholder="Acme Corp"
                    value={form.displayName}
                    onChange={e => setForm({ ...form, displayName: e.target.value })}
                  />
                </FormField>
                <FormField label="Industry" helper="Helps configure compliance rules.">
                  <select value={form.industry} onChange={e => setForm({ ...form, industry: e.target.value })}>
                    <option value="">Select industry...</option>
                    {INDUSTRIES.map(ind => (
                      <option key={ind} value={ind}>{ind}</option>
                    ))}
                  </select>
                </FormField>
                <FormField label="Website" error={formErrors.website}>
                  <input
                    type="url"
                    placeholder="https://example.com"
                    value={form.website}
                    onChange={e => { setForm({ ...form, website: e.target.value }); setFormErrors(prev => { const n = {...prev}; delete n.website; return n }) }}
                    className={formErrors.website ? 'border-red-300 focus:ring-red-500' : ''}
                  />
                </FormField>
              </div>
            </div>

            {/* Partner Configuration */}
            <div>
              <h3 className="text-sm font-semibold text-primary mb-3">Partner Configuration</h3>
              <div className="grid grid-cols-2 gap-4">
                <FormField label="Partner Type" helper="Defines how this partner is categorized in reports.">
                  <select value={form.partnerType} onChange={e => setForm({ ...form, partnerType: e.target.value })}>
                    {PARTNER_TYPES.map(type => (
                      <option key={type} value={type}>{type}</option>
                    ))}
                  </select>
                </FormField>
                <FormField label="SLA Tier" helper="Determines transfer limits and retention.">
                  <select value={form.slaTier} onChange={e => setForm({ ...form, slaTier: e.target.value })}>
                    {SLA_TIERS.map(tier => (
                      <option key={tier} value={tier}>{tier}</option>
                    ))}
                  </select>
                </FormField>
              </div>
              <div className="mt-4">
                <label className="block text-sm font-medium text-primary mb-1">Protocols</label>
                <div className="flex flex-wrap gap-3 mt-1">
                  {PROTOCOLS.map(proto => (
                    <label key={proto} className="flex items-center gap-2 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={form.protocolsEnabled.includes(proto)}
                        onChange={() => handleProtocolToggle(proto)}
                        className="rounded border-border"
                      />
                      <span className="text-sm text-primary">{proto}</span>
                    </label>
                  ))}
                </div>
                <p className="mt-1 text-xs text-muted">Select which file transfer protocols this partner will use.</p>
              </div>
            </div>

            {/* Primary Contact */}
            <div>
              <h3 className="text-sm font-semibold text-primary mb-3">Primary Contact</h3>
              <div className="grid grid-cols-3 gap-4">
                <FormField label="Contact Name" helper="Primary point of contact for this partner.">
                  <input
                    placeholder="Jane Smith"
                    value={form.primaryContactName}
                    onChange={e => setForm({ ...form, primaryContactName: e.target.value })}
                  />
                </FormField>
                <FormField label="Email" error={formErrors.primaryContactEmail} helper="Receives transfer notifications.">
                  <input
                    type="email"
                    placeholder="jane@example.com"
                    value={form.primaryContactEmail}
                    onChange={e => { setForm({ ...form, primaryContactEmail: e.target.value }); setFormErrors(prev => { const n = {...prev}; delete n.primaryContactEmail; return n }) }}
                    className={formErrors.primaryContactEmail ? 'border-red-300 focus:ring-red-500' : ''}
                  />
                </FormField>
                <FormField label="Phone">
                  <input
                    type="tel"
                    placeholder="+1 (555) 000-0000"
                    value={form.primaryContactPhone}
                    onChange={e => setForm({ ...form, primaryContactPhone: e.target.value })}
                  />
                </FormField>
              </div>
            </div>

            {/* Notes */}
            <FormField label="Notes" helper="Internal notes visible only to platform administrators.">
              <textarea
                rows={3}
                placeholder="Additional notes about this partner..."
                value={form.notes}
                onChange={e => setForm({ ...form, notes: e.target.value })}
              />
            </FormField>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 pt-4 border-t border-border">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => { setShowCreate(false); setForm(EMPTY_FORM); setFormErrors({}) }}
              >
                Cancel
              </button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>
                {createMut.isPending ? 'Creating...' : 'Create Partner'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Bulk Confirm — danger for delete, warning for lifecycle transitions */}
      <ConfirmDialog
        open={!!showBulkConfirm}
        variant={showBulkConfirm === 'delete' ? 'danger' : 'warning'}
        title={
          showBulkConfirm === 'delete'
            ? `Delete ${selected.size} partner${selected.size !== 1 ? 's' : ''}?`
            : showBulkConfirm === 'activate'
              ? `Activate ${selected.size} partner${selected.size !== 1 ? 's' : ''}?`
              : showBulkConfirm === 'suspend'
                ? `Deactivate ${selected.size} partner${selected.size !== 1 ? 's' : ''}?`
                : ''
        }
        message={
          showBulkConfirm === 'delete'
            ? 'This will permanently remove the selected partners and all their accounts, flows, and endpoints.'
            : showBulkConfirm === 'activate'
              ? 'Selected partners will transition to ACTIVE status and start receiving transfers.'
              : showBulkConfirm === 'suspend'
                ? 'Selected partners will transition to SUSPENDED status and stop receiving transfers.'
                : ''
        }
        confirmLabel={
          showBulkConfirm === 'delete' ? 'Delete all'
          : showBulkConfirm === 'activate' ? 'Activate all'
          : 'Deactivate all'
        }
        cancelLabel="Cancel"
        loading={bulkLoading}
        onConfirm={() => handleBulkAction(showBulkConfirm)}
        onCancel={() => setShowBulkConfirm(null)}
      />

      {/* Delete partner */}
      <ConfirmDialog
        open={!!deleteTarget}
        variant="danger"
        title="Delete partner?"
        message="Are you sure you want to delete this partner? This action cannot be undone."
        confirmLabel="Delete partner"
        cancelLabel="Keep"
        loading={deleteMut.isPending}
        onConfirm={() => { deleteMut.mutate(deleteTarget); setDeleteTarget(null) }}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* Close actions dropdown when clicking outside */}
      {openActions !== null && (
        <div className="fixed inset-0 z-10" onClick={() => setOpenActions(null)} />
      )}
    </div>
  )
}
