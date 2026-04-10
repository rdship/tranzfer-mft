import { useState, useEffect, useCallback, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import {
  XMarkIcon, ArrowTopRightOnSquareIcon, ExclamationTriangleIcon,
  ArrowPathIcon,
} from '@heroicons/react/24/outline'
import { onboardingApi, configApi } from '../api/client'
import FormField, { friendlyError } from './FormField'

// ── Config type metadata ───────────────────────────────────────────────────

const TYPE_META = {
  flow:            { label: 'Flow',             route: '/flows',                  icon: '\u2699\uFE0F' },
  account:         { label: 'Account',          route: '/accounts',               icon: '\uD83D\uDC64' },
  partner:         { label: 'Partner',          route: (id) => `/partners/${id}`, icon: '\uD83E\uDD1D' },
  server:          { label: 'Server Instance',  route: '/server-instances',       icon: '\uD83D\uDDA5\uFE0F' },
  mapping:         { label: 'Folder Mapping',   route: '/folder-mappings',        icon: '\uD83D\uDCC1' },
  destination:     { label: 'Ext. Destination', route: '/external-destinations',  icon: '\uD83C\uDF10' },
  securityProfile: { label: 'Security Profile', route: '/security-profiles',      icon: '\uD83D\uDD12' },
}

// ── API helpers ────────────────────────────────────────────────────────────

function fetchConfig(type, id) {
  switch (type) {
    case 'flow':            return configApi.get(`/api/flows/${id}`).then(r => r.data)
    case 'account':         return onboardingApi.get(`/api/accounts/${id}`).then(r => r.data)
    case 'partner':         return onboardingApi.get(`/api/partners/${id}`).then(r => r.data)
    case 'server':          return onboardingApi.get(`/api/servers/${id}`).then(r => r.data)
    case 'mapping':         return onboardingApi.get(`/api/folder-mappings/${id}`).then(r => r.data)
    case 'destination':     return configApi.get('/api/external-destinations').then(r => {
      const list = Array.isArray(r.data) ? r.data : []
      return list.find(d => String(d.id) === String(id)) || null
    })
    case 'securityProfile': return configApi.get(`/api/listener-security-policies/${id}`).then(r => r.data)
    default:                return Promise.reject(new Error(`Unknown config type: ${type}`))
  }
}

function saveConfig(type, id, data) {
  switch (type) {
    case 'flow':            return configApi.put(`/api/flows/${id}`, data).then(r => r.data)
    case 'account':         return onboardingApi.put(`/api/accounts/${id}`, data).then(r => r.data)
    case 'partner':         return onboardingApi.put(`/api/partners/${id}`, data).then(r => r.data)
    case 'server':          return onboardingApi.patch(`/api/servers/${id}`, data).then(r => r.data)
    case 'mapping':         return onboardingApi.put(`/api/folder-mappings/${id}`, data).then(r => r.data)
    case 'securityProfile': return configApi.put(`/api/listener-security-policies/${id}`, data).then(r => r.data)
    default:                return Promise.reject(new Error(`Save not supported for: ${type}`))
  }
}

// Query keys to invalidate after save
function invalidationKeys(type) {
  switch (type) {
    case 'flow':            return [['flows'], ['flows-for-scheduler']]
    case 'account':         return [['accounts']]
    case 'partner':         return [['partners'], ['partner-stats']]
    case 'server':          return [['servers'], ['server-instances']]
    case 'mapping':         return [['folder-mappings'], ['mappings']]
    case 'destination':     return [['external-destinations']]
    case 'securityProfile': return [['security-policies']]
    default:                return []
  }
}

// ── Toggle component ───────────────────────────────────────────────────────

function Toggle({ checked, onChange, disabled }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`
        relative inline-flex h-5 w-9 flex-shrink-0 cursor-pointer rounded-full
        border-2 border-transparent transition-colors duration-200 ease-in-out
        focus:outline-none focus:ring-2 focus:ring-[rgb(var(--accent))] focus:ring-offset-2
        focus:ring-offset-[rgb(var(--surface))]
        ${checked ? 'bg-[rgb(var(--accent))]' : 'bg-[rgb(var(--border))]'}
        ${disabled ? 'opacity-50 cursor-not-allowed' : ''}
      `}
    >
      <span
        className={`
          pointer-events-none inline-block h-4 w-4 transform rounded-full
          bg-white shadow ring-0 transition duration-200 ease-in-out
          ${checked ? 'translate-x-4' : 'translate-x-0'}
        `}
      />
    </button>
  )
}

