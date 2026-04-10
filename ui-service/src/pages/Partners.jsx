import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { getPartners, createPartner, deletePartner, activatePartner, suspendPartner, getPartnerStats } from '../api/partners'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
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

  const { data: partners = [], isLoading } = useQuery({
    queryKey: ['partners', statusFilter],
    queryFn: () => getPartners(statusFilter || undefined),
  })

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
      toast.success('Partner created')
    },
    onError: err => toast.error(err.response?.data?.error || 'Failed to create partner'),
  })

  const deleteMut = useMutation({
    mutationFn: deletePartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      toast.success('Partner deleted')
    },
    onError: err => toast.error(err.response?.data?.error || 'Failed to delete partner'),
  })

  const activateMut = useMutation({
    mutationFn: activatePartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      toast.success('Partner activated')
    },
    onError: err => toast.error(err.response?.data?.error || 'Failed to activate partner'),
  })

  const suspendMut = useMutation({
    mutationFn: suspendPartner,
    onSuccess: () => {
      qc.invalidateQueries(['partners'])
      qc.invalidateQueries(['partner-stats'])
      toast.success('Partner suspended')
    },
    onError: err => toast.error(err.response?.data?.error || 'Failed to suspend partner'),
  })

  const totalPartners = (stats.ACTIVE || 0) + (stats.PENDING || 0) + (stats.SUSPENDED || 0) + (stats.OFFBOARDED || 0)

  const filtered = partners.filter(p =>
    p.companyName?.toLowerCase().includes(search.toLowerCase()) ||
    p.displayName?.toLowerCase().includes(search.toLowerCase())
  )

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
    if (!form.companyName.trim()) {
      toast.error('Company name is required')
      return
    }
    createMut.mutate({
      ...form,
      protocolsEnabled: JSON.stringify(form.protocolsEnabled),
    })
  }

  const handleDelete = (e, id) => {
    e.stopPropagation()
    if (window.confirm('Are you sure you want to delete this partner? This action cannot be undone.')) {
      deleteMut.mutate(id)
    }
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

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Partner Management</h1>
          <p className="text-gray-500 text-sm">{totalPartners} trading partners configured</p>
        </div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}>
          <PlusIcon className="w-4 h-4" /> New Partner
        </button>
      </div>

      {/* Stats Bar */}
      <div className="grid grid-cols-4 gap-4">
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-blue-100 rounded-xl flex items-center justify-center">
            <BuildingOfficeIcon className="w-5 h-5 text-blue-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{totalPartners}</p>
            <p className="text-xs text-gray-500">Total Partners</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-green-100 rounded-xl flex items-center justify-center">
            <CheckCircleIcon className="w-5 h-5 text-green-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{stats.ACTIVE || 0}</p>
            <p className="text-xs text-gray-500">Active</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-yellow-100 rounded-xl flex items-center justify-center">
            <ClockIcon className="w-5 h-5 text-yellow-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{stats.PENDING || 0}</p>
            <p className="text-xs text-gray-500">Pending Setup</p>
          </div>
        </div>
        <div className="card !p-4 flex items-center gap-4">
          <div className="w-10 h-10 bg-red-100 rounded-xl flex items-center justify-center">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-600" />
          </div>
          <div>
            <p className="text-2xl font-bold text-gray-900">{stats.SUSPENDED || 0}</p>
            <p className="text-xs text-gray-500">Suspended</p>
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
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Search */}
      <div className="relative max-w-sm">
        <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400 pointer-events-none" />
        <input
          placeholder="Search by company name..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="pl-10 pr-3 py-2 text-sm border rounded-lg w-full focus:ring-2 focus:ring-blue-500 max-w-sm"
        />
      </div>

      {/* Partner Table */}
      {filtered.length === 0 ? (
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
                <tr className="border-b border-gray-100">
                  <th className="table-header">Company</th>
                  <th className="table-header">Type</th>
                  <th className="table-header">Protocols</th>
                  <th className="table-header">Status</th>
                  <th className="table-header">Phase</th>
                  <th className="table-header">SLA Tier</th>
                  <th className="table-header">Accounts</th>
                  <th className="table-header">Created</th>
                  <th className="table-header">Actions</th>
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
                      <td className="table-cell">
                        <div>
                          <p className="font-semibold text-gray-900">{partner.companyName}</p>
                          <p className="text-xs text-gray-400">{partner.slug}</p>
                        </div>
                      </td>
                      <td className="table-cell">
                        <span className={typeBadge(partner.partnerType)}>{partner.partnerType}</span>
                      </td>
                      <td className="table-cell">
                        <div className="flex flex-wrap gap-1">
                          {protocols.length > 0 ? protocols.map(proto => (
                            <span key={proto} className="badge badge-gray text-xs">{proto}</span>
                          )) : (
                            <span className="text-xs text-gray-300">None</span>
                          )}
                        </div>
                      </td>
                      <td className="table-cell">
                        <span className={statusBadge(partner.status)}>{partner.status}</span>
                      </td>
                      <td className="table-cell">
                        <span className="text-xs text-gray-500">{partner.onboardingPhase || '-'}</span>
                      </td>
                      <td className="table-cell">
                        <span className={slaBadge(partner.slaTier)}>{partner.slaTier || '-'}</span>
                      </td>
                      <td className="table-cell text-center">
                        <span className="text-sm text-gray-700">{partner.accountCount ?? 0}</span>
                      </td>
                      <td className="table-cell text-xs text-gray-500">
                        {partner.createdAt ? format(new Date(partner.createdAt), 'MMM d, yyyy') : '-'}
                      </td>
                      <td className="table-cell">
                        <div className="relative">
                          <button
                            onClick={(e) => {
                              e.stopPropagation()
                              setOpenActions(openActions === partner.id ? null : partner.id)
                            }}
                            className="p-1 rounded hover:bg-gray-100 transition-colors"
                          >
                            <EllipsisVerticalIcon className="w-5 h-5 text-gray-400" />
                          </button>
                          {openActions === partner.id && (
                            <div className="absolute right-0 top-8 z-20 w-44 bg-white rounded-lg shadow-lg border border-gray-200 py-1">
                              <button
                                onClick={(e) => handleView(e, partner.id)}
                                className="w-full flex items-center gap-2 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
                              >
                                <EyeIcon className="w-4 h-4" /> View Details
                              </button>
                              {partner.status !== 'ACTIVE' && partner.status !== 'OFFBOARDED' && (
                                <button
                                  onClick={(e) => handleActivate(e, partner.id)}
                                  className="w-full flex items-center gap-2 px-4 py-2 text-sm text-green-700 hover:bg-green-50"
                                >
                                  <PlayIcon className="w-4 h-4" /> Activate
                                </button>
                              )}
                              {partner.status === 'ACTIVE' && (
                                <button
                                  onClick={(e) => handleSuspend(e, partner.id)}
                                  className="w-full flex items-center gap-2 px-4 py-2 text-sm text-yellow-700 hover:bg-yellow-50"
                                >
                                  <PauseIcon className="w-4 h-4" /> Suspend
                                </button>
                              )}
                              <button
                                onClick={(e) => handleDelete(e, partner.id)}
                                className="w-full flex items-center gap-2 px-4 py-2 text-sm text-red-700 hover:bg-red-50"
                              >
                                <TrashIcon className="w-4 h-4" /> Delete
                              </button>
                            </div>
                          )}
                        </div>
                      </td>
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
        <Modal title="New Trading Partner" onClose={() => { setShowCreate(false); setForm(EMPTY_FORM) }} size="lg">
          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Company Information */}
            <div>
              <h3 className="text-sm font-semibold text-gray-900 mb-3">Company Information</h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label>Company Name *</label>
                  <input
                    required
                    placeholder="Acme Corporation"
                    value={form.companyName}
                    onChange={e => setForm({ ...form, companyName: e.target.value })}
                  />
                </div>
                <div>
                  <label>Display Name</label>
                  <input
                    placeholder="Acme Corp"
                    value={form.displayName}
                    onChange={e => setForm({ ...form, displayName: e.target.value })}
                  />
                </div>
                <div>
                  <label>Industry</label>
                  <select value={form.industry} onChange={e => setForm({ ...form, industry: e.target.value })}>
                    <option value="">Select industry...</option>
                    {INDUSTRIES.map(ind => (
                      <option key={ind} value={ind}>{ind}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label>Website</label>
                  <input
                    type="url"
                    placeholder="https://example.com"
                    value={form.website}
                    onChange={e => setForm({ ...form, website: e.target.value })}
                  />
                </div>
              </div>
            </div>

            {/* Partner Configuration */}
            <div>
              <h3 className="text-sm font-semibold text-gray-900 mb-3">Partner Configuration</h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label>Partner Type</label>
                  <select value={form.partnerType} onChange={e => setForm({ ...form, partnerType: e.target.value })}>
                    {PARTNER_TYPES.map(type => (
                      <option key={type} value={type}>{type}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label>SLA Tier</label>
                  <select value={form.slaTier} onChange={e => setForm({ ...form, slaTier: e.target.value })}>
                    {SLA_TIERS.map(tier => (
                      <option key={tier} value={tier}>{tier}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="mt-4">
                <label>Protocols</label>
                <div className="flex flex-wrap gap-3 mt-1">
                  {PROTOCOLS.map(proto => (
                    <label key={proto} className="flex items-center gap-2 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={form.protocolsEnabled.includes(proto)}
                        onChange={() => handleProtocolToggle(proto)}
                        className="rounded border-gray-300"
                      />
                      <span className="text-sm text-gray-700">{proto}</span>
                    </label>
                  ))}
                </div>
              </div>
            </div>

            {/* Primary Contact */}
            <div>
              <h3 className="text-sm font-semibold text-gray-900 mb-3">Primary Contact</h3>
              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label>Contact Name</label>
                  <input
                    placeholder="Jane Smith"
                    value={form.primaryContactName}
                    onChange={e => setForm({ ...form, primaryContactName: e.target.value })}
                  />
                </div>
                <div>
                  <label>Email</label>
                  <input
                    type="email"
                    placeholder="jane@example.com"
                    value={form.primaryContactEmail}
                    onChange={e => setForm({ ...form, primaryContactEmail: e.target.value })}
                  />
                </div>
                <div>
                  <label>Phone</label>
                  <input
                    type="tel"
                    placeholder="+1 (555) 000-0000"
                    value={form.primaryContactPhone}
                    onChange={e => setForm({ ...form, primaryContactPhone: e.target.value })}
                  />
                </div>
              </div>
            </div>

            {/* Notes */}
            <div>
              <label>Notes</label>
              <textarea
                rows={3}
                placeholder="Additional notes about this partner..."
                value={form.notes}
                onChange={e => setForm({ ...form, notes: e.target.value })}
              />
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 pt-4 border-t border-gray-100">
              <button
                type="button"
                className="btn-secondary"
                onClick={() => { setShowCreate(false); setForm(EMPTY_FORM) }}
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

      {/* Close actions dropdown when clicking outside */}
      {openActions !== null && (
        <div className="fixed inset-0 z-10" onClick={() => setOpenActions(null)} />
      )}
    </div>
  )
}
