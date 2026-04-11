import React, { useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { friendlyError } from '../components/FormField'
import { format } from 'date-fns'
import {
  ArrowLeftIcon,
  PencilSquareIcon,
  PlayIcon,
  PauseIcon,
  PlusIcon,
  TrashIcon,
  GlobeAltIcon,
  EnvelopeIcon,
  PhoneIcon,
  UserIcon,
  ArrowRightIcon,
  ClockIcon,
  ServerIcon,
  FolderIcon,
  ArrowsRightLeftIcon,
  SignalIcon,
  Cog6ToothIcon,
  BellAlertIcon,
  CheckCircleIcon,
  XCircleIcon,
  KeyIcon,
} from '@heroicons/react/24/outline'
import {
  getPartner,
  updatePartner,
  activatePartner,
  suspendPartner,
  getPartnerAccounts,
  getPartnerFlows,
  getPartnerEndpoints,
} from '../api/partners'
import { onboardingApi } from '../api/client'
import { createAccount } from '../api/accounts'
import ConfirmDialog from '../components/ConfirmDialog'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'

const formatBytes = (bytes) => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
}

const statusBadge = (status) => {
  const map = { ACTIVE: 'badge-green', PENDING: 'badge-yellow', SUSPENDED: 'badge-red', OFFBOARDED: 'badge-gray' }
  return map[status] || 'badge-gray'
}

const phaseBadge = (phase) => {
  const map = { SETUP: 'badge-gray', CREDENTIALS: 'badge-yellow', TESTING: 'badge-blue', LIVE: 'badge-green' }
  return map[phase] || 'badge-gray'
}

const slaTierBadge = (tier) => {
  const map = { BASIC: 'badge-gray', STANDARD: 'badge-blue', PREMIUM: 'badge-purple', ENTERPRISE: 'badge-yellow' }
  return map[tier] || 'badge-gray'
}

const TABS = [
  { key: 'overview', label: 'Overview' },
  { key: 'accounts', label: 'Accounts' },
  { key: 'flows', label: 'Flows' },
  { key: 'endpoints', label: 'Endpoints' },
  { key: 'webhooks', label: 'Webhooks' },
  { key: 'settings', label: 'Settings' },
]

const WEBHOOK_EVENTS = [
  'FILE_RECEIVED', 'FILE_DELIVERED', 'TRANSFER_FAILED', 'TRANSFER_COMPLETE',
  'FLOW_STARTED', 'FLOW_COMPLETED', 'FLOW_FAILED', 'PARTNER_ACTIVATED', 'PARTNER_SUSPENDED'
]

const EMPTY_WEBHOOK = { url: '', events: [], active: true }

const EMPTY_ACCOUNT = { protocol: 'SFTP', username: '', password: '', homeDir: '' }

const EMPTY_CONTACT = { name: '', email: '', phone: '', role: 'Technical', isPrimary: false }

