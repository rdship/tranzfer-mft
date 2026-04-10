import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import {
  CheckIcon,
  GlobeAltIcon,
  BuildingOfficeIcon,
  TruckIcon,
  UserGroupIcon,
  ServerIcon,
  ArrowsRightLeftIcon,
  PlusIcon,
  TrashIcon,
  ChevronDownIcon,
  ChevronUpIcon,
  EyeIcon,
  EyeSlashIcon,
  ArrowPathIcon
} from '@heroicons/react/24/outline'
import { createPartner } from '../api/partners'
import { createAccount } from '../api/accounts'
import { friendlyError } from '../components/FormField'
import LoadingSpinner from '../components/LoadingSpinner'

const STEPS = [
  { num: 1, label: 'Company Info' },
  { num: 2, label: 'Protocols' },
  { num: 3, label: 'Contacts' },
  { num: 4, label: 'Account Setup' },
  { num: 5, label: 'SLA Config' },
  { num: 6, label: 'Review' }
]

const INDUSTRIES = [
  'Financial Services', 'Healthcare', 'Retail', 'Manufacturing',
  'Technology', 'Logistics', 'Government', 'Energy', 'Telecommunications', 'Other'
]

const PARTNER_TYPES = [
  { value: 'EXTERNAL', icon: GlobeAltIcon, label: 'External Partner', desc: 'Third-party trading partner' },
  { value: 'INTERNAL', icon: BuildingOfficeIcon, label: 'Internal', desc: 'Internal department or division' },
  { value: 'VENDOR', icon: TruckIcon, label: 'Vendor', desc: 'Vendor or supplier' },
  { value: 'CLIENT', icon: UserGroupIcon, label: 'Client', desc: 'Client or customer' }
]

const PROTOCOLS = [
  { value: 'SFTP', icon: ServerIcon, label: 'SFTP', desc: 'Secure File Transfer Protocol (SSH)', detail: 'Port 22/2222' },
  { value: 'FTP', icon: ServerIcon, label: 'FTP', desc: 'File Transfer Protocol', detail: 'Port 21' },
  { value: 'AS2', icon: ArrowsRightLeftIcon, label: 'AS2', desc: 'Applicability Statement 2 (EDI)', detail: 'HTTP-based B2B' },
  { value: 'AS4', icon: ArrowsRightLeftIcon, label: 'AS4', desc: 'Applicability Statement 4', detail: 'ebMS3/AS4 messaging' },
  { value: 'HTTPS', icon: GlobeAltIcon, label: 'HTTPS', desc: 'HTTP Secure file upload/download', detail: 'REST API or Web UI' }
]

const CONTACT_ROLES = ['Technical', 'Business', 'Billing', 'Emergency']

const SLA_TIERS = [
  {
    value: 'BASIC', label: 'Basic',
    desc: 'Best effort, no guarantees',
    maxTransfers: 100, retention: 30, maxFileSize: 104857600, maxFileSizeLabel: '100 MB'
  },
  {
    value: 'STANDARD', label: 'Standard',
    desc: 'Business hours support',
    maxTransfers: 1000, retention: 90, maxFileSize: 536870912, maxFileSizeLabel: '512 MB'
  },
  {
    value: 'PREMIUM', label: 'Premium',
    desc: 'Priority support, 99.5% uptime',
    maxTransfers: 10000, retention: 180, maxFileSize: 2147483648, maxFileSizeLabel: '2 GB'
  },
  {
    value: 'ENTERPRISE', label: 'Enterprise',
    desc: '24/7 support, 99.9% uptime, custom',
    maxTransfers: 0, retention: 365, maxFileSize: 10737418240, maxFileSizeLabel: '10 GB'
  }
]

const HOME_DIRS = {
  SFTP: '/data/sftp',
  FTP: '/data/ftp',
  AS2: '/data/as2',
  AS4: '/data/as4',
  HTTPS: '/data/https'
}

const generatePassword = () => {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%&*'
  return Array.from({ length: 16 }, () => chars[Math.floor(Math.random() * chars.length)]).join('')
}

const slugify = (str) => str.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')