// ── Per-type form field definitions ────────────────────────────────────────

function getFieldDefs(type) {
  switch (type) {
    case 'flow':
      return [
        { key: 'name',        label: 'Name',        type: 'text',     required: true },
        { key: 'description', label: 'Description',  type: 'textarea' },
        { key: 'enabled',     label: 'Enabled',      type: 'toggle' },
        { key: 'priority',    label: 'Priority',     type: 'number',   min: 0, max: 100 },
        { key: '_steps',      label: 'Steps',        type: 'readonly', render: (val, data) => {
          const count = Array.isArray(data?.steps) ? data.steps.length : 0
          return count > 0
            ? `${count} step${count !== 1 ? 's' : ''} configured (edit on Flows page)`
            : 'No steps configured'
        }},
      ]

    case 'account':
      return [
        { key: 'username',              label: 'Username',                type: 'text',   readOnly: true },
        { key: 'protocol',              label: 'Protocol',               type: 'select',  options: ['SFTP', 'FTP', 'FTP_WEB'] },
        { key: 'active',                label: 'Active',                 type: 'toggle' },
        { key: 'homeDirectory',         label: 'Home Directory',         type: 'text' },
        { key: 'qosTier',              label: 'QoS Tier',              type: 'select',  options: ['HIGH', 'MEDIUM', 'LOW'] },
        { key: 'uploadBandwidthLimit',  label: 'Upload Limit (MB/s)',   type: 'number',  min: 0 },
        { key: 'downloadBandwidthLimit',label: 'Download Limit (MB/s)', type: 'number',  min: 0 },
        { key: 'maxConcurrentSessions', label: 'Max Concurrent Sessions',type: 'number', min: 1, max: 100 },
      ]

    case 'partner':
      return [
        { key: 'name',         label: 'Name',          type: 'text',     required: true },
        { key: 'contactEmail', label: 'Contact Email',  type: 'text' },
        { key: 'industry',     label: 'Industry',       type: 'text' },
        { key: 'type',         label: 'Type',           type: 'select',  options: ['CUSTOMER', 'VENDOR', 'FINANCIAL', 'HEALTHCARE', 'GOVERNMENT', 'OTHER'] },
        { key: 'status',       label: 'Status',         type: 'readonly', render: (val) => val || '\u2014' },
        { key: 'notes',        label: 'Notes',          type: 'textarea' },
      ]

    case 'server':
      return [
        { key: 'name',           label: 'Name',            type: 'text',    required: true },
        { key: 'protocol',       label: 'Protocol',        type: 'select',  options: ['SFTP', 'FTP', 'FTP_WEB', 'HTTPS'] },
        { key: 'host',           label: 'Host',            type: 'text' },
        { key: 'port',           label: 'Port',            type: 'number',  min: 1, max: 65535 },
        { key: 'active',         label: 'Active',          type: 'toggle' },
        { key: 'maxConnections', label: 'Max Connections', type: 'number',  min: 1 },
      ]

    case 'mapping':
      return [
        { key: 'sourceAccountName',      label: 'Source Account',     type: 'readonly', render: (v, d) => d?.sourceAccountName || d?.sourceAccount || '\u2014' },
        { key: 'sourceFolder',           label: 'Source Folder',      type: 'text' },
        { key: 'destinationAccountName', label: 'Dest. Account',      type: 'readonly', render: (v, d) => d?.destinationAccountName || d?.destinationAccount || '\u2014' },
        { key: 'destinationFolder',      label: 'Dest. Folder',       type: 'text' },
        { key: 'encryptionOption',       label: 'Encryption',         type: 'select',  options: ['NONE', 'PGP', 'AES'] },
        { key: 'filenamePattern',        label: 'Filename Pattern',   type: 'text',    placeholder: '*.csv' },
        { key: 'active',                 label: 'Active',             type: 'toggle' },
      ]

    case 'destination':
      return [
        { key: 'name',     label: 'Name',     type: 'readonly' },
        { key: 'protocol', label: 'Protocol', type: 'readonly' },
        { key: 'host',     label: 'Host',     type: 'readonly' },
        { key: 'port',     label: 'Port',     type: 'readonly' },
        { key: 'path',     label: 'Path',     type: 'readonly' },
      ]

    case 'securityProfile':
      return [
        { key: 'name',           label: 'Name',            type: 'text',   required: true },
        { key: 'sshCiphers',     label: 'Ciphers',         type: 'text',   placeholder: 'aes256-ctr, aes128-ctr, ...',  asList: true },
        { key: 'sshMacs',        label: 'MACs',            type: 'text',   placeholder: 'hmac-sha2-256, ...',            asList: true },
        { key: 'sshKex',         label: 'Key Exchange',    type: 'text',   placeholder: 'curve25519-sha256, ...',        asList: true },
        { key: 'minTlsVersion',  label: 'Min TLS Version', type: 'select', options: ['TLSv1.2', 'TLSv1.3'] },
      ]

    default:
      return []
  }
}