export default function PartnerDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const [activeTab, setActiveTab] = useState('overview')
  const [showAccountModal, setShowAccountModal] = useState(false)
  const [accountForm, setAccountForm] = useState(EMPTY_ACCOUNT)
  const [settingsForm, setSettingsForm] = useState(null)
  const [settingsContacts, setSettingsContacts] = useState([])
  const [showWebhookModal, setShowWebhookModal] = useState(false)
  const [editingWebhook, setEditingWebhook] = useState(null)
  const [webhookForm, setWebhookForm] = useState({ ...EMPTY_WEBHOOK })
  const [deleteWebhookConfirm, setDeleteWebhookConfirm] = useState(null)
  const [showSuspendConfirm, setShowSuspendConfirm] = useState(false)
  const [expandedAccount, setExpandedAccount] = useState(null)
  const [expandedEndpoint, setExpandedEndpoint] = useState(null)
  const [expandedWebhook, setExpandedWebhook] = useState(null)

  // Main partner detail
  const { data: detail, isLoading, isError, error } = useQuery({
    queryKey: ['partner', id],
    queryFn: () => getPartner(id),
    retry: 1,
  })

  // Conditional sub-resource queries
  const { data: accounts = [] } = useQuery({
    queryKey: ['partner-accounts', id],
    queryFn: () => getPartnerAccounts(id),
    enabled: activeTab === 'accounts',
  })

  const { data: flows = [] } = useQuery({
    queryKey: ['partner-flows', id],
    queryFn: () => getPartnerFlows(id),
    enabled: activeTab === 'flows',
  })

  const { data: endpoints = [] } = useQuery({
    queryKey: ['partner-endpoints', id],
    queryFn: () => getPartnerEndpoints(id),
    enabled: activeTab === 'endpoints',
  })

  const { data: webhooks = [], isLoading: loadingWebhooks } = useQuery({
    queryKey: ['partner-webhooks', id],
    queryFn: () => onboardingApi.get(`/api/partner-webhooks?partnerId=${id}`).then(r => r.data),
    enabled: activeTab === 'webhooks',
  })

  // Mutations
  const activateMut = useMutation({
    mutationFn: () => activatePartner(id),
    onSuccess: () => {
      qc.invalidateQueries(['partner', id])
      toast.success('Partner activated')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to activate partner'),
  })

  const suspendMut = useMutation({
    mutationFn: () => suspendPartner(id),
    onSuccess: () => {
      qc.invalidateQueries(['partner', id])
      toast.success('Partner suspended')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to suspend partner'),
  })

  const [accountErrors, setAccountErrors] = useState({})
  const createAccountMut = useMutation({
    mutationFn: (data) => createAccount(data),
    onSuccess: () => {
      qc.invalidateQueries(['partner-accounts', id])
      qc.invalidateQueries(['partner', id])
      setShowAccountModal(false)
      setAccountForm(EMPTY_ACCOUNT)
      setAccountErrors({})
      toast.success('Account created')
    },
    onError: (err) => toast.error(friendlyError(err)),
  })

  const updateMut = useMutation({
    mutationFn: (data) => updatePartner(id, data),
    onSuccess: () => {
      qc.invalidateQueries(['partner', id])
      toast.success('Partner settings updated')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to update partner'),
  })

  const createWebhookMut = useMutation({
    mutationFn: (data) => onboardingApi.post('/api/partner-webhooks', { ...data, partnerId: id }).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['partner-webhooks', id])
      setShowWebhookModal(false); setEditingWebhook(null); setWebhookForm({ ...EMPTY_WEBHOOK })
      toast.success('Webhook created')
    },
    onError: (err) => toast.error(friendlyError(err)),
  })

  const updateWebhookMut = useMutation({
    mutationFn: ({ whId, data }) => onboardingApi.put(`/api/partner-webhooks/${whId}`, data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['partner-webhooks', id])
      setShowWebhookModal(false); setEditingWebhook(null); setWebhookForm({ ...EMPTY_WEBHOOK })
      toast.success('Webhook updated')
    },
    onError: (err) => toast.error(friendlyError(err)),
  })

  const deleteWebhookMut = useMutation({
    mutationFn: (whId) => onboardingApi.delete(`/api/partner-webhooks/${whId}`),
    onSuccess: () => {
      qc.invalidateQueries(['partner-webhooks', id])
      setDeleteWebhookConfirm(null)
      toast.success('Webhook deleted')
    },
    onError: (err) => toast.error(err.response?.data?.error || 'Failed to delete webhook'),
  })

  if (isLoading) return <LoadingSpinner text="Loading partner details..." />

  if (isError) {
    const status = error?.response?.status
    return (
      <div className="space-y-6">
        <button onClick={() => navigate('/partners')} className="flex items-center gap-1 text-sm text-secondary hover:text-primary">
          <ArrowLeftIcon className="w-4 h-4" /> Back to Partners
        </button>
        <div className="card text-center py-12">
          <div className="w-16 h-16 bg-red-50 rounded-2xl flex items-center justify-center mx-auto mb-4">
            <XCircleIcon className="w-8 h-8 text-red-400" />
          </div>
          <h3 className="text-lg font-semibold text-primary mb-2">
            {status === 404 ? 'Partner Not Found' : 'Failed to Load Partner'}
          </h3>
          <p className="text-sm text-secondary max-w-sm mx-auto mb-6">
            {status === 404
              ? 'This partner does not exist or has been removed. It may have been deleted by another user.'
              : `An error occurred while loading this partner (${status || 'network error'}). Please try again.`}
          </p>
          <div className="flex gap-3 justify-center">
            <button onClick={() => navigate('/partners')} className="btn-secondary">
              <ArrowLeftIcon className="w-4 h-4" /> Back to Partners
            </button>
            {status !== 404 && (
              <button onClick={() => qc.invalidateQueries(['partner', id])} className="btn-primary">
                Retry
              </button>
            )}
          </div>
        </div>
      </div>
    )
  }

  if (!detail || !detail.partner) {
    return (
      <div className="space-y-6">
        <button onClick={() => navigate('/partners')} className="flex items-center gap-1 text-sm text-secondary hover:text-primary">
          <ArrowLeftIcon className="w-4 h-4" /> Back to Partners
        </button>
        <div className="card">
          <EmptyState title="Partner not found" description="The partner you're looking for does not exist or has been removed." />
        </div>
      </div>
    )
  }

  const partner = detail.partner
  const contacts = detail.contacts || []
  const accountCount = detail.accountCount || 0
  const flowCount = detail.flowCount || 0
  const endpointCount = detail.endpointCount || 0
  const primaryContact = contacts.find((c) => c.isPrimary) || contacts[0]

  let protocolsEnabled = []
  try {
    protocolsEnabled = partner.protocolsEnabled ? JSON.parse(partner.protocolsEnabled) : []
  } catch {
    protocolsEnabled = []
  }

  // Initialize settings form when switching to settings tab
  const initSettingsForm = () => {
    if (!settingsForm) {
      setSettingsForm({
        displayName: partner.displayName || '',
        industry: partner.industry || '',
        website: partner.website || '',
        logoUrl: partner.logoUrl || '',
        partnerType: partner.partnerType || 'CUSTOMER',
        slaTier: partner.slaTier || 'BASIC',
        maxFileSize: partner.maxFileSize || 0,
        maxTransfersPerDay: partner.maxTransfersPerDay || 0,
        retentionDays: partner.retentionDays || 30,
        notes: partner.notes || '',
      })
      setSettingsContacts(contacts.map((c) => ({ ...c })))
    }
  }

  const handleSaveSettings = (e) => {
    e.preventDefault()
    updateMut.mutate({
      ...settingsForm,
      contacts: settingsContacts,
    })
  }

  const addContact = () => {
    setSettingsContacts([...settingsContacts, { ...EMPTY_CONTACT }])
  }

  const removeContact = (idx) => {
    setSettingsContacts(settingsContacts.filter((_, i) => i !== idx))
  }

  const updateContact = (idx, field, value) => {
    setSettingsContacts(settingsContacts.map((c, i) => (i === idx ? { ...c, [field]: value } : c)))
  }

  return (
    <div className="space-y-6">
      {/* Back Navigation */}
      <button onClick={() => navigate('/partners')} className="flex items-center gap-1 text-sm text-secondary hover:text-primary transition-colors">
        <ArrowLeftIcon className="w-4 h-4" /> Back to Partners
      </button>

      {/* Partner Header */}
      <div className="card">
        <div className="flex items-start justify-between">
          <div>
            <div className="flex items-center gap-3 flex-wrap">
              <h1 className="text-2xl font-bold text-primary">{partner.companyName}</h1>
              <span className={`badge ${statusBadge(partner.status)}`}>{partner.status}</span>
              {partner.onboardingPhase && (
                <span className={`badge ${phaseBadge(partner.onboardingPhase)}`}>{partner.onboardingPhase}</span>
              )}
              {partner.slaTier && (
                <span className={`badge ${slaTierBadge(partner.slaTier)}`}>{partner.slaTier}</span>
              )}
            </div>
            <div className="flex items-center gap-4 mt-2 text-sm text-secondary">
              {partner.slug && <span className="font-mono bg-hover px-2 py-0.5 rounded text-xs">{partner.slug}</span>}
              {partner.industry && <span>{partner.industry}</span>}
              {partner.website && (
                <a href={partner.website} target="_blank" rel="noopener noreferrer" className="text-blue-600 hover:underline flex items-center gap-1">
                  <GlobeAltIcon className="w-3.5 h-3.5" /> {partner.website}
                </a>
              )}
            </div>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              className="btn-secondary"
              onClick={() => {
                setActiveTab('settings')
                initSettingsForm()
              }}
            >
              <PencilSquareIcon className="w-4 h-4" /> Edit
            </button>
            {partner.status === 'ACTIVE' ? (
              <button
                className="btn-danger"
                onClick={() => setShowSuspendConfirm(true)}
                disabled={suspendMut.isPending}
              >
                <PauseIcon className="w-4 h-4" /> {suspendMut.isPending ? 'Suspending...' : 'Suspend'}
              </button>
            ) : partner.status !== 'OFFBOARDED' ? (
              <button className="btn-primary" onClick={() => activateMut.mutate()} disabled={activateMut.isPending}>
                <PlayIcon className="w-4 h-4" /> {activateMut.isPending ? 'Activating...' : 'Activate'}
              </button>
            ) : null}
          </div>
        </div>
      </div>

      {/*
        Cross-link strip — Phase 2 entity cross-linking.
        Principles: Guidance (every entity has a direct CTA), Information transparency
        (counts shown when available), Speed (reuses detail.* counts — no extra fetch),
        Minimalism (single accent per badge, whitespace over decoration).
        Links target the unified /operations/activity path introduced in Phase 1.
      */}
      <div className="flex items-center gap-2 flex-wrap">
        {[
          { to: `/accounts?partnerId=${id}`, icon: UserIcon, label: 'Accounts', count: accountCount, entity: 'accounts' },
          { to: `/flows?partnerId=${id}`, icon: ArrowsRightLeftIcon, label: 'Flows', count: flowCount, entity: 'flows' },
          { to: `/operations/activity?partnerId=${id}`, icon: ClockIcon, label: 'Recent Executions', count: null, entity: 'executions' },
          { to: `/folder-mappings?partnerId=${id}`, icon: FolderIcon, label: 'Folder Mappings', count: null, entity: 'folder mappings' },
          { to: `/keystore?partnerId=${id}`, icon: KeyIcon, label: 'Keys in Use', count: null, entity: 'keys' },
        ].map(({ to, icon: Icon, label, count, entity }) => (
          <Link
            key={label}
            to={to}
            title={count !== null ? `View all ${count} ${entity} for this partner` : `View ${entity} for this partner`}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-border bg-surface text-secondary hover:text-primary hover:border-blue-500 hover:bg-[rgba(100,140,255,0.06)] transition-colors"
          >
            <Icon className="w-3.5 h-3.5" />
            <span>{label}</span>
            {count !== null && count !== undefined && (
              <span className="ml-1 px-1.5 py-0.5 rounded-full text-[10px] font-bold bg-blue-100 text-blue-700">
                {count}
              </span>
            )}
          </Link>
        ))}
      </div>

      {/* Tab Navigation */}
      <div className="border-b border-border">
        <nav className="flex gap-6">
          {TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => {
                setActiveTab(tab.key)
                if (tab.key === 'settings') initSettingsForm()
              }}
              className={`pb-3 text-sm font-medium transition-colors border-b-2 ${
                activeTab === tab.key
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-secondary hover:text-primary'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </nav>
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && (
        <div className="grid lg:grid-cols-3 gap-6">
          {/* Left Column */}
          <div className="lg:col-span-2 space-y-6">
            {/* Company Information */}
            <div className="card">
              <h3 className="text-base font-semibold text-primary mb-4">Company Information</h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-secondary mb-1">Company Name</p>
                  <p className="text-sm font-medium text-primary">{partner.companyName}</p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Display Name</p>
                  <p className="text-sm font-medium text-primary">{partner.displayName || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Industry</p>
                  <p className="text-sm font-medium text-primary">{partner.industry || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Website</p>
                  {partner.website ? (
                    <a href={partner.website} target="_blank" rel="noopener noreferrer" className="text-sm text-blue-600 hover:underline">
                      {partner.website}
                    </a>
                  ) : (
                    <p className="text-sm text-primary">-</p>
                  )}
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Slug</p>
                  <p className="text-sm font-mono text-primary">{partner.slug || '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Partner Type</p>
                  <p className="text-sm font-medium text-primary">{partner.partnerType || '-'}</p>
                </div>
              </div>
            </div>

            {/* SLA Configuration */}
            <div className="card">
              <h3 className="text-base font-semibold text-primary mb-4">SLA Configuration</h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-secondary mb-1">SLA Tier</p>
                  <span className={`badge ${slaTierBadge(partner.slaTier)}`}>{partner.slaTier || 'BASIC'}</span>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Max File Size</p>
                  <p className="text-sm font-medium text-primary">{formatBytes(partner.maxFileSize)}</p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Max Transfers/Day</p>
                  <p className="text-sm font-medium text-primary">{partner.maxTransfersPerDay?.toLocaleString() || '0'}</p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Retention Days</p>
                  <p className="text-sm font-medium text-primary">{partner.retentionDays || 30} days</p>
                </div>
              </div>
            </div>

            {/* Notes */}
            {partner.notes && (
              <div className="card">
                <h3 className="text-base font-semibold text-primary mb-4">Notes</h3>
                <p className="text-sm text-primary whitespace-pre-wrap">{partner.notes}</p>
              </div>
            )}
          </div>

          {/* Right Column */}
          <div className="space-y-6">
            {/* Quick Stats */}
            <div className="card">
              <h3 className="text-base font-semibold text-primary mb-4">Quick Stats</h3>
              <div className="space-y-3">
                <div className="flex items-center justify-between py-2 border-b border-border">
                  <div className="flex items-center gap-2 text-sm text-secondary">
                    <UserIcon className="w-4 h-4" /> Accounts
                  </div>
                  <span className="text-sm font-semibold text-primary">{accountCount}</span>
                </div>
                <div className="flex items-center justify-between py-2 border-b border-border">
                  <div className="flex items-center gap-2 text-sm text-secondary">
                    <ArrowsRightLeftIcon className="w-4 h-4" /> Flows
                  </div>
                  <span className="text-sm font-semibold text-primary">{flowCount}</span>
                </div>
                <div className="flex items-center justify-between py-2">
                  <div className="flex items-center gap-2 text-sm text-secondary">
                    <ServerIcon className="w-4 h-4" /> Endpoints
                  </div>
                  <span className="text-sm font-semibold text-primary">{endpointCount}</span>
                </div>
              </div>
            </div>

            {/* Primary Contact */}
            {primaryContact && (
              <div className="card">
                <h3 className="text-base font-semibold text-primary mb-4">Primary Contact</h3>
                <div className="space-y-3">
                  <div className="flex items-center gap-2 text-sm">
                    <UserIcon className="w-4 h-4 text-muted" />
                    <span className="text-primary">{primaryContact.name}</span>
                  </div>
                  {primaryContact.email && (
                    <div className="flex items-center gap-2 text-sm">
                      <EnvelopeIcon className="w-4 h-4 text-muted" />
                      <a href={`mailto:${primaryContact.email}`} className="text-blue-600 hover:underline">
                        {primaryContact.email}
                      </a>
                    </div>
                  )}
                  {primaryContact.phone && (
                    <div className="flex items-center gap-2 text-sm">
                      <PhoneIcon className="w-4 h-4 text-muted" />
                      <span className="text-primary">{primaryContact.phone}</span>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* Protocols */}
            {protocolsEnabled.length > 0 && (
              <div className="card">
                <h3 className="text-base font-semibold text-primary mb-4">Protocols</h3>
                <div className="flex flex-wrap gap-2">
                  {protocolsEnabled.map((proto) => (
                    <span key={proto} className="badge badge-blue">{proto}</span>
                  ))}
                </div>
              </div>
            )}

            {/* Dates */}
            <div className="card">
              <h3 className="text-base font-semibold text-primary mb-4">Dates</h3>
              <div className="space-y-3">
                <div>
                  <p className="text-xs text-secondary mb-1">Created</p>
                  <p className="text-sm text-primary">
                    {partner.createdAt ? format(new Date(partner.createdAt), 'MMM d, yyyy h:mm a') : '-'}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-secondary mb-1">Last Updated</p>
                  <p className="text-sm text-primary">
                    {partner.updatedAt ? format(new Date(partner.updatedAt), 'MMM d, yyyy h:mm a') : '-'}
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Accounts Tab */}
      {activeTab === 'accounts' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-semibold text-primary">Transfer Accounts</h3>
            <button className="btn-primary" onClick={() => setShowAccountModal(true)}>
              <PlusIcon className="w-4 h-4" /> Add Account
            </button>
          </div>

          {accounts.length === 0 ? (
            <div className="card">
              <EmptyState
                title="No accounts yet"
                description="Create a transfer account for this partner to enable file transfers."
                action={
                  <button className="btn-primary" onClick={() => setShowAccountModal(true)}>
                    <PlusIcon className="w-4 h-4" /> Add Account
                  </button>
                }
              />
            </div>
          ) : (
            <div className="card p-0 overflow-hidden">
              <table className="w-full">
                <thead>
                  <tr>
                    <th className="table-header">Username</th>
                    <th className="table-header">Protocol</th>
                    <th className="table-header">Home Dir</th>
                    <th className="table-header">Status</th>
                    <th className="table-header">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {accounts.map((acct) => (
                    <React.Fragment key={acct.id}>
                      <tr
                        className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
                        onClick={() => setExpandedAccount(expandedAccount === acct.id ? null : acct.id)}
                      >
                        <td className="table-cell font-mono text-sm">{acct.username}</td>
                        <td className="table-cell">
                          <span className="badge badge-blue">{acct.protocol}</span>
                        </td>
                        <td className="table-cell font-mono text-xs text-secondary">{acct.homeDir || '-'}</td>
                        <td className="table-cell">
                          <span className={`badge ${acct.active ? 'badge-green' : 'badge-red'}`}>
                            {acct.active ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                        <td className="table-cell text-secondary text-sm">
                          {acct.createdAt ? format(new Date(acct.createdAt), 'MMM d, yyyy') : '-'}
                        </td>
                      </tr>
                      {expandedAccount === acct.id && (
                        <tr>
                          <td colSpan={5} className="px-4 py-3 bg-canvas/50 border-b border-border">
                            <div className="grid grid-cols-3 gap-4 text-sm">
                              <div>
                                <span className="text-xs text-muted">Username</span>
                                <p className="font-mono text-primary">{acct.username}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Protocol</span>
                                <p className="text-primary">{acct.protocol}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Status</span>
                                <span className={`badge ${acct.active ? 'badge-green' : 'badge-red'}`}>
                                  {acct.active ? 'Active' : 'Inactive'}
                                </span>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Home Directory</span>
                                <p className="font-mono text-xs text-primary">{acct.homeDir || '-'}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Created</span>
                                <p className="text-primary">{acct.createdAt ? format(new Date(acct.createdAt), 'MMM d, yyyy HH:mm') : '-'}</p>
                              </div>
                              {acct.lastLoginAt && (
                                <div>
                                  <span className="text-xs text-muted">Last Login</span>
                                  <p className="text-primary">{format(new Date(acct.lastLoginAt), 'MMM d, yyyy HH:mm')}</p>
                                </div>
                              )}
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Create Account Modal */}
          {showAccountModal && (
            <Modal title="Add Transfer Account" onClose={() => { setShowAccountModal(false); setAccountErrors({}) }}>
              <form
                onSubmit={(e) => {
                  e.preventDefault()
                  const errs = {}
                  if (!accountForm.username.trim()) errs.username = 'Username is required'
                  if (!accountForm.password) errs.password = 'Password is required'
                  else if (accountForm.password.length < 8) errs.password = 'Password must be at least 8 characters'
                  setAccountErrors(errs)
                  if (Object.keys(errs).length > 0) return
                  createAccountMut.mutate({ ...accountForm, partnerId: id })
                }}
                className="space-y-4"
              >
                <div>
                  <label className="block text-sm font-medium text-primary mb-1">Protocol</label>
                  <select value={accountForm.protocol} onChange={(e) => setAccountForm((f) => ({ ...f, protocol: e.target.value }))}>
                    <option value="SFTP">SFTP</option>
                    <option value="FTP">FTP</option>
                    <option value="FTP_WEB">FTP Web</option>
                  </select>
                  <p className="mt-1 text-xs text-muted">Transfer protocol for this account.</p>
                </div>
                <div>
                  <label className="block text-sm font-medium text-primary mb-1">Username <span className="text-red-500">*</span></label>
                  <input
                    value={accountForm.username}
                    onChange={(e) => { setAccountForm((f) => ({ ...f, username: e.target.value })); setAccountErrors(prev => { const n = {...prev}; delete n.username; return n }) }}
                    placeholder="e.g. acme-sftp-user"
                    className={accountErrors.username ? 'border-red-300 focus:ring-red-500' : ''}
                  />
                  {accountErrors.username ? <p className="mt-1 text-xs text-red-600">{accountErrors.username}</p> : <p className="mt-1 text-xs text-muted">Unique login name for this partner's account.</p>}
                </div>
                <div>
                  <label className="block text-sm font-medium text-primary mb-1">Password <span className="text-red-500">*</span></label>
                  <input
                    type="password"
                    value={accountForm.password}
                    onChange={(e) => { setAccountForm((f) => ({ ...f, password: e.target.value })); setAccountErrors(prev => { const n = {...prev}; delete n.password; return n }) }}
                    placeholder="Enter password (min 8 characters)"
                    className={accountErrors.password ? 'border-red-300 focus:ring-red-500' : ''}
                  />
                  {accountErrors.password ? <p className="mt-1 text-xs text-red-600">{accountErrors.password}</p> : <p className="mt-1 text-xs text-muted">Used for protocol authentication. Minimum 8 characters.</p>}
                </div>
                <div>
                  <label className="block text-sm font-medium text-primary mb-1">Home Directory</label>
                  <input
                    value={accountForm.homeDir}
                    onChange={(e) => setAccountForm((f) => ({ ...f, homeDir: e.target.value }))}
                    placeholder="/data/partners/acme"
                  />
                  <p className="mt-1 text-xs text-muted">Root directory for this account. Defaults to auto-generated path.</p>
                </div>
                <div className="flex gap-3 justify-end pt-2 border-t">
                  <button type="button" className="btn-secondary" onClick={() => { setShowAccountModal(false); setAccountErrors({}) }}>
                    Cancel
                  </button>
                  <button type="submit" className="btn-primary" disabled={createAccountMut.isPending}>
                    {createAccountMut.isPending ? 'Creating...' : 'Create Account'}
                  </button>
                </div>
              </form>
            </Modal>
          )}
        </div>
      )}

      {/* Flows Tab */}
      {activeTab === 'flows' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-semibold text-primary">File Flows</h3>
            <Link to="/flows" className="btn-primary">
              <PlusIcon className="w-4 h-4" /> Create Flow
            </Link>
          </div>

          {flows.length === 0 ? (
            <div className="card">
              <EmptyState
                title="No flows configured"
                description="File flows define how files are processed and routed for this partner."
                action={
                  <Link to="/flows" className="btn-primary">
                    <PlusIcon className="w-4 h-4" /> Create Flow
                  </Link>
                }
              />
            </div>
          ) : (
            <div className="grid gap-4">
              {flows.map((flow) => (
                <div key={flow.id} className="card">
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <h4 className="font-semibold text-primary">{flow.name}</h4>
                        <span className={`badge ${flow.status === 'ACTIVE' ? 'badge-green' : flow.status === 'PAUSED' ? 'badge-yellow' : 'badge-gray'}`}>
                          {flow.status}
                        </span>
                      </div>
                      {flow.description && <p className="text-sm text-secondary mt-1">{flow.description}</p>}
                    </div>
                  </div>
                  {flow.steps && flow.steps.length > 0 && (
                    <div className="flex items-center gap-2 mt-3 flex-wrap">
                      {flow.steps.map((step, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <span className="bg-blue-50 text-blue-700 text-xs font-medium px-2.5 py-1 rounded-lg">
                            {step.name || step.type || `Step ${idx + 1}`}
                          </span>
                          {idx < flow.steps.length - 1 && <ArrowRightIcon className="w-3.5 h-3.5 text-muted" />}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Endpoints Tab */}
      {activeTab === 'endpoints' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-semibold text-primary">Delivery Endpoints</h3>
            <Link to="/external-destinations" className="btn-primary">
              <PlusIcon className="w-4 h-4" /> New Endpoint
            </Link>
          </div>

          {endpoints.length === 0 ? (
            <div className="card">
              <EmptyState
                title="No endpoints configured"
                description="Delivery endpoints define where files are sent for this partner."
                action={
                  <Link to="/external-destinations" className="btn-primary">
                    <PlusIcon className="w-4 h-4" /> New Endpoint
                  </Link>
                }
              />
            </div>
          ) : (
            <div className="card p-0 overflow-hidden">
              <table className="w-full">
                <thead>
                  <tr>
                    <th className="table-header">Name</th>
                    <th className="table-header">Protocol</th>
                    <th className="table-header">Host</th>
                    <th className="table-header">Auth Type</th>
                    <th className="table-header">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {endpoints.map((ep) => (
                    <React.Fragment key={ep.id}>
                      <tr
                        className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
                        onClick={() => setExpandedEndpoint(expandedEndpoint === ep.id ? null : ep.id)}
                      >
                        <td className="table-cell font-medium text-primary">{ep.name}</td>
                        <td className="table-cell">
                          <span className="badge badge-blue">{ep.protocol}</span>
                        </td>
                        <td className="table-cell font-mono text-xs text-secondary">{ep.host || '-'}</td>
                        <td className="table-cell text-sm text-secondary">{ep.authType || '-'}</td>
                        <td className="table-cell">
                          <span className={`badge ${ep.active || ep.status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>
                            {ep.active || ep.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                      </tr>
                      {expandedEndpoint === ep.id && (
                        <tr>
                          <td colSpan={5} className="px-4 py-3 bg-canvas/50 border-b border-border">
                            <div className="grid grid-cols-3 gap-4 text-sm">
                              <div>
                                <span className="text-xs text-muted">Name</span>
                                <p className="font-medium text-primary">{ep.name}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Protocol</span>
                                <p className="text-primary">{ep.protocol}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Host</span>
                                <p className="font-mono text-xs text-primary">{ep.host || '-'}</p>
                              </div>
                              {ep.port && (
                                <div>
                                  <span className="text-xs text-muted">Port</span>
                                  <p className="font-mono text-primary">{ep.port}</p>
                                </div>
                              )}
                              <div>
                                <span className="text-xs text-muted">Auth Type</span>
                                <p className="text-primary">{ep.authType || '-'}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Status</span>
                                <span className={`badge ${ep.active || ep.status === 'ACTIVE' ? 'badge-green' : 'badge-red'}`}>
                                  {ep.active || ep.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                                </span>
                              </div>
                              {ep.basePath && (
                                <div className="col-span-3">
                                  <span className="text-xs text-muted">Base Path</span>
                                  <p className="font-mono text-xs text-primary">{ep.basePath}</p>
                                </div>
                              )}
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Webhooks Tab */}
      {activeTab === 'webhooks' && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-semibold text-primary">Partner Webhooks</h3>
            <button className="btn-primary" onClick={() => {
              setEditingWebhook(null); setWebhookForm({ ...EMPTY_WEBHOOK }); setShowWebhookModal(true)
            }}>
              <PlusIcon className="w-4 h-4" /> Add Webhook
            </button>
          </div>

          {loadingWebhooks ? (
            <LoadingSpinner />
          ) : webhooks.length === 0 ? (
            <div className="card">
              <EmptyState
                title="No webhooks configured"
                description="Add a webhook to receive real-time event notifications for this partner."
                action={
                  <button className="btn-primary" onClick={() => {
                    setEditingWebhook(null); setWebhookForm({ ...EMPTY_WEBHOOK }); setShowWebhookModal(true)
                  }}>
                    <PlusIcon className="w-4 h-4" /> Add Webhook
                  </button>
                }
              />
            </div>
          ) : (
            <div className="card p-0 overflow-hidden">
              <table className="w-full">
                <thead>
                  <tr>
                    <th className="table-header">URL</th>
                    <th className="table-header">Events</th>
                    <th className="table-header">Active</th>
                    <th className="table-header">Last Triggered</th>
                    <th className="table-header w-28">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {webhooks.map((wh) => (
                    <React.Fragment key={wh.id}>
                      <tr
                        className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]"
                        onClick={() => setExpandedWebhook(expandedWebhook === wh.id ? null : wh.id)}
                      >
                        <td className="table-cell">
                          <span className="font-mono text-xs text-primary truncate max-w-[300px] block" title={wh.url}>{wh.url}</span>
                        </td>
                        <td className="table-cell">
                          <div className="flex flex-wrap gap-1">
                            {(wh.events || []).map(ev => (
                              <span key={ev} className="badge badge-blue text-xs">{ev}</span>
                            ))}
                            {(!wh.events || wh.events.length === 0) && <span className="text-xs text-muted">All events</span>}
                          </div>
                        </td>
                        <td className="table-cell">
                          {wh.active ? (
                            <span className="inline-flex items-center gap-1 badge badge-green"><CheckCircleIcon className="w-3 h-3" /> Active</span>
                          ) : (
                            <span className="inline-flex items-center gap-1 badge badge-gray"><XCircleIcon className="w-3 h-3" /> Inactive</span>
                          )}
                        </td>
                        <td className="table-cell text-xs text-secondary">
                          {wh.lastTriggeredAt ? format(new Date(wh.lastTriggeredAt), 'MMM d, yyyy HH:mm') : 'Never'}
                        </td>
                        <td className="table-cell" onClick={(e) => e.stopPropagation()}>
                          <div className="flex gap-1">
                            <button
                              onClick={() => {
                                setEditingWebhook(wh)
                                setWebhookForm({ url: wh.url || '', events: wh.events || [], active: wh.active !== false })
                                setShowWebhookModal(true)
                              }}
                              className="p-1 rounded hover:bg-hover" title="Edit webhook" aria-label="Edit webhook"
                            >
                              <PencilSquareIcon className="w-4 h-4 text-secondary" />
                            </button>
                            <button onClick={() => setDeleteWebhookConfirm(wh)} className="p-1 rounded hover:bg-hover" title="Delete webhook" aria-label="Delete webhook">
                              <TrashIcon className="w-4 h-4 text-red-500" />
                            </button>
                          </div>
                        </td>
                      </tr>
                      {expandedWebhook === wh.id && (
                        <tr>
                          <td colSpan={5} className="px-4 py-3 bg-canvas/50 border-b border-border">
                            <div className="grid grid-cols-2 gap-4 text-sm">
                              <div className="col-span-2">
                                <span className="text-xs text-muted">Webhook URL</span>
                                <p className="font-mono text-xs text-primary break-all">{wh.url}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Active</span>
                                <p className={wh.active ? 'text-green-700 font-medium' : 'text-secondary'}>{wh.active ? 'Yes' : 'No'}</p>
                              </div>
                              <div>
                                <span className="text-xs text-muted">Last Triggered</span>
                                <p className="text-primary">{wh.lastTriggeredAt ? format(new Date(wh.lastTriggeredAt), 'MMM d, yyyy HH:mm:ss') : 'Never'}</p>
                              </div>
                              <div className="col-span-2">
                                <span className="text-xs text-muted">Subscribed Events</span>
                                <div className="flex flex-wrap gap-1 mt-1">
                                  {(wh.events || []).length > 0
                                    ? wh.events.map(ev => <span key={ev} className="badge badge-blue text-xs">{ev}</span>)
                                    : <span className="text-xs text-muted">All events</span>}
                                </div>
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Create/Edit Webhook Modal */}
          {showWebhookModal && (
            <Modal title={editingWebhook ? 'Edit Webhook' : 'Create Webhook'} onClose={() => { setShowWebhookModal(false); setEditingWebhook(null) }}>
              <form onSubmit={e => {
                e.preventDefault()
                if (editingWebhook) {
                  updateWebhookMut.mutate({ whId: editingWebhook.id, data: webhookForm })
                } else {
                  createWebhookMut.mutate(webhookForm)
                }
              }} className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-primary mb-1">Webhook URL *</label>
                  <input
                    type="url"
                    value={webhookForm.url}
                    onChange={e => setWebhookForm(f => ({ ...f, url: e.target.value }))}
                    required
                    placeholder="https://partner.com/webhooks/file-events"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-primary mb-1">Events (select which events trigger this webhook)</label>
                  <div className="grid grid-cols-2 gap-2 mt-1">
                    {WEBHOOK_EVENTS.map(ev => (
                      <label key={ev} className="flex items-center gap-2 text-sm cursor-pointer">
                        <input
                          type="checkbox"
                          checked={webhookForm.events.includes(ev)}
                          onChange={e => {
                            if (e.target.checked) {
                              setWebhookForm(f => ({ ...f, events: [...f.events, ev] }))
                            } else {
                              setWebhookForm(f => ({ ...f, events: f.events.filter(x => x !== ev) }))
                            }
                          }}
                          className="rounded border-border text-blue-600"
                        />
                        <span className="text-primary">{ev.replace(/_/g, ' ')}</span>
                      </label>
                    ))}
                  </div>
                  <p className="text-xs text-muted mt-1">Leave all unchecked to receive all events.</p>
                </div>
                <div className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={webhookForm.active}
                    onChange={e => setWebhookForm(f => ({ ...f, active: e.target.checked }))}
                    className="rounded border-border text-blue-600"
                  />
                  <label className="text-sm text-primary">Active</label>
                </div>
                <div className="flex gap-3 justify-end pt-2 border-t">
                  <button type="button" className="btn-secondary" onClick={() => { setShowWebhookModal(false); setEditingWebhook(null) }}>Cancel</button>
                  <button type="submit" className="btn-primary" disabled={createWebhookMut.isPending || updateWebhookMut.isPending}>
                    {(createWebhookMut.isPending || updateWebhookMut.isPending)
                      ? 'Saving...'
                      : editingWebhook ? 'Update Webhook' : 'Create Webhook'}
                  </button>
                </div>
              </form>
            </Modal>
          )}

          {/* Delete webhook confirmation */}
          <ConfirmDialog
            open={!!deleteWebhookConfirm}
            variant="danger"
            title="Delete Webhook"
            message={deleteWebhookConfirm ? `Are you sure you want to delete the webhook for ${deleteWebhookConfirm.url}?` : ''}
            confirmLabel="Delete Webhook"
            cancelLabel="Cancel"
            loading={deleteWebhookMut.isPending}
            onConfirm={() => { deleteWebhookMut.mutate(deleteWebhookConfirm.id); setDeleteWebhookConfirm(null) }}
            onCancel={() => setDeleteWebhookConfirm(null)}
          />
        </div>
      )}

      {/* Settings Tab */}
      {activeTab === 'settings' && settingsForm && (
        <form onSubmit={handleSaveSettings} className="space-y-6">
          {/* General Settings */}
          <div className="card">
            <h3 className="text-base font-semibold text-primary mb-4">General Settings</h3>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label>Display Name</label>
                <input
                  value={settingsForm.displayName}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, displayName: e.target.value }))}
                  placeholder="Partner display name"
                />
              </div>
              <div>
                <label>Industry</label>
                <input
                  value={settingsForm.industry}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, industry: e.target.value }))}
                  placeholder="e.g. Financial Services"
                />
              </div>
              <div>
                <label>Website</label>
                <input
                  value={settingsForm.website}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, website: e.target.value }))}
                  placeholder="https://partner.com"
                />
              </div>
              <div>
                <label>Logo URL</label>
                <input
                  value={settingsForm.logoUrl}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, logoUrl: e.target.value }))}
                  placeholder="https://partner.com/logo.png"
                />
              </div>
              <div>
                <label>Partner Type</label>
                <select value={settingsForm.partnerType} onChange={(e) => setSettingsForm((f) => ({ ...f, partnerType: e.target.value }))}>
                  <option value="CUSTOMER">Customer</option>
                  <option value="VENDOR">Vendor</option>
                  <option value="PARTNER">Partner</option>
                  <option value="INTERNAL">Internal</option>
                </select>
              </div>
              <div>
                <label>SLA Tier</label>
                <select value={settingsForm.slaTier} onChange={(e) => setSettingsForm((f) => ({ ...f, slaTier: e.target.value }))}>
                  <option value="BASIC">Basic</option>
                  <option value="STANDARD">Standard</option>
                  <option value="PREMIUM">Premium</option>
                  <option value="ENTERPRISE">Enterprise</option>
                </select>
              </div>
            </div>
          </div>

          {/* SLA Limits */}
          <div className="card">
            <h3 className="text-base font-semibold text-primary mb-4">SLA Limits</h3>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label>Max File Size (bytes)</label>
                <input
                  type="number"
                  value={settingsForm.maxFileSize}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, maxFileSize: parseInt(e.target.value) || 0 }))}
                  placeholder="104857600"
                />
                <p className="text-xs text-muted mt-1">{formatBytes(settingsForm.maxFileSize)}</p>
              </div>
              <div>
                <label>Max Transfers/Day</label>
                <input
                  type="number"
                  value={settingsForm.maxTransfersPerDay}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, maxTransfersPerDay: parseInt(e.target.value) || 0 }))}
                  placeholder="1000"
                />
              </div>
              <div>
                <label>Retention Days</label>
                <input
                  type="number"
                  value={settingsForm.retentionDays}
                  onChange={(e) => setSettingsForm((f) => ({ ...f, retentionDays: parseInt(e.target.value) || 0 }))}
                  placeholder="30"
                />
              </div>
            </div>
          </div>

          {/* Notes */}
          <div className="card">
            <h3 className="text-base font-semibold text-primary mb-4">Notes</h3>
            <textarea
              rows={4}
              value={settingsForm.notes}
              onChange={(e) => setSettingsForm((f) => ({ ...f, notes: e.target.value }))}
              placeholder="Internal notes about this partner..."
            />
          </div>

          {/* Contacts Management */}
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-base font-semibold text-primary">Contacts</h3>
              <button type="button" className="btn-secondary" onClick={addContact}>
                <PlusIcon className="w-4 h-4" /> Add Contact
              </button>
            </div>

            {settingsContacts.length === 0 ? (
              <p className="text-sm text-secondary text-center py-4">No contacts. Click "Add Contact" to add one.</p>
            ) : (
              <div className="space-y-4">
                {settingsContacts.map((contact, idx) => (
                  <div key={idx} className="border border-border rounded-lg p-4">
                    <div className="flex items-center justify-between mb-3">
                      <span className="text-sm font-medium text-primary">Contact #{idx + 1}</span>
                      <button
                        type="button"
                        onClick={() => removeContact(idx)}
                        className="p-1 rounded hover:bg-red-50 text-red-500 transition-colors"
                      >
                        <TrashIcon className="w-4 h-4" />
                      </button>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label>Name</label>
                        <input
                          value={contact.name}
                          onChange={(e) => updateContact(idx, 'name', e.target.value)}
                          placeholder="John Doe"
                        />
                      </div>
                      <div>
                        <label>Email</label>
                        <input
                          type="email"
                          value={contact.email}
                          onChange={(e) => updateContact(idx, 'email', e.target.value)}
                          placeholder="john@partner.com"
                        />
                      </div>
                      <div>
                        <label>Phone</label>
                        <input
                          value={contact.phone}
                          onChange={(e) => updateContact(idx, 'phone', e.target.value)}
                          placeholder="+1 555-0100"
                        />
                      </div>
                      <div>
                        <label>Role</label>
                        <select value={contact.role} onChange={(e) => updateContact(idx, 'role', e.target.value)}>
                          <option value="Technical">Technical</option>
                          <option value="Business">Business</option>
                          <option value="Billing">Billing</option>
                          <option value="Emergency">Emergency</option>
                        </select>
                      </div>
                    </div>
                    <div className="mt-3 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={contact.isPrimary || false}
                        onChange={(e) => updateContact(idx, 'isPrimary', e.target.checked)}
                        className="w-4 h-4 rounded border-border text-blue-600"
                      />
                      <label className="mb-0 text-sm text-primary">Primary Contact</label>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Save Button */}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="btn-secondary"
              onClick={() => {
                setSettingsForm(null)
                setActiveTab('overview')
              }}
            >
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={updateMut.isPending}>
              {updateMut.isPending ? 'Saving...' : 'Save Settings'}
            </button>
          </div>
        </form>
      )}

      {/* Suspend Partner Confirmation */}
      <ConfirmDialog
        open={showSuspendConfirm}
        variant="warning"
        title="Suspend partner?"
        message={partner ? `Suspend partner "${partner.companyName}"?` : ''}
        confirmLabel="Suspend"
        cancelLabel="Cancel"
        loading={suspendMut.isPending}
        onConfirm={() => { suspendMut.mutate(); setShowSuspendConfirm(false) }}
        onCancel={() => setShowSuspendConfirm(false)}
      />
    </div>
  )
}
