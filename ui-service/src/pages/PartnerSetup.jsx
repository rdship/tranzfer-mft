import { useState, useEffect, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
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
  ArrowPathIcon,
  SignalIcon,
  ExclamationTriangleIcon,
  BoltIcon
} from '@heroicons/react/24/outline'
import { createPartner } from '../api/partners'
import { createAccount } from '../api/accounts'
import { onboardingApi, configApi } from '../api/client'
import FormField, { friendlyError, validators, validateForm } from '../components/FormField'
import useEnterAdvances from '../hooks/useEnterAdvances'
import LoadingSpinner from '../components/LoadingSpinner'

const STEPS = [
  { num: 1, label: 'Company Info' },
  { num: 2, label: 'Protocols' },
  { num: 3, label: 'Contacts' },
  { num: 4, label: 'Account Setup' },
  { num: 5, label: 'SLA Config' },
  { num: 6, label: 'Server Assign' },
  { num: 7, label: 'Flow Config' },
  { num: 8, label: 'Test Connect' },
  { num: 9, label: 'Review' }
]

const TOTAL_STEPS = STEPS.length

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
  const queryClient = useQueryClient()
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

  // Server Assignment state (step 6)
  const [servers, setServers] = useState([])
  const [serversLoading, setServersLoading] = useState(false)
  const [selectedServerId, setSelectedServerId] = useState(null)
  const [serverAssigned, setServerAssigned] = useState(false)
  const [serverAssigning, setServerAssigning] = useState(false)

  // Flow Configuration state (step 7)
  const [flows, setFlows] = useState([])
  const [flowsLoading, setFlowsLoading] = useState(false)
  const [selectedFlowId, setSelectedFlowId] = useState(null)

  // Test Connection state (step 8)
  const [testResult, setTestResult] = useState(null)  // { success, message, details }
  const [testRunning, setTestRunning] = useState(false)

  // Created entity IDs (populated during submit or account creation)
  const [createdPartnerId, setCreatedPartnerId] = useState(null)
  const [createdAccountIds, setCreatedAccountIds] = useState([])

  const updateForm = (fields) => setForm((prev) => ({ ...prev, ...fields }))

  // Creates partner + accounts, used both when advancing past SLA (step 5→6) and on final submit
  const createMut = useMutation({
    mutationFn: async (data) => {
      const partner = await createPartner(data)
      const accountIds = []
      for (const acc of form.accounts) {
        const created = await createAccount({ ...acc, partnerId: partner.id })
        accountIds.push(created.id)
      }
      return { partner, accountIds }
    },
    onSuccess: ({ partner, accountIds }) => {
      setCreatedPartnerId(partner.id)
      setCreatedAccountIds(accountIds)
      queryClient.invalidateQueries({ queryKey: ['partners'] })
      // If we are at step 5 advancing to 6, just advance — don't navigate
      if (step === 5) {
        setStep(6)
      } else {
        toast.success('Partner onboarded successfully!')
        navigate(`/partners/${partner.id}`)
      }
    },
    onError: (err) => toast.error(friendlyError(err))
  })

  // Scroll + focus helper for the first missing field in a given errors map.
  // Mirrors useGentleValidation's behaviour without locking us into a single
  // `rules` array (this wizard validates a different subset per step).
  const focusFirstError = (errs) => {
    const firstKey = Object.keys(errs)[0]
    if (!firstKey) return
    const wrapper = document.querySelector(`[data-field-wrapper="${firstKey}"]`)
    if (wrapper) wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setTimeout(() => {
      const input = document.querySelector(`[data-field-name="${firstKey}"]`)
      if (input && typeof input.focus === 'function') input.focus({ preventScroll: true })
    }, 320)
  }

  // Friendly amber toast — same palette as useGentleValidation
  const gentleToast = (message) => toast(message, {
    icon: '👋',
    duration: 3500,
    style: {
      background: 'rgb(28, 22, 12)',
      color: 'rgb(245, 158, 11)',
      border: '1px solid rgba(245, 158, 11, 0.4)',
    },
  })

  // Validation per step — uses shared validateForm() where possible, returns
  // the errors map so the caller can both persist + surface it.
  const validateStep = (s) => {
    let errs = {}
    switch (s) {
      case 1:
        errs = validateForm(form, [
          { field: 'companyName', label: 'Company name', required: true },
        ])
        break
      case 2:
        if (form.protocolsEnabled.length === 0) {
          errs.protocols = 'Pick at least one protocol so we know how this partner will connect'
        }
        break
      case 3:
        if (form.contacts.length === 0) {
          errs.contacts = 'Add at least one contact so we have someone to reach'
        } else {
          form.contacts.forEach((c, i) => {
            if (!c.name.trim()) errs[`contact_${i}_name`] = 'Add a name to continue'
            if (!c.email.trim()) errs[`contact_${i}_email`] = 'Add an email to continue'
            else {
              const emailErr = validators.email(c.email)
              if (emailErr) errs[`contact_${i}_email`] = emailErr
            }
          })
        }
        break
      default:
        break
    }
    setStepErrors(errs)
    const missing = Object.keys(errs).length
    if (missing > 0) {
      gentleToast(missing === 1 ? 'Just one more thing on this step' : `${missing} more details to continue`)
      focusFirstError(errs)
      return false
    }
    return true
  }

  const clearStepFieldError = (fieldName) => {
    setStepErrors((prev) => {
      if (!prev[fieldName]) return prev
      const next = { ...prev }
      delete next[fieldName]
      return next
    })
  }

  // Keyboard: Enter advances within the current step's form, last Enter
  // triggers Next. Defined via a ref-box so the handler can reference
  // handleNext (declared below) without a TDZ / ReferenceError.
  const handleNextRef = useRef(() => {})
  const onStepKeyDown = useEnterAdvances({ onSubmit: () => handleNextRef.current?.() })

  // Fetch servers filtered by selected protocols
  const fetchServers = useCallback(async () => {
    setServersLoading(true)
    try {
      const { data } = await onboardingApi.get('/api/servers?activeOnly=true')
      // Filter servers that match any of the partner's selected protocols
      const filtered = (data || []).filter((s) =>
        form.protocolsEnabled.some((p) =>
          (s.protocol || '').toUpperCase() === p.toUpperCase()
        )
      )
      setServers(filtered)
    } catch {
      setServers([])
    } finally {
      setServersLoading(false)
    }
  }, [form.protocolsEnabled])

  // Fetch flows filtered by protocol
  const fetchFlows = useCallback(async () => {
    setFlowsLoading(true)
    try {
      const { data } = await configApi.get('/api/flows')
      const filtered = (data || []).filter((f) =>
        form.protocolsEnabled.some((p) =>
          (f.protocol || '').toUpperCase() === p.toUpperCase()
        )
      )
      setFlows(filtered)
    } catch {
      setFlows([])
    } finally {
      setFlowsLoading(false)
    }
  }, [form.protocolsEnabled])

  // Keep the Enter-advances handler pointing at the latest handleNext so
  // closures captured by useEnterAdvances stay fresh on every render.
  useEffect(() => {
    handleNextRef.current = () => handleNext()
  })

  // Auto-fetch when entering steps 6 or 7
  useEffect(() => {
    if (step === 6) fetchServers()
  }, [step, fetchServers])

  useEffect(() => {
    if (step === 7) fetchFlows()
  }, [step, fetchFlows])

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

      // Create partner + accounts when advancing from SLA Config (step 5) to Server Assignment (step 6)
      if (step === 5 && !createdPartnerId) {
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
        return  // step advancement happens in onSuccess
      }

      setStep((prev) => Math.min(prev + 1, TOTAL_STEPS))
    }
  }

  const handleBack = () => {
    setStepErrors({})
    // Don't allow going back before step 6 once partner is created
    const minStep = createdPartnerId ? 6 : 1
    setStep((prev) => Math.max(prev - 1, minStep))
  }

  const handleSubmit = () => {
    // Partner was already created when advancing past step 5; just navigate
    if (createdPartnerId) {
      toast.success('Partner onboarded successfully!')
      queryClient.invalidateQueries({ queryKey: ['partners'] })
      navigate(`/partners/${createdPartnerId}`)
      return
    }
    // Fallback: create partner if somehow not yet created
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

  // Server assignment handler
  const handleAssignServer = async () => {
    if (!selectedServerId || createdAccountIds.length === 0) return
    setServerAssigning(true)
    try {
      // Assign the first account to the selected server (primary account)
      await onboardingApi.post(`/api/servers/${selectedServerId}/accounts/${createdAccountIds[0]}`)
      setServerAssigned(true)
      toast.success('Server assigned successfully')
    } catch (err) {
      toast.error(friendlyError(err))
    } finally {
      setServerAssigning(false)
    }
  }

  // Test connection handler
  const handleTestConnection = async () => {
    setTestRunning(true)
    setTestResult(null)
    try {
      const { data } = await onboardingApi.get('/api/partner/test-connection', {
        params: { partnerId: createdPartnerId }
      })
      setTestResult({
        success: true,
        message: data?.message || 'Connection successful',
        details: data
      })
    } catch (err) {
      const errMsg = err.response?.data?.message || err.message || 'Connection test failed'
      setTestResult({
        success: false,
        message: errMsg,
        details: err.response?.data
      })
    } finally {
      setTestRunning(false)
    }
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
        <FormField
          label="Company name"
          required
          name="companyName"
          error={stepErrors.companyName}
          valid={!stepErrors.companyName && !!form.companyName}
          helper="How you'll recognize this partner across reports and dashboards"
          tooltip="The legal entity name. It becomes the default partner slug used in API paths and audit trails, so pick something stable."
          samples={['ACME Trading Co', 'Global Logistics Inc', 'Pacific Supply Ltd', 'FirstFederal Bank']}
          onSampleClick={(val) => { updateForm({ companyName: val }); clearStepFieldError('companyName') }}
        >
          <input
            type="text"
            value={form.companyName}
            onChange={(e) => { updateForm({ companyName: e.target.value }); clearStepFieldError('companyName') }}
            placeholder="Acme Corporation"
          />
        </FormField>
        <FormField
          label="Display name"
          name="displayName"
          helper="Short name shown in dashboards — falls back to company name if you leave it empty"
          samples={['Acme', 'GlobLog', 'Pacific', 'FirstFed']}
          onSampleClick={(val) => updateForm({ displayName: val })}
        >
          <input
            type="text"
            value={form.displayName}
            onChange={(e) => updateForm({ displayName: e.target.value })}
            placeholder="Acme (optional)"
          />
        </FormField>
        <FormField
          label="Industry"
          name="industry"
          helper="Tunes compliance rules and reporting categories for this partner"
        >
          <select value={form.industry} onChange={(e) => updateForm({ industry: e.target.value })}>
            <option value="">Select industry...</option>
            {INDUSTRIES.map((ind) => (
              <option key={ind} value={ind}>
                {ind}
              </option>
            ))}
          </select>
        </FormField>
        <FormField
          label="Website"
          name="website"
          helper="The partner's corporate site — shown on the partner detail page for quick reference"
        >
          <input
            type="url"
            value={form.website}
            onChange={(e) => updateForm({ website: e.target.value })}
            placeholder="https://www.example.com"
          />
        </FormField>
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
        <FormField
          label="Logo URL"
          name="logoUrl"
          helper="Optional — shown next to the partner name in dashboards and reports"
        >
          <input
            type="url"
            value={form.logoUrl}
            onChange={(e) => updateForm({ logoUrl: e.target.value })}
            placeholder="https://cdn.example.com/logo.png"
          />
        </FormField>
      </div>

      <div className="mt-6">
        <FormField
          label="Notes"
          name="notes"
          helper="Anything the next person looking at this partner should know"
        >
          <textarea
            rows={3}
            value={form.notes}
            onChange={(e) => updateForm({ notes: e.target.value })}
            placeholder="Any additional notes about this partner..."
          />
        </FormField>
      </div>
    </div>
  )

  // ---------- Step 2: Protocols ----------
  const renderStep2 = () => (
    <div className="card">
      <div className="flex items-center gap-2 mb-2">
        <h2 className="text-xl font-bold text-primary">Protocol Selection</h2>
        <span
          className="text-[9px] uppercase tracking-wide font-bold px-1.5 py-0.5 rounded-full"
          style={{ color: 'rgb(148, 163, 184)', background: 'rgb(var(--border))', letterSpacing: '0.08em' }}
        >
          required
        </span>
      </div>
      <p className="text-sm text-secondary mb-2">
        Choose which file transfer protocols this partner will use
      </p>
      <p className="text-xs text-muted mb-6">Each protocol creates a dedicated transfer account in the next step. You can add more later.</p>
      {stepErrors.protocols && (
        <div
          className="mb-4 text-sm px-3 py-2 rounded-lg flex items-start gap-2"
          style={{
            color: 'rgb(245, 158, 11)',
            background: 'rgba(245, 158, 11, 0.08)',
            border: '1px solid rgba(245, 158, 11, 0.35)',
          }}
          role="alert"
        >
          <span
            aria-hidden="true"
            className="inline-flex items-center justify-center w-3.5 h-3.5 rounded-full mt-0.5 flex-shrink-0"
            style={{ background: 'rgb(245, 158, 11)', color: '#1a1510', fontSize: '9px', fontWeight: 'bold' }}
          >!</span>
          <span>{stepErrors.protocols}</span>
        </div>
      )}

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
      {stepErrors.contacts && (
        <div
          className="mb-4 text-sm px-3 py-2 rounded-lg flex items-start gap-2"
          style={{
            color: 'rgb(245, 158, 11)',
            background: 'rgba(245, 158, 11, 0.08)',
            border: '1px solid rgba(245, 158, 11, 0.35)',
          }}
          role="alert"
        >
          <span
            aria-hidden="true"
            className="inline-flex items-center justify-center w-3.5 h-3.5 rounded-full mt-0.5 flex-shrink-0"
            style={{ background: 'rgb(245, 158, 11)', color: '#1a1510', fontSize: '9px', fontWeight: 'bold' }}
          >!</span>
          <span>{stepErrors.contacts}</span>
        </div>
      )}

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
              <FormField
                label="Name"
                required
                name={`contact_${idx}_name`}
                error={stepErrors[`contact_${idx}_name`]}
                valid={!stepErrors[`contact_${idx}_name`] && !!contact.name}
                helper="Who we'll address onboarding emails and escalation calls to"
              >
                <input
                  type="text"
                  value={contact.name}
                  onChange={(e) => { updateContact(idx, { name: e.target.value }); clearStepFieldError(`contact_${idx}_name`) }}
                  placeholder="John Doe"
                />
              </FormField>
              <FormField
                label="Email"
                required
                name={`contact_${idx}_email`}
                error={stepErrors[`contact_${idx}_email`]}
                valid={!stepErrors[`contact_${idx}_email`] && !!contact.email}
                helper="Where we'll send onboarding updates and any critical alerts"
              >
                <input
                  type="email"
                  value={contact.email}
                  onChange={(e) => { updateContact(idx, { email: e.target.value }); clearStepFieldError(`contact_${idx}_email`) }}
                  placeholder="john@example.com"
                />
              </FormField>
              <FormField
                label="Phone"
                name={`contact_${idx}_phone`}
                helper="Include the country code for international partners so on-call routing works"
              >
                <input
                  type="tel"
                  value={contact.phone}
                  onChange={(e) => updateContact(idx, { phone: e.target.value })}
                  placeholder="+1 (555) 123-4567"
                />
              </FormField>
              <FormField
                label="Role"
                name={`contact_${idx}_role`}
                helper="Drives notification preferences and escalation path"
              >
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
              </FormField>
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

  // ---------- Step 6: Server Assignment ----------
  const renderStep6 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Server Assignment</h2>
      <p className="text-sm text-secondary mb-2">Assign this partner's account to a transfer server</p>
      <p className="text-xs text-muted mb-6">Servers handle the actual file transfer connections. Pick one that matches the partner's protocol.</p>

      {serverAssigned ? (
        <div className="flex flex-col items-center justify-center py-10">
          <div className="w-16 h-16 bg-[rgb(30,80,50)] rounded-full flex items-center justify-center mb-4">
            <CheckIcon className="w-8 h-8 text-[rgb(60,200,120)]" />
          </div>
          <p className="text-lg font-semibold text-[rgb(60,200,120)]">Server Assigned</p>
          <p className="text-sm text-secondary mt-1">
            Account linked to server: {servers.find((s) => s.id === selectedServerId)?.name || selectedServerId}
          </p>
        </div>
      ) : serversLoading ? (
        <div className="flex items-center justify-center py-10">
          <LoadingSpinner />
          <span className="ml-3 text-secondary">Loading available servers...</span>
        </div>
      ) : servers.length === 0 ? (
        <div className="text-center py-10">
          <ServerIcon className="w-12 h-12 text-muted mx-auto mb-3" />
          <p className="text-secondary">No active servers found for the selected protocols.</p>
          <p className="text-xs text-muted mt-1">You can assign a server later from the Partner Detail page.</p>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {servers.map((server) => {
              const isSelected = selectedServerId === server.id
              return (
                <button
                  key={server.id}
                  type="button"
                  onClick={() => setSelectedServerId(server.id)}
                  className={`relative p-5 rounded-xl border-2 text-left transition-all ${
                    isSelected ? 'border-accent bg-accent-soft' : 'border-border hover:border-muted'
                  }`}
                >
                  {isSelected && (
                    <div className="absolute top-3 right-3">
                      <div className="w-6 h-6 bg-accent rounded-full flex items-center justify-center">
                        <CheckIcon className="w-4 h-4 text-white" />
                      </div>
                    </div>
                  )}
                  <div className="flex items-center gap-3 mb-2">
                    <ServerIcon className={`w-6 h-6 ${isSelected ? 'text-accent' : 'text-muted'}`} />
                    <span className={`font-semibold ${isSelected ? 'text-accent' : 'text-primary'}`}>
                      {server.name || `Server ${server.id}`}
                    </span>
                  </div>
                  <div className="space-y-1 text-xs text-muted">
                    {server.protocol && <p>Protocol: <span className="text-secondary">{server.protocol}</span></p>}
                    {server.host && <p>Host: <span className="text-secondary font-mono">{server.host}:{server.port || '—'}</span></p>}
                    {server.status && <p>Status: <span className="text-secondary">{server.status}</span></p>}
                  </div>
                </button>
              )
            })}
          </div>

          <div className="flex items-center gap-3 mt-6">
            <button
              type="button"
              className="btn-primary"
              onClick={handleAssignServer}
              disabled={!selectedServerId || serverAssigning}
            >
              {serverAssigning ? (
                <span className="flex items-center gap-2"><LoadingSpinner /> Assigning...</span>
              ) : (
                'Assign Server'
              )}
            </button>
          </div>
        </>
      )}

      <div className="mt-6 pt-4 border-t">
        <button
          type="button"
          onClick={() => setStep(7)}
          className="text-sm text-accent hover:underline"
        >
          I'll do this later — skip to Flow Configuration
        </button>
      </div>
    </div>
  )

  // ---------- Step 7: Flow Configuration ----------
  const renderStep7 = () => (
    <div className="card">
      <h2 className="text-xl font-bold text-primary">Flow Configuration</h2>
      <p className="text-sm text-secondary mb-2">Select or create a transfer flow for this partner</p>
      <p className="text-xs text-muted mb-6">Flows define how files are processed, routed, and delivered. You can pick an existing flow or create a new one.</p>

      {flowsLoading ? (
        <div className="flex items-center justify-center py-10">
          <LoadingSpinner />
          <span className="ml-3 text-secondary">Loading available flows...</span>
        </div>
      ) : flows.length === 0 ? (
        <div className="text-center py-10">
          <ArrowsRightLeftIcon className="w-12 h-12 text-muted mx-auto mb-3" />
          <p className="text-secondary">No existing flows match the selected protocols.</p>
          <p className="text-xs text-muted mt-1">Create a new flow or skip and configure later.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {flows.map((flow) => {
            const isSelected = selectedFlowId === flow.id
            return (
              <button
                key={flow.id}
                type="button"
                onClick={() => setSelectedFlowId(flow.id)}
                className={`relative p-5 rounded-xl border-2 text-left transition-all ${
                  isSelected ? 'border-accent bg-accent-soft' : 'border-border hover:border-muted'
                }`}
              >
                {isSelected && (
                  <div className="absolute top-3 right-3">
                    <div className="w-6 h-6 bg-accent rounded-full flex items-center justify-center">
                      <CheckIcon className="w-4 h-4 text-white" />
                    </div>
                  </div>
                )}
                <div className="flex items-center gap-3 mb-2">
                  <ArrowsRightLeftIcon className={`w-5 h-5 ${isSelected ? 'text-accent' : 'text-muted'}`} />
                  <span className={`font-semibold ${isSelected ? 'text-accent' : 'text-primary'}`}>
                    {flow.name || `Flow ${flow.id}`}
                  </span>
                </div>
                <div className="space-y-1 text-xs text-muted">
                  {flow.protocol && <p>Protocol: <span className="text-secondary">{flow.protocol}</span></p>}
                  {flow.description && <p className="text-secondary">{flow.description}</p>}
                  {flow.direction && <p>Direction: <span className="text-secondary">{flow.direction}</span></p>}
                </div>
                {isSelected && (
                  <div className="mt-3 pt-2 border-t border-accent/20">
                    <span className="text-xs text-accent font-medium">Selected — will be assigned on review</span>
                  </div>
                )}
              </button>
            )
          })}
        </div>
      )}

      <div className="flex items-center gap-4 mt-6">
        <button
          type="button"
          className="btn-secondary flex items-center gap-2"
          onClick={() => navigate('/flows')}
        >
          <PlusIcon className="w-4 h-4" />
          Create New Flow
        </button>
      </div>

      <div className="mt-6 pt-4 border-t">
        <button
          type="button"
          onClick={() => setStep(8)}
          className="text-sm text-accent hover:underline"
        >
          I'll do this later — skip to Test Connection
        </button>
      </div>
    </div>
  )

  // ---------- Step 8: Test Connection ----------
  const renderStep8 = () => {
    const primaryProtocol = form.protocolsEnabled[0] || 'SFTP'
    return (
      <div className="card">
        <h2 className="text-xl font-bold text-primary">Test Connection</h2>
        <p className="text-sm text-secondary mb-2">Verify connectivity before finishing setup</p>
        <p className="text-xs text-muted mb-6">
          Run a connectivity test to make sure the partner's {primaryProtocol} endpoint is reachable and credentials work correctly.
        </p>

        <div className="flex flex-col items-center py-8">
          {!testResult && !testRunning && (
            <>
              <div className="w-20 h-20 bg-hover rounded-full flex items-center justify-center mb-6">
                <SignalIcon className="w-10 h-10 text-muted" />
              </div>
              <p className="text-secondary mb-6">Click below to test {primaryProtocol} connectivity</p>
              <button
                type="button"
                className="btn-primary flex items-center gap-2 px-6 py-2.5"
                onClick={handleTestConnection}
              >
                <BoltIcon className="w-5 h-5" />
                Test {primaryProtocol} Connectivity
              </button>
            </>
          )}

          {testRunning && (
            <div className="flex flex-col items-center">
              <LoadingSpinner />
              <p className="text-secondary mt-4">Testing connection...</p>
            </div>
          )}

          {testResult && !testRunning && (
            <div className="w-full max-w-lg">
              {testResult.success ? (
                <div className="text-center">
                  <div className="w-16 h-16 bg-[rgb(30,80,50)] rounded-full flex items-center justify-center mx-auto mb-4">
                    <CheckIcon className="w-8 h-8 text-[rgb(60,200,120)]" />
                  </div>
                  <p className="text-lg font-semibold text-[rgb(60,200,120)]">Connection Successful</p>
                  <p className="text-sm text-secondary mt-1">{testResult.message}</p>
                </div>
              ) : (
                <div>
                  <div className="text-center mb-6">
                    <div className="w-16 h-16 bg-[rgb(80,30,30)] rounded-full flex items-center justify-center mx-auto mb-4">
                      <ExclamationTriangleIcon className="w-8 h-8 text-[rgb(240,120,120)]" />
                    </div>
                    <p className="text-lg font-semibold text-[rgb(240,120,120)]">Connection Failed</p>
                    <p className="text-sm text-secondary mt-1">{testResult.message}</p>
                  </div>

                  <div className="bg-[rgb(60,20,20)] border border-[rgb(80,30,30)] rounded-lg p-4">
                    <h4 className="text-sm font-semibold text-[rgb(240,180,180)] mb-2">Troubleshooting Tips</h4>
                    <ul className="space-y-2 text-xs text-[rgb(200,160,160)]">
                      <li className="flex items-start gap-2">
                        <span className="text-[rgb(240,120,120)] mt-0.5">*</span>
                        <span>Verify the hostname and port are correct and reachable from this network</span>
                      </li>
                      <li className="flex items-start gap-2">
                        <span className="text-[rgb(240,120,120)] mt-0.5">*</span>
                        <span>Check that firewall rules allow outbound traffic on the {primaryProtocol} port</span>
                      </li>
                      <li className="flex items-start gap-2">
                        <span className="text-[rgb(240,120,120)] mt-0.5">*</span>
                        <span>Confirm credentials (username/password or SSH key) are correct</span>
                      </li>
                      <li className="flex items-start gap-2">
                        <span className="text-[rgb(240,120,120)] mt-0.5">*</span>
                        <span>If using SFTP/FTP, ensure the server is running and accepting connections</span>
                      </li>
                    </ul>
                  </div>

                  <button
                    type="button"
                    className="mt-4 btn-secondary flex items-center gap-2"
                    onClick={handleTestConnection}
                  >
                    <ArrowPathIcon className="w-4 h-4" />
                    Retry Test
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        <div className="mt-6 pt-4 border-t">
          <button
            type="button"
            onClick={() => setStep(9)}
            className="text-sm text-accent hover:underline"
          >
            Test later — skip to Review
          </button>
        </div>
      </div>
    )
  }

  // ---------- Step 9: Review ----------
  const renderStep9 = () => (
    <div className="space-y-6">
      <div className="card">
        <h2 className="text-xl font-bold text-primary">Review & Finish</h2>
        <p className="text-sm text-secondary">Review the onboarding summary before proceeding to the Partner Detail page</p>
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

      {/* Server Assignment */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          Server Assignment
        </h3>
        {serverAssigned ? (
          <div className="flex items-center gap-3 text-sm">
            <CheckIcon className="w-5 h-5 text-[rgb(60,200,120)]" />
            <span className="text-primary font-medium">
              Assigned to: {servers.find((s) => s.id === selectedServerId)?.name || `Server ${selectedServerId}`}
            </span>
          </div>
        ) : (
          <p className="text-sm text-muted italic">Not assigned — can be configured from the Partner Detail page.</p>
        )}
      </div>

      {/* Flow Configuration */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          Flow Configuration
        </h3>
        {selectedFlowId ? (
          <div className="flex items-center gap-3 text-sm">
            <ArrowsRightLeftIcon className="w-5 h-5 text-accent" />
            <span className="text-primary font-medium">
              Flow: {flows.find((f) => f.id === selectedFlowId)?.name || `Flow ${selectedFlowId}`}
            </span>
          </div>
        ) : (
          <p className="text-sm text-muted italic">No flow selected — can be configured from the Partner Detail page.</p>
        )}
      </div>

      {/* Test Connection */}
      <div className="card">
        <h3 className="text-sm font-semibold text-secondary uppercase tracking-wider mb-3">
          Connectivity Test
        </h3>
        {testResult ? (
          <div className="flex items-center gap-3 text-sm">
            {testResult.success ? (
              <>
                <CheckIcon className="w-5 h-5 text-[rgb(60,200,120)]" />
                <span className="text-[rgb(60,200,120)] font-medium">Passed</span>
              </>
            ) : (
              <>
                <ExclamationTriangleIcon className="w-5 h-5 text-[rgb(240,120,120)]" />
                <span className="text-[rgb(240,120,120)] font-medium">Failed: {testResult.message}</span>
              </>
            )}
          </div>
        ) : (
          <p className="text-sm text-muted italic">Not tested — you can test connectivity from the Partner Detail page.</p>
        )}
      </div>

      {/* What happens next */}
      <div className="card border-2 border-blue-100 bg-accent-soft/50">
        <h3 className="text-sm font-semibold text-blue-900 mb-3">Partner has been created</h3>
        <ol className="space-y-2 text-sm text-blue-800">
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">1</span>
            <span>Your partner record was created with status <strong>PENDING</strong> and a unique slug for API references.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">2</span>
            <span>Transfer accounts have been provisioned for each selected protocol with home directories created automatically.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">3</span>
            <span>Contacts are saved and the primary contact will receive onboarding notifications (if configured).</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="flex-shrink-0 w-5 h-5 bg-accent text-white rounded-full flex items-center justify-center text-xs font-bold mt-0.5">4</span>
            <span>Click <strong>"Finish & View Partner"</strong> to go to the Partner Detail page where you can <strong>activate</strong> the partner and configure webhooks.</span>
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
      case 7:
        return renderStep7()
      case 8:
        return renderStep8()
      case 9:
        return renderStep9()
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
          company details, protocol selection, contacts, transfer accounts, SLA configuration, server assignment, flow configuration, connectivity test, and final review.
        </p>
        <div className="flex items-center gap-4 mt-3 text-xs text-blue-200">
          <span>Step {step} of {STEPS.length}: <strong className="text-white">{STEPS[step - 1].label}</strong></span>
          <span className="text-blue-300">|</span>
          <span>Estimated time: 3-5 minutes</span>
        </div>
      </div>

      {renderStepIndicator()}
      {/* Wrap step content in a <form> so useEnterAdvances can find a
          common ancestor and walk tabbable inputs. The outer flow keeps
          using explicit Back/Next buttons — we prevent default submit so
          the buttons stay in control. */}
      <form onKeyDown={onStepKeyDown} onSubmit={(e) => e.preventDefault()}>
        {renderCurrentStep()}
      </form>

      {/* Navigation Bar */}
      <div className="flex items-center justify-between pt-4 border-t">
        <button
          type="button"
          className="btn-secondary"
          onClick={handleBack}
          disabled={step === 1 || (createdPartnerId && step === 6)}
        >
          Back
        </button>

        {step < TOTAL_STEPS ? (
          <button type="button" className="btn-primary" onClick={handleNext}
            disabled={step === 5 && createMut.isPending}
          >
            {step === 5 && createMut.isPending ? (
              <span className="flex items-center gap-2"><LoadingSpinner /> Creating...</span>
            ) : (
              'Next'
            )}
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
              'Finish & View Partner'
            )}
          </button>
        )}
      </div>
    </div>
  )
}