// ── Main Component ─────────────────────────────────────────────────────────

/**
 * ConfigInlineEditor — slide-in panel for editing any config entity.
 *
 * Props:
 *   open         boolean
 *   onClose      () => void
 *   configType   'flow' | 'account' | 'partner' | 'server' | 'mapping' | 'destination' | 'securityProfile'
 *   configId     string | number (entity ID)
 *   configName   string (display fallback while loading)
 */
export default function ConfigInlineEditor({ open, onClose, configType, configId, configName }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [form, setForm] = useState({})
  const [dirty, setDirty] = useState(false)

  const meta = TYPE_META[configType] || { label: configType, route: '/', icon: '\u2699\uFE0F' }
  const fieldDefs = useMemo(() => getFieldDefs(configType), [configType])
  const isReadOnly = configType === 'destination'

  const fullRoute = typeof meta.route === 'function' ? meta.route(configId) : meta.route

  // ── Fetch entity ─────────────────────────────────────────────────────
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['config-inline', configType, configId],
    queryFn: () => fetchConfig(configType, configId),
    enabled: open && !!configId && !!configType,
    staleTime: 30_000,
  })

  // Seed form from fetched data
  useEffect(() => {
    if (data) {
      const initial = {}
      for (const def of fieldDefs) {
        if (def.type === 'readonly') continue
        let value = data[def.key]
        // Convert arrays (like ciphers list) to comma-separated strings for text inputs
        if (def.asList && Array.isArray(value)) {
          value = value.join(', ')
        }
        initial[def.key] = value ?? (def.type === 'toggle' ? false : def.type === 'number' ? '' : '')
      }
      setForm(initial)
      setDirty(false)
    }
  }, [data, fieldDefs])

  // Reset on close
  useEffect(() => {
    if (!open) { setForm({}); setDirty(false) }
  }, [open])

  // ── Save mutation ────────────────────────────────────────────────────
  const saveMutation = useMutation({
    mutationFn: () => {
      // Build payload from form, merging with original data
      const payload = { ...data }
      for (const def of fieldDefs) {
        if (def.type === 'readonly' || def.readOnly) continue
        let val = form[def.key]
        // Convert comma-separated text back to array for list fields
        if (def.asList && typeof val === 'string') {
          val = val.split(',').map(s => s.trim()).filter(Boolean)
        }
        if (def.type === 'number' && val !== '' && val != null) {
          val = Number(val)
        }
        payload[def.key] = val
      }
      return saveConfig(configType, configId, payload)
    },
    onSuccess: () => {
      toast.success(`${meta.label} updated`)
      // Invalidate related query caches
      for (const key of invalidationKeys(configType)) {
        queryClient.invalidateQueries({ queryKey: key })
      }
      queryClient.invalidateQueries({ queryKey: ['config-inline', configType, configId] })
      setDirty(false)
      onClose()
    },
    onError: (err) => {
      toast.error(friendlyError(err))
    },
  })

  // ── Field change handler ─────────────────────────────────────────────
  const handleChange = useCallback((key, value) => {
    setForm(prev => ({ ...prev, [key]: value }))
    setDirty(true)
  }, [])

  // ── Keyboard: Escape to close ────────────────────────────────────────
  useEffect(() => {
    if (!open) return
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  // ── Render ───────────────────────────────────────────────────────────
  if (!open) return null

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/50 z-50 transition-opacity" onClick={onClose} />

      {/* Slide-in panel */}
      <div
        className="fixed top-0 right-0 h-full z-50 flex flex-col bg-[rgb(var(--canvas))] border-l border-[rgb(var(--border))] shadow-2xl"
        style={{ width: 'min(480px, 100vw)', animation: 'slideInRight 0.2s ease-out' }}
      >
        {/* ── Header ──────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between p-4 border-b border-[rgb(var(--border))] flex-shrink-0">
          <div className="min-w-0 flex items-center gap-2">
            <span className="text-lg">{meta.icon}</span>
            <div className="min-w-0">
              <h2 className="text-sm font-bold text-[rgb(var(--tx-primary))] truncate">
                Edit {meta.label}
              </h2>
              <p className="text-xs text-[rgb(var(--tx-secondary))] truncate">
                {data?.name || data?.username || configName || configId}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-[rgb(var(--hover))] transition-colors flex-shrink-0"
          >
            <XMarkIcon className="w-5 h-5 text-[rgb(var(--tx-secondary))]" />
          </button>
        </div>

        {/* ── Scrollable Content ──────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto p-5">
          {isLoading ? (
            <LoadingSkeleton />
          ) : error ? (
            <ErrorState error={error} onRetry={refetch} />
          ) : (
            <div className="space-y-4">
              {fieldDefs.map((def) => (
                <FieldRenderer
                  key={def.key}
                  def={def}
                  value={def.type === 'readonly' ? (data?.[def.key]) : form[def.key]}
                  data={data}
                  onChange={(val) => handleChange(def.key, val)}
                />
              ))}

              {/* Link to full page */}
              <div className="pt-2">
                <button
                  onClick={() => { onClose(); navigate(fullRoute) }}
                  className="inline-flex items-center gap-1.5 text-xs text-[rgb(100,140,255)] hover:underline"
                >
                  <ArrowTopRightOnSquareIcon className="w-3.5 h-3.5" />
                  Open full {meta.label.toLowerCase()} page
                </button>
              </div>

              {/* Destination read-only notice */}
              {isReadOnly && (
                <div className="flex items-start gap-2 p-3 rounded-lg bg-[rgb(var(--hover))] border border-[rgb(var(--border))]">
                  <ExclamationTriangleIcon className="w-4 h-4 text-[rgb(240,200,100)] flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-[rgb(var(--tx-secondary))]">
                    External destinations are read-only in this view.
                    Edit on the <button onClick={() => { onClose(); navigate('/external-destinations') }} className="text-[rgb(100,140,255)] hover:underline">External Destinations page</button>.
                  </p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* ── Action Bar ──────────────────────────────────────────────── */}
        {!isLoading && !error && !isReadOnly && (
          <div className="flex items-center gap-2 p-4 border-t border-[rgb(var(--border))] flex-shrink-0 bg-[rgb(var(--surface))]">
            <div className="flex-1" />
            <button onClick={onClose} className="btn-secondary text-sm">
              Cancel
            </button>
            <button
              onClick={() => saveMutation.mutate()}
              disabled={!dirty || saveMutation.isPending}
              className="btn-primary text-sm"
            >
              {saveMutation.isPending ? 'Saving...' : 'Save'}
            </button>
          </div>
        )}

        {/* Read-only footer for destinations */}
        {!isLoading && !error && isReadOnly && (
          <div className="flex items-center gap-2 p-4 border-t border-[rgb(var(--border))] flex-shrink-0 bg-[rgb(var(--surface))]">
            <div className="flex-1" />
            <button onClick={onClose} className="btn-secondary text-sm">
              Close
            </button>
          </div>
        )}
      </div>

      {/* Slide-in animation */}
      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to   { transform: translateX(0); }
        }
      `}</style>
    </>
  )
}

// ── Field Renderer ─────────────────────────────────────────────────────────

function FieldRenderer({ def, value, data, onChange }) {
  const { key, label, type, readOnly, required, options, placeholder, min, max, render, asList } = def

  // Read-only fields
  if (type === 'readonly' || readOnly) {
    const display = render ? render(value, data) : (value ?? '\u2014')
    return (
      <FormField label={label}>
        <div className="px-3 py-2 text-sm text-[rgb(var(--tx-secondary))] bg-[rgb(var(--hover))] rounded-lg border border-[rgb(var(--border))]">
          {display}
        </div>
      </FormField>
    )
  }

  // Toggle
  if (type === 'toggle') {
    return (
      <div className="flex items-center justify-between py-1">
        <label className="text-sm font-medium text-[rgb(var(--tx-primary))]">{label}</label>
        <Toggle checked={!!value} onChange={onChange} />
      </div>
    )
  }

  // Select
  if (type === 'select') {
    return (
      <FormField label={label} required={required}>
        <select
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">Select...</option>
          {options.map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      </FormField>
    )
  }

  // Textarea
  if (type === 'textarea') {
    return (
      <FormField label={label} required={required}>
        <textarea
          rows={3}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className="resize-y"
        />
      </FormField>
    )
  }

  // Number
  if (type === 'number') {
    return (
      <FormField label={label} required={required}>
        <input
          type="number"
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
          min={min}
          max={max}
          placeholder={placeholder}
        />
      </FormField>
    )
  }

  // Text (default) — also handles asList fields displayed as comma-separated text
  return (
    <FormField label={label} required={required} helper={asList ? 'Comma-separated list' : undefined}>
      <input
        type="text"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
      />
    </FormField>
  )
}

// ── Loading Skeleton ───────────────────────────────────────────────────────

function LoadingSkeleton() {
  return (
    <div className="space-y-4">
      {[1, 2, 3, 4, 5].map(i => (
        <div key={i} className="space-y-1.5">
          <div className="h-3.5 w-24 rounded bg-[rgb(var(--hover))] animate-pulse" />
          <div className="h-9 w-full rounded-lg bg-[rgb(var(--hover))] animate-pulse" />
        </div>
      ))}
    </div>
  )
}

// ── Error State ────────────────────────────────────────────────────────────

function ErrorState({ error, onRetry }) {
  return (
    <div className="text-center py-8">
      <ExclamationTriangleIcon className="w-10 h-10 mx-auto text-[rgb(240,120,120)] mb-3" />
      <p className="text-sm text-[rgb(var(--tx-secondary))] mb-3">
        {error?.response?.data?.message || error?.message || 'Failed to load configuration'}
      </p>
      <button onClick={onRetry} className="btn-secondary text-sm">
        <ArrowPathIcon className="w-4 h-4" /> Retry
      </button>
    </div>
  )
}