export default function PartnerSetup() {
  const navigate = useNavigate()
  const [step, setStep] = useState(1)
  const [form, setForm] = useState({
    companyName: '',
    displayName: '',
    industry: '',
    website: '',
    partnerType: 'EXTERNAL',
    logoUrl: '',
    notes: '',
    protocolsEnabled: [],
    contacts: [{ name: '', email: '', phone: '', role: 'Technical', isPrimary: true }],
    accounts: [],
    slaTier: 'STANDARD',
    maxFileSizeBytes: 536870912,
    maxTransfersPerDay: 1000,
    retentionDays: 90
  })
  const [skipAccounts, setSkipAccounts] = useState(false)
  const [expandedProtocols, setExpandedProtocols] = useState({})
  const [showPasswords, setShowPasswords] = useState({})
  const [stepErrors, setStepErrors] = useState({})

  const updateForm = (fields) => setForm((prev) => ({ ...prev, ...fields }))

  const createMut = useMutation({
    mutationFn: async (data) => {
      const partner = await createPartner(data)
      for (const acc of form.accounts) {
        await createAccount({ ...acc, partnerId: partner.id })
      }
      return partner
    },
    onSuccess: (partner) => {
      toast.success('Partner onboarded successfully!')
      navigate(`/partners/${partner.id}`)
    },
    onError: (err) => toast.error(friendlyError(err))
  })

  // Validation per step — sets inline errors and returns boolean
  const validateStep = (s) => {
    const errs = {}
    switch (s) {
      case 1:
        if (!form.companyName.trim()) errs.companyName = 'Company name is required'
        break
      case 2:
        if (form.protocolsEnabled.length === 0) errs.protocols = 'Select at least one protocol'
        break
      case 3:
        if (form.contacts.length === 0) {
          errs.contacts = 'At least one contact is required'
        } else {
          for (let i = 0; i < form.contacts.length; i++) {
            const c = form.contacts[i]
            if (!c.name.trim()) errs[`contact_${i}_name`] = 'Name is required'
            if (!c.email.trim()) errs[`contact_${i}_email`] = 'Email is required'
            else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(c.email)) errs[`contact_${i}_email`] = 'Enter a valid email address'
          }
        }
        break
      default:
        break
    }
    setStepErrors(errs)
    if (Object.keys(errs).length > 0) {
      toast.error('Please fix the highlighted fields before continuing')
      return false
    }
    return true
  }

  const handleNext = () => {
    if (validateStep(step)) {
      if (step === 2) {
        // Auto-generate account stubs for newly selected protocols
        const slug = slugify(form.companyName || 'partner')
        const existingProtocols = form.accounts.map((a) => a.protocol)
        const newAccounts = [...form.accounts]
        for (const p of form.protocolsEnabled) {
          if (!existingProtocols.includes(p)) {
            newAccounts.push({
              protocol: p,
              username: `${slug}-${p.toLowerCase()}`,
              password: '',
              homeDir: `${HOME_DIRS[p]}/${slug}`
            })
          }
        }
        // Remove accounts for deselected protocols
        const filtered = newAccounts.filter((a) => form.protocolsEnabled.includes(a.protocol))
        updateForm({ accounts: filtered })
      }
      setStep((prev) => Math.min(prev + 1, 6))
    }
  }

  const handleBack = () => { setStepErrors({}); setStep((prev) => Math.max(prev - 1, 1)) }

  const handleSubmit = () => {
    const payload = {
      companyName: form.companyName,
      displayName: form.displayName || form.companyName,
      industry: form.industry,
      website: form.website,
      partnerType: form.partnerType,
      logoUrl: form.logoUrl,
      notes: form.notes,
      protocolsEnabled: form.protocolsEnabled,
      contacts: form.contacts,
      slaTier: form.slaTier,
      maxFileSizeBytes: form.maxFileSizeBytes,
      maxTransfersPerDay: form.maxTransfersPerDay,
      retentionDays: form.retentionDays
    }
    createMut.mutate(payload)
  }

  const toggleProtocol = (proto) => {
    updateForm({
      protocolsEnabled: form.protocolsEnabled.includes(proto)
        ? form.protocolsEnabled.filter((p) => p !== proto)
        : [...form.protocolsEnabled, proto]
    })
  }

  const updateContact = (index, fields) => {
    const updated = form.contacts.map((c, i) => (i === index ? { ...c, ...fields } : c))
    updateForm({ contacts: updated })
  }

  const addContact = () => {
    updateForm({
      contacts: [
        ...form.contacts,
        { name: '', email: '', phone: '', role: 'Technical', isPrimary: false }
      ]
    })
  }

  const removeContact = (index) => {
    const updated = form.contacts.filter((_, i) => i !== index)
    // If we removed the primary, make the first one primary
    if (updated.length > 0 && !updated.some((c) => c.isPrimary)) {
      updated[0].isPrimary = true
    }
    updateForm({ contacts: updated })
  }

  const setPrimaryContact = (index) => {
    const updated = form.contacts.map((c, i) => ({ ...c, isPrimary: i === index }))
    updateForm({ contacts: updated })
  }

  const updateAccount = (index, fields) => {
    const updated = form.accounts.map((a, i) => (i === index ? { ...a, ...fields } : a))
    updateForm({ accounts: updated })
  }

  const applySLATier = (tierValue) => {
    const tier = SLA_TIERS.find((t) => t.value === tierValue)
    updateForm({
      slaTier: tierValue,
      maxFileSizeBytes: tier.maxFileSize,
      maxTransfersPerDay: tier.maxTransfers,
      retentionDays: tier.retention
    })
  }

  const toggleExpandProtocol = (proto) => {
    setExpandedProtocols((prev) => ({ ...prev, [proto]: !prev[proto] }))
  }

  const formatBytes = (bytes) => {
    if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`
    if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(0)} MB`
    return `${bytes} bytes`
  }

  // ---------- Step Indicator ----------
  const renderStepIndicator = () => (
    <div className="flex items-center justify-between mb-8">
      {STEPS.map((s, i) => {
        const isCompleted = step > s.num
        const isCurrent = step === s.num
        const isUpcoming = step < s.num
        return (
          <div key={s.num} className="flex items-center flex-1 last:flex-initial">
            <div className="flex flex-col items-center">
              <div
                className={`w-10 h-10 rounded-full flex items-center justify-center text-sm font-semibold transition-all ${
                  isCompleted
                    ? 'bg-[rgb(60,180,100)] text-white'
                    : isCurrent
                    ? 'bg-accent text-white ring-4 ring-accent/20'
                    : 'bg-hover text-secondary'
                }`}
              >
                {isCompleted ? <CheckIcon className="w-5 h-5" /> : s.num}
              </div>
              <span
                className={`text-xs mt-1 whitespace-nowrap ${
                  isCurrent ? 'text-accent font-semibold' : isCompleted ? 'text-[rgb(120,220,160)]' : 'text-muted'
                }`}
              >
                {s.label}
              </span>
            </div>
            {i < STEPS.length - 1 && (
              <div
                className={`flex-1 h-0.5 mx-2 ${step > s.num ? 'bg-[rgb(60,180,100)]' : 'bg-hover'}`}
              />
            )}
          </div>
        )
      })}
    </div>
  )

  // ---------- Step 1: Company Information ----------
  const renderStep1 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Company Information</h2>
      <p className="text-sm text-secondary mb-2">Tell us about the partner organization</p>
      <p className="text-xs text-muted mb-6">This information identifies the partner across the platform and appears in compliance reports.</p>

      <div className="grid grid-cols-2 gap-6">
        <div>
          <label className="block text-sm font-medium text-primary mb-1">
            Company Name <span className="text-[rgb(240,120,120)]">*</span>
          </label>
          <input
            type="text"
            value={form.companyName}
            onChange={(e) => updateForm({ companyName: e.target.value })}
            placeholder="Acme Corporation"
            className={stepErrors.companyName ? 'border-red-300 focus:ring-red-500' : ''}
          />
          {stepErrors.companyName && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{stepErrors.companyName}</p>}
          {!stepErrors.companyName && <p className="mt-1 text-xs text-muted">The legal entity name. Used to generate the partner slug for API references.</p>}
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Display Name</label>
          <input
            type="text"
            value={form.displayName}
            onChange={(e) => updateForm({ displayName: e.target.value })}
            placeholder="Acme (optional)"
          />
          <p className="mt-1 text-xs text-muted">Short name shown in dashboards. Defaults to Company Name if empty.</p>
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Industry</label>
          <select value={form.industry} onChange={(e) => updateForm({ industry: e.target.value })}>
            <option value="">Select industry...</option>
            {INDUSTRIES.map((ind) => (
              <option key={ind} value={ind}>
                {ind}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-muted">Helps configure compliance rules and reporting categories.</p>
        </div>
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Website</label>
          <input
            type="url"
            value={form.website}
            onChange={(e) => updateForm({ website: e.target.value })}
            placeholder="https://www.example.com"
          />
          <p className="mt-1 text-xs text-muted">Partner's corporate website for reference.</p>
        </div>
      </div>

      <div className="mt-6">
        <label className="block text-sm font-medium text-primary mb-3">Partner Type</label>
        <div className="grid grid-cols-4 gap-4">
          {PARTNER_TYPES.map((pt) => {
            const Icon = pt.icon
            const selected = form.partnerType === pt.value
            return (
              <button
                key={pt.value}
                type="button"
                onClick={() => updateForm({ partnerType: pt.value })}
                className={`p-4 rounded-xl border-2 text-left transition-all ${
                  selected
                    ? 'border-accent bg-accent-soft'
                    : 'border-border hover:border-muted'
                }`}
              >
                <Icon className={`w-6 h-6 mb-2 ${selected ? 'text-accent' : 'text-muted'}`} />
                <p className={`text-sm font-semibold ${selected ? 'text-accent' : 'text-primary'}`}>
                  {pt.label}
                </p>
                <p className="text-xs text-secondary">{pt.desc}</p>
              </button>
            )
          })}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-6 mt-6">
        <div>
          <label className="block text-sm font-medium text-primary mb-1">Logo URL</label>
          <input
            type="url"
            value={form.logoUrl}
            onChange={(e) => updateForm({ logoUrl: e.target.value })}
            placeholder="https://cdn.example.com/logo.png (optional)"
          />
        </div>
      </div>

      <div className="mt-6">
        <label className="block text-sm font-medium text-primary mb-1">Notes</label>
        <textarea
          rows={3}
          value={form.notes}
          onChange={(e) => updateForm({ notes: e.target.value })}
          placeholder="Any additional notes about this partner..."
        />
      </div>
    </div>
  )

  // ---------- Step 2: Protocols ----------
  const renderStep2 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Protocol Selection</h2>
      <p className="text-sm text-secondary mb-2">
        Choose which file transfer protocols this partner will use <span className="text-[rgb(240,120,120)]">*</span>
      </p>
      <p className="text-xs text-muted mb-6">Each protocol creates a dedicated transfer account in the next step. You can add more later.</p>
      {stepErrors.protocols && <p className="mb-4 text-sm text-[rgb(240,120,120)] bg-[rgb(60,20,20)] border border-[rgb(80,30,30)] rounded-lg px-3 py-2">{stepErrors.protocols}</p>}

      <div className="grid grid-cols-1 gap-4">
        {PROTOCOLS.map((proto) => {
          const Icon = proto.icon
          const selected = form.protocolsEnabled.includes(proto.value)
          return (
            <button
              key={proto.value}
              type="button"
              onClick={() => toggleProtocol(proto.value)}
              className={`relative flex items-center gap-4 p-5 rounded-xl border-2 text-left transition-all ${
                selected ? 'border-accent bg-accent-soft' : 'border-border hover:border-muted'
              }`}
            >
              {selected && (
                <div className="absolute top-3 right-3">
                  <div className="w-6 h-6 bg-accent rounded-full flex items-center justify-center">
                    <CheckIcon className="w-4 h-4 text-white" />
                  </div>
                </div>
              )}
              <div
                className={`w-12 h-12 rounded-xl flex items-center justify-center ${
                  selected ? 'bg-accent-soft' : 'bg-hover'
                }`}
              >
                <Icon className={`w-6 h-6 ${selected ? 'text-accent' : 'text-muted'}`} />
              </div>
              <div>
                <p className={`font-semibold ${selected ? 'text-accent' : 'text-primary'}`}>
                  {proto.label}
                </p>
                <p className="text-sm text-secondary">{proto.desc}</p>
                <p className="text-xs text-muted mt-0.5">{proto.detail}</p>
              </div>
            </button>
          )
        })}
      </div>

      {form.protocolsEnabled.length > 0 && (
        <div className="mt-4 bg-[rgb(60,50,20)] border border-[rgb(80,65,25)] rounded-lg p-3 text-sm text-[rgb(240,200,100)]">
          <span className="font-medium">Network Routing:</span> If this partner connects to external endpoints,
          you can route traffic through the DMZ Proxy for network isolation. Configure proxy settings when
          creating <a href="/external-destinations" className="underline">External Destinations</a> for this partner.
        </div>
      )}
    </div>
  )

  // ---------- Step 3: Contacts ----------
  const renderStep3 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Contact Information</h2>
      <p className="text-sm text-secondary mb-2">Add contacts for this partner</p>
      <p className="text-xs text-muted mb-6">Contacts receive notifications about transfer events and are used for emergency escalation.</p>
      {stepErrors.contacts && <p className="mb-4 text-sm text-[rgb(240,120,120)] bg-[rgb(60,20,20)] border border-[rgb(80,30,30)] rounded-lg px-3 py-2">{stepErrors.contacts}</p>}

      <div className="space-y-4">
        {form.contacts.map((contact, idx) => (
          <div key={idx} className="border border-border rounded-xl p-5 relative">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <span className="text-sm font-semibold text-primary">Contact {idx + 1}</span>
                {contact.isPrimary && (
                  <span className="badge badge-blue">Primary</span>
                )}
              </div>
              <div className="flex items-center gap-2">
                {!contact.isPrimary && (
                  <button
                    type="button"
                    onClick={() => setPrimaryContact(idx)}
                    className="text-xs text-accent hover:text-accent"
                  >
                    Make Primary
                  </button>
                )}
                {form.contacts.length > 1 && (
                  <button
                    type="button"
                    onClick={() => removeContact(idx)}
                    className="text-muted hover:text-[rgb(240,120,120)] transition-colors"
                  >
                    <TrashIcon className="w-4 h-4" />
                  </button>
                )}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-primary mb-1">
                  Name <span className="text-[rgb(240,120,120)]">*</span>
                </label>
                <input
                  type="text"
                  value={contact.name}
                  onChange={(e) => { updateContact(idx, { name: e.target.value }); setStepErrors(prev => { const n = {...prev}; delete n[`contact_${idx}_name`]; return n }) }}
                  placeholder="John Doe"
                  className={stepErrors[`contact_${idx}_name`] ? 'border-red-300 focus:ring-red-500' : ''}
                />
                {stepErrors[`contact_${idx}_name`] && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{stepErrors[`contact_${idx}_name`]}</p>}
              </div>
              <div>
                <label className="block text-sm font-medium text-primary mb-1">
                  Email <span className="text-[rgb(240,120,120)]">*</span>
                </label>
                <input
                  type="email"
                  value={contact.email}
                  onChange={(e) => { updateContact(idx, { email: e.target.value }); setStepErrors(prev => { const n = {...prev}; delete n[`contact_${idx}_email`]; return n }) }}
                  placeholder="john@example.com"
                  className={stepErrors[`contact_${idx}_email`] ? 'border-red-300 focus:ring-red-500' : ''}
                />
                {stepErrors[`contact_${idx}_email`] && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{stepErrors[`contact_${idx}_email`]}</p>}
              </div>
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Phone</label>
                <input
                  type="tel"
                  value={contact.phone}
                  onChange={(e) => updateContact(idx, { phone: e.target.value })}
                  placeholder="+1 (555) 123-4567"
                />
                <p className="mt-1 text-xs text-muted">Include country code for international partners.</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Role</label>
                <select
                  value={contact.role}
                  onChange={(e) => updateContact(idx, { role: e.target.value })}
                >
                  {CONTACT_ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
                <p className="mt-1 text-xs text-muted">Determines notification preferences and escalation path.</p>
              </div>
            </div>
          </div>
        ))}
      </div>

      <button
        type="button"
        onClick={addContact}
        className="mt-4 flex items-center gap-2 text-sm text-accent hover:text-accent font-medium"
      >
        <PlusIcon className="w-4 h-4" />
        Add Contact
      </button>
    </div>
  )

  // ---------- Step 4: Account Setup ----------
  const renderStep4 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Transfer Accounts</h2>
      <p className="text-sm text-secondary mb-2">Create accounts for each selected protocol</p>
      <p className="text-xs text-muted mb-6">Each account gives the partner credentials to connect via the selected protocol. Usernames are auto-generated but editable.</p>

      <div className="flex items-center gap-3 mb-6">
        <input
          type="checkbox"
          id="skipAccounts"
          checked={skipAccounts}
          onChange={(e) => setSkipAccounts(e.target.checked)}
          className="rounded border-border"
        />
        <label htmlFor="skipAccounts" className="text-sm text-secondary">
          I'll set up accounts later
        </label>
      </div>

      {!skipAccounts && (
        <div className="space-y-4">
          {form.accounts.map((acc, idx) => {
            const proto = PROTOCOLS.find((p) => p.value === acc.protocol)
            const isExpanded = expandedProtocols[acc.protocol] !== false
            const isPasswordVisible = showPasswords[idx]
            return (
              <div key={acc.protocol} className="border border-border rounded-xl overflow-hidden">
                <button
                  type="button"
                  onClick={() => toggleExpandProtocol(acc.protocol)}
                  className="w-full flex items-center justify-between p-4 bg-canvas hover:bg-hover transition-colors"
                >
                  <div className="flex items-center gap-3">
                    {proto && <proto.icon className="w-5 h-5 text-secondary" />}
                    <span className="font-semibold text-primary">{acc.protocol} Account</span>
                    {acc.username && (
                      <span className="text-sm text-muted">({acc.username})</span>
                    )}
                  </div>
                  {isExpanded ? (
                    <ChevronUpIcon className="w-5 h-5 text-muted" />
                  ) : (
                    <ChevronDownIcon className="w-5 h-5 text-muted" />
                  )}
                </button>
                {isExpanded && (
                  <div className="p-5 space-y-4">
                    <div>
                      <label className="block text-sm font-medium text-primary mb-1">
                        Username
                      </label>
                      <input
                        type="text"
                        value={acc.username}
                        onChange={(e) => updateAccount(idx, { username: e.target.value })}
                        placeholder="username"
                      />
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-primary mb-1">
                        Password
                      </label>
                      <div className="flex gap-2">
                        <div className="relative flex-1">
                          <input
                            type={isPasswordVisible ? 'text' : 'password'}
                            value={acc.password}
                            onChange={(e) => updateAccount(idx, { password: e.target.value })}
                            placeholder="Enter or generate password"
                          />
                          <button
                            type="button"
                            onClick={() =>
                              setShowPasswords((prev) => ({ ...prev, [idx]: !prev[idx] }))
                            }
                            className="absolute right-2 top-1/2 -translate-y-1/2 text-muted hover:text-secondary"
                          >
                            {isPasswordVisible ? (
                              <EyeSlashIcon className="w-4 h-4" />
                            ) : (
                              <EyeIcon className="w-4 h-4" />
                            )}
                          </button>
                        </div>
                        <button
                          type="button"
                          onClick={() => updateAccount(idx, { password: generatePassword() })}
                          className="btn-secondary flex items-center gap-1"
                        >
                          <ArrowPathIcon className="w-4 h-4" />
                          Generate
                        </button>
                      </div>
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-primary mb-1">
                        Home Directory
                      </label>
                      <input
                        type="text"
                        value={acc.homeDir}
                        onChange={(e) => updateAccount(idx, { homeDir: e.target.value })}
                        placeholder="/data/protocol/partner"
                      />
                    </div>
                  </div>
                )}
              </div>
            )
          })}

          {form.accounts.length === 0 && (
            <p className="text-sm text-muted italic">
              No protocols selected. Go back to step 2 to select protocols.
            </p>
          )}
        </div>
      )}
    </div>
  )

  // ---------- Step 5: SLA Configuration ----------
  const renderStep5 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Service Level Agreement</h2>
      <p className="text-sm text-secondary mb-2">Configure performance and retention settings</p>
      <p className="text-xs text-muted mb-6">The SLA tier determines transfer limits, file retention, and maximum file sizes. You can override individual values below.</p>

      <div className="grid grid-cols-2 gap-4 mb-8">
        {SLA_TIERS.map((tier) => {
          const selected = form.slaTier === tier.value
          return (
            <button
              key={tier.value}
              type="button"
              onClick={() => applySLATier(tier.value)}
              className={`p-5 rounded-xl border-2 text-left transition-all ${
                selected ? 'border-accent bg-accent-soft' : 'border-border hover:border-muted'
              }`}
            >
              <p className={`font-bold text-lg ${selected ? 'text-accent' : 'text-primary'}`}>
                {tier.label}
              </p>
              <p className="text-sm text-secondary mt-1">{tier.desc}</p>
              <div className="mt-3 space-y-1 text-xs text-muted">
                <p>
                  {tier.maxTransfers === 0
                    ? 'Unlimited transfers/day'
                    : `Max ${tier.maxTransfers.toLocaleString()} transfers/day`}
                </p>
                <p>{tier.retention}-day retention</p>
                <p>Max file size: {tier.maxFileSizeLabel}</p>
              </div>
            </button>
          )
        })}
      </div>

      <div className="border-t pt-6">
        <h3 className="text-sm font-semibold text-primary mb-4">Custom Overrides</h3>
        <div className="grid grid-cols-3 gap-6">
          <div>
            <label className="block text-sm font-medium text-primary mb-1">
              Max File Size
            </label>
            <div className="flex gap-2">
              <input
                type="number"
                value={
                  form.maxFileSizeBytes >= 1073741824
                    ? Math.round(form.maxFileSizeBytes / 1073741824)
                    : Math.round(form.maxFileSizeBytes / 1048576)
                }
                onChange={(e) => {
                  const val = parseInt(e.target.value) || 0
                  const unit = form.maxFileSizeBytes >= 1073741824 ? 1073741824 : 1048576
                  updateForm({ maxFileSizeBytes: val * unit })
                }}
                min={1}
                className="flex-1"
              />
              <select
                value={form.maxFileSizeBytes >= 1073741824 ? 'GB' : 'MB'}
                onChange={(e) => {
                  const currentVal =
                    form.maxFileSizeBytes >= 1073741824
                      ? Math.round(form.maxFileSizeBytes / 1073741824)
                      : Math.round(form.maxFileSizeBytes / 1048576)
                  const multiplier = e.target.value === 'GB' ? 1073741824 : 1048576
                  updateForm({ maxFileSizeBytes: currentVal * multiplier })
                }}
                className="w-20"
              >
                <option value="MB">MB</option>
                <option value="GB">GB</option>
              </select>
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium text-primary mb-1">
              Max Transfers per Day
            </label>
            <input
              type="number"
              value={form.maxTransfersPerDay}
              onChange={(e) => updateForm({ maxTransfersPerDay: parseInt(e.target.value) || 0 })}
              min={0}
              placeholder="0 for unlimited"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-primary mb-1">Retention Days</label>
            <input
              type="number"
              value={form.retentionDays}
              onChange={(e) => updateForm({ retentionDays: parseInt(e.target.value) || 0 })}
              min={1}
            />
          </div>
        </div>
      </div>
    </div>
  )

  // ---------- Step 6: Review ----------
  const renderStep6 = () => (
    <div className="space-y-6">
      <div className="card">
        <h2 className="text-xl font-bold text-primary">Review & Create</h2>
        <p className="text-sm text-secondary">Verify everything looks correct</p>
      </div>

      {/* Company Details */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          Company Details
        </h3>
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <span className="text-secondary">Company Name:</span>
            <span className="ml-2 font-medium text-primary">{form.companyName}</span>
          </div>
          <div>
            <span className="text-secondary">Display Name:</span>
            <span className="ml-2 font-medium text-primary">
              {form.displayName || form.companyName}
            </span>
          </div>
          {form.industry && (
            <div>
              <span className="text-secondary">Industry:</span>
              <span className="ml-2 font-medium text-primary">{form.industry}</span>
            </div>
          )}
          {form.website && (
            <div>
              <span className="text-secondary">Website:</span>
              <span className="ml-2 font-medium text-primary">{form.website}</span>
            </div>
          )}
          <div>
            <span className="text-secondary">Partner Type:</span>
            <span className="ml-2">
              <span className="badge badge-blue">{form.partnerType}</span>
            </span>
          </div>
          {form.notes && (
            <div className="col-span-2">
              <span className="text-secondary">Notes:</span>
              <span className="ml-2 font-medium text-primary">{form.notes}</span>
            </div>
          )}
        </div>
      </div>

      {/* Protocols */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          Protocols
        </h3>
        <div className="flex flex-wrap gap-2">
          {form.protocolsEnabled.map((p) => (
            <span key={p} className="badge badge-purple">
              {p}
            </span>
          ))}
        </div>
      </div>

      {/* Contacts */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          Contacts ({form.contacts.length})
        </h3>
        <div className="space-y-3">
          {form.contacts.map((c, idx) => (
            <div
              key={idx}
              className="flex items-center justify-between text-sm p-3 bg-canvas rounded-lg"
            >
              <div>
                <span className="font-medium text-primary">{c.name}</span>
                <span className="text-muted mx-2">|</span>
                <span className="text-secondary">{c.email}</span>
                {c.phone && (
                  <>
                    <span className="text-muted mx-2">|</span>
                    <span className="text-secondary">{c.phone}</span>
                  </>
                )}
              </div>
              <div className="flex items-center gap-2">
                <span className="badge badge-gray">{c.role}</span>
                {c.isPrimary && <span className="badge badge-green">Primary</span>}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Accounts */}
      {!skipAccounts && form.accounts.length > 0 && (
        <div className="card">
          <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
            Accounts ({form.accounts.length})
          </h3>
          <div className="space-y-3">
            {form.accounts.map((acc) => (
              <div
                key={acc.protocol}
                className="flex items-center justify-between text-sm p-3 bg-canvas rounded-lg"
              >
                <div>
                  <span className="badge badge-purple mr-2">{acc.protocol}</span>
                  <span className="font-medium text-primary">{acc.username}</span>
                </div>
                <span className="text-secondary text-xs font-mono">{acc.homeDir}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* SLA */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          SLA Configuration
        </h3>
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <span className="text-secondary">SLA Tier:</span>
            <span className="ml-2">
              <span className="badge badge-yellow">{form.slaTier}</span>
            </span>
          </div>
          <div>
            <span className="text-secondary">Max File Size:</span>
            <span className="ml-2 font-medium text-primary">
              {formatBytes(form.maxFileSizeBytes)}
            </span>
          </div>
          <div>
            <span className="text-secondary">Max Transfers/Day:</span>
            <span className="ml-2 font-medium text-primary">
              {form.maxTransfersPerDay === 0
                ? 'Unlimited'
                : form.maxTransfersPerDay.toLocaleString()}
            </span>
          </div>
          <div>
            <span className="text-secondary">Retention:</span>
            <span className="ml-2 font-medium text-primary">{form.retentionDays} days</span>
          </div>
        </div>
      </div>

      {/* What happens next */}
      <div className="card border-2 border-blue-100 bg-accent-soft/50">
        <h3 className="text-sm font-semibold text-blue-900 mb-3">What happens when you click "Create Partner"?</h3>
        <ol className="space-y-2 text-sm text-blue-800">
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">1</span>
            <span>A new partner record is created with status <strong>PENDING</strong> and a unique slug for API references.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">2</span>
            <span>Transfer accounts are provisioned for each selected protocol with home directories created automatically.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">3</span>
            <span>Contacts are saved and the primary contact will receive onboarding notifications (if configured).</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">4</span>
            <span>You'll be redirected to the Partner Detail page where you can <strong>activate</strong> the partner, configure flows, and set up webhooks.</span>
          </li>
        </ol>
      </div>
    </div>
  )

  // ---------- Render Current Step ----------
  const renderCurrentStep = () => {
    switch (step) {
      case 1:
        return renderStep1()
      case 2:
        return renderStep2()
      case 3:
        return renderStep3()
      case 4:
        return renderStep4()
      case 5:
        return renderStep5()
      case 6:
        return renderStep6()
      default:
        return null
    }
  }

  return (
    <div className="space-y-6">
      {/* Hero Header */}
      <div className="bg-gradient-to-r from-blue-600 to-indigo-600 rounded-xl p-6 text-white">
        <h1 className="text-2xl font-bold">Onboard New Partner</h1>
        <p className="text-blue-100 mt-1 text-sm">
          This wizard will guide you through setting up a new trading partner in {STEPS.length} steps:
          company details, protocol selection, contacts, transfer accounts, SLA configuration, and final review.
        </p>
        <div className="flex items-center gap-4 mt-3 text-xs text-blue-200">
          <span>Step {step} of {STEPS.length}: <strong className="text-white">{STEPS[step - 1].label}</strong></span>
          <span className="text-blue-300">|</span>
          <span>Estimated time: 3-5 minutes</span>
        </div>
      </div>

      {renderStepIndicator()}
      {renderCurrentStep()}

      {/* Navigation Bar */}
      <div className="flex items-center justify-between pt-4 border-t">
        <button
          type="button"
          className="btn-secondary"
          onClick={handleBack}
          disabled={step === 1}
        >
          Back
        </button>

        {step < 6 ? (
          <button type="button" className="btn-primary" onClick={handleNext}>
            Next
          </button>
        ) : (
          <button
            type="button"
            className="btn-primary px-8 py-2.5 text-base"
            onClick={handleSubmit}
            disabled={createMut.isPending}
          >
            {createMut.isPending ? (
              <span className="flex items-center gap-2">
                <LoadingSpinner /> Creating...
              </span>
            ) : (
              'Create Partner'
            )}
          </button>
        )}
      </div>
    </div>
  )
}
