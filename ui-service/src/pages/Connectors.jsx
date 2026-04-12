import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi, onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import LoadingSpinner from '../components/LoadingSpinner'
import FormField, { validators } from '../components/FormField'
import useGentleValidation from '../hooks/useGentleValidation'
import useEnterAdvances from '../hooks/useEnterAdvances'
import toast from 'react-hot-toast'
import {
  PlusIcon, BoltIcon, LinkIcon, TrashIcon, PencilSquareIcon,
  CheckCircleIcon, XCircleIcon, ArrowPathIcon, MagnifyingGlassIcon,
  ExclamationTriangleIcon,
} from '@heroicons/react/24/outline'

// ── Admin connector constants ──────────────────────────────────────────────────
const CONN_TYPES = ['SERVICENOW', 'PAGERDUTY', 'SLACK', 'TEAMS', 'OPSGENIE', 'WEBHOOK']
const EVENTS = [
  'TRANSFER_FAILED', 'FLOW_FAILED', 'FLOW_COMPLETED',
  'AI_BLOCKED', 'INTEGRITY_FAIL', 'FLOW_FAIL', 'QUARANTINE',
  'ANOMALY_DETECTED', 'SLA_BREACH', 'LICENSE_EXPIRED',
]

// ── Partner webhook constants ──────────────────────────────────────────────────
const WEBHOOK_EVENTS = ['FLOW_COMPLETED', 'FLOW_FAILED']
const emptyWebhookForm = { partnerName: '', url: '', secret: '', events: ['FLOW_COMPLETED', 'FLOW_FAILED'], active: true, description: '' }

// ── Partner Webhook card ───────────────────────────────────────────────────────
function WebhookCard({ hook, onEdit, onDelete, onTest, testPending }) {
  return (
    <div className="card flex items-center gap-4">
      <div
        className="w-2 h-2 rounded-full flex-shrink-0"
        style={{ background: hook.active ? '#22c55e' : '#52525b' }}
      />
      <LinkIcon className="w-5 h-5 text-violet-400 flex-shrink-0" />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <h3 className="font-semibold text-sm" style={{ color: 'rgb(var(--tx-primary))' }}>
            {hook.partnerName}
          </h3>
          {!hook.active && (
            <span className="badge badge-gray text-[10px]">inactive</span>
          )}
          {hook.secret && (
            <span className="badge badge-green text-[10px]">HMAC signed</span>
          )}
        </div>
        <p className="text-xs font-mono truncate max-w-md mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
          {hook.url}
        </p>
        <div className="flex gap-1 mt-1 flex-wrap">
          {(hook.events || []).map(e => (
            <span key={e} className={`badge text-[10px] ${e === 'FLOW_COMPLETED' ? 'badge-green' : 'badge-red'}`}>{e}</span>
          ))}
        </div>
      </div>
      <div className="text-right text-xs flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }}>
        <p>{hook.totalCalls || 0} calls · {hook.failedCalls || 0} failed</p>
        {hook.lastTriggered && <p>Last: {new Date(hook.lastTriggered).toLocaleString()}</p>}
      </div>
      <div className="flex items-center gap-1 flex-shrink-0">
        <button
          onClick={() => onTest(hook.id)}
          disabled={testPending}
          className="p-1.5 rounded-lg transition-colors"
          style={{ color: 'rgb(var(--tx-muted))' }}
          onMouseEnter={e => e.currentTarget.style.color = '#8b5cf6'}
          onMouseLeave={e => e.currentTarget.style.color = 'rgb(var(--tx-muted))'}
          title="Send test event"
          aria-label="Send test event"
        >
          <ArrowPathIcon className="w-4 h-4" />
        </button>
        <button
          onClick={() => onEdit(hook)}
          className="p-1.5 rounded-lg transition-colors"
          style={{ color: 'rgb(var(--tx-muted))' }}
          onMouseEnter={e => e.currentTarget.style.color = '#60a5fa'}
          onMouseLeave={e => e.currentTarget.style.color = 'rgb(var(--tx-muted))'}
          title="Edit"
          aria-label="Edit"
        >
          <PencilSquareIcon className="w-4 h-4" />
        </button>
        <button
          onClick={() => onDelete(hook.id)}
          className="p-1.5 rounded-lg transition-colors"
          style={{ color: 'rgb(var(--tx-muted))' }}
          onMouseEnter={e => e.currentTarget.style.color = '#ef4444'}
          onMouseLeave={e => e.currentTarget.style.color = 'rgb(var(--tx-muted))'}
          title="Delete"
          aria-label="Delete"
        >
          <TrashIcon className="w-4 h-4" />
        </button>
      </div>
    </div>
  )
}

// ── Main page ──────────────────────────────────────────────────────────────────
export default function Connectors() {
  const qc = useQueryClient()

  // Admin connectors
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', type: 'SLACK', url: '', authToken: '', triggerEvents: ['TRANSFER_FAILED', 'FLOW_FAILED'], minSeverity: 'HIGH',
    // Type-specific fields
    channel: '', apiKey: '', region: 'us', priority: 'P3', headers: '', authType: 'NONE', bearerToken: '', basicUser: '', basicPass: '',
  })

  const [editingConn, setEditingConn] = useState(null)
  const [confirmDeleteConn, setConfirmDeleteConn] = useState(null)
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState('name')
  const [sortDir, setSortDir] = useState('asc')

  // Partner webhooks
  const [showWebhookModal, setShowWebhookModal] = useState(false)
  const [editingWebhook, setEditingWebhook] = useState(null) // null = create, object = edit
  const [webhookForm, setWebhookForm] = useState(emptyWebhookForm)
  const [confirmDelete, setConfirmDelete] = useState(null)

  // ── Queries ──
  const { data: connectors = [], isLoading, isError: connectorsError, refetch: refetchConnectors } = useQuery({
    queryKey: ['connectors'],
    queryFn: () => configApi.get('/api/connectors').then(r => r.data),
    retry: 1
  })

  const { data: partnerWebhooks = [], isLoading: webhooksLoading, isError: webhooksError, refetch: refetchWebhooks } = useQuery({
    queryKey: ['partner-webhooks'],
    queryFn: () => onboardingApi.get('/api/partner-webhooks').then(r => r.data),
    refetchInterval: 30000,
    retry: 1
  })

  const isError = connectorsError || webhooksError
  const refetch = () => { refetchConnectors(); refetchWebhooks() }

  const { data: partners = [] } = useQuery({
    queryKey: ['partners-for-connector'],
    queryFn: () => onboardingApi.get('/api/partners').then(r => r.data)
  })

  // ── Admin connector mutations ──
  const createMut = useMutation({
    mutationFn: (d) => configApi.post('/api/connectors', d),
    onSuccess: () => { qc.invalidateQueries(['connectors']); setShowCreate(false); toast.success('Connector created') }
  })
  const updateConnMut = useMutation({
    mutationFn: ({ id, data }) => configApi.put(`/api/connectors/${id}`, data),
    onSuccess: () => { qc.invalidateQueries(['connectors']); setEditingConn(null); toast.success('Connector updated') },
    onError: err => toast.error(err.response?.data?.message || 'Update failed'),
  })
  const deleteConnMut = useMutation({
    mutationFn: (id) => configApi.delete(`/api/connectors/${id}`),
    onSuccess: () => { qc.invalidateQueries(['connectors']); toast.success('Connector deleted') },
    onError: err => toast.error(err.response?.data?.message || 'Delete failed'),
  })
  const testMut = useMutation({
    mutationFn: (id) => configApi.post(`/api/connectors/${id}/test`).then(r => r.data),
    onSuccess: (d) => toast.success('Test: ' + d.status),
    onError: () => toast.error('Test failed'),
  })

  // ── Partner webhook mutations ──
  const saveWebhookMut = useMutation({
    mutationFn: (data) => editingWebhook
      ? onboardingApi.put(`/api/partner-webhooks/${editingWebhook.id}`, data).then(r => r.data)
      : onboardingApi.post('/api/partner-webhooks', data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['partner-webhooks'])
      setShowWebhookModal(false)
      setEditingWebhook(null)
      toast.success(editingWebhook ? 'Webhook updated' : 'Webhook created')
    },
    onError: err => toast.error(err.response?.data?.message || 'Save failed'),
  })

  const deleteWebhookMut = useMutation({
    mutationFn: (id) => onboardingApi.delete(`/api/partner-webhooks/${id}`),
    onSuccess: () => { qc.invalidateQueries(['partner-webhooks']); toast.success('Webhook deleted') },
    onError: () => toast.error('Delete failed'),
  })

  const testWebhookMut = useMutation({
    mutationFn: (id) => onboardingApi.post(`/api/partner-webhooks/${id}/test`).then(r => r.data),
    onSuccess: (d) => d.status === 'SUCCESS'
      ? toast.success(`Test delivered — HTTP ${d.httpStatus}`)
      : toast.error(`Test failed: ${d.error}`),
    onError: err => toast.error(err.response?.data?.error || 'Test failed — check the URL'),
  })

  // Gentle validation for partner webhook form
  const webhookValidation = useGentleValidation({
    rules: [
      { field: 'partnerName', label: 'Partner name', required: true },
      { field: 'url', label: 'Webhook URL', required: true, validate: validators.url },
      { field: 'events', label: 'Trigger events', required: true, message: 'Pick at least one event to trigger this webhook' },
    ],
    onValid: (data) => saveWebhookMut.mutate(data),
    recordKind: 'webhook',
  })
  const webhookErrors = webhookValidation.errors
  const onWebhookKeyDown = useEnterAdvances({ onSubmit: () => webhookValidation.handleSubmit(webhookForm)(null) })

  // Reset type-specific fields when connector type changes
  const handleConnectorTypeChange = (newType) => {
    setForm(f => ({
      ...f,
      type: newType,
      url: '', channel: '', apiKey: '', region: 'us', priority: 'P3',
      headers: '', authType: 'NONE', bearerToken: '', basicUser: '', basicPass: '',
    }))
  }

  const openCreateWebhook = () => {
    setEditingWebhook(null)
    setWebhookForm(emptyWebhookForm)
    setShowWebhookModal(true)
  }

  const openEditWebhook = (hook) => {
    setEditingWebhook(hook)
    setWebhookForm({ partnerName: hook.partnerName, url: hook.url, secret: '', events: hook.events || ['FLOW_COMPLETED', 'FLOW_FAILED'], active: hook.active, description: hook.description || '' })
    setShowWebhookModal(true)
  }

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  const sortedConnectors = useMemo(() => {
    const list = (connectors || []).filter(c => {
      if (!search) return true
      const q = search.toLowerCase()
      return c.name?.toLowerCase().includes(q) || c.type?.toLowerCase().includes(q) || c.url?.toLowerCase().includes(q)
    })
    const arr = [...list]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'active') {
        va = a.active ? 1 : 0; vb = b.active ? 1 : 0
      } else {
        va = a[sortBy] ?? ''; vb = b[sortBy] ?? ''
      }
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [connectors, search, sortBy, sortDir])

  const sortedWebhooks = useMemo(() => {
    const list = (partnerWebhooks || []).filter(hook => {
      if (!search) return true
      const q = search.toLowerCase()
      return hook.partnerName?.toLowerCase().includes(q) || hook.url?.toLowerCase().includes(q)
    })
    const arr = [...list]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'active') {
        va = a.active ? 1 : 0; vb = b.active ? 1 : 0
      } else {
        va = a[sortBy] ?? ''; vb = b[sortBy] ?? ''
      }
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [partnerWebhooks, search, sortBy, sortDir])

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-8">

      {isError && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">Failed to load data — service may be unavailable</span>
          </div>
          <button onClick={() => refetch()} className="text-xs text-red-400 hover:text-red-300 underline">Retry</button>
        </div>
      )}

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>Connectors &amp; Webhooks</h1>
          <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>Manage external integrations and partner webhook notifications</p>
        </div>
        <div className="relative">
          <MagnifyingGlassIcon className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Search connectors..." className="pl-9 w-64" />
        </div>
      </div>

      {/* ── Admin Connectors ── */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>External Connectors</h2>
            <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
              ServiceNow, Slack, PagerDuty, Teams — admin-level alerts
            </p>
          </div>
          <button className="btn-primary" onClick={() => setShowCreate(true)}>
            <PlusIcon className="w-4 h-4" /> Add Connector
          </button>
        </div>

        <div className="flex items-center gap-2 mb-3 text-xs text-secondary">
          <span className="text-muted">Sort:</span>
          {[
            { key: 'name', label: 'Name' },
            { key: 'type', label: 'Type' },
            { key: 'active', label: 'Active' },
          ].map(col => (
            <button key={col.key} onClick={() => toggleSort(col.key)}
              className={`px-2 py-0.5 rounded text-xs font-medium transition-colors ${
                sortBy === col.key ? 'bg-blue-100 text-blue-700' : 'bg-hover text-secondary hover:bg-gray-200'
              }`}>
              {col.label} {sortBy === col.key && (sortDir === 'asc' ? '\u2191' : '\u2193')}
            </button>
          ))}
        </div>

        <div className="space-y-3">
          {connectors.length === 0 ? (
            <div className="card flex flex-col items-center justify-center py-16 px-4 text-center">
              <div
                className="w-14 h-14 rounded-full flex items-center justify-center mb-3"
                style={{ background: 'rgba(79, 70, 229, 0.1)' }}
              >
                <BoltIcon className="w-7 h-7" style={{ color: 'rgb(var(--accent, 79 70 229))' }} />
              </div>
              <h3 className="text-base font-semibold mb-1" style={{ color: 'rgb(var(--tx-primary))' }}>
                No connectors yet
              </h3>
              <p className="text-xs max-w-md mb-4" style={{ color: 'rgb(148, 163, 184)' }}>
                Connectors deliver notification events to Slack, Teams, PagerDuty, and other destinations.
              </p>
              <button
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
                style={{ background: 'rgb(var(--accent, 79 70 229))', color: '#fff' }}
                onClick={() => setShowCreate(true)}
              >
                <PlusIcon className="w-3.5 h-3.5" />
                New Connector
              </button>
            </div>
          ) : sortedConnectors.map(c => (
            <div key={c.id} className="card flex items-center gap-4">
              <BoltIcon className="w-6 h-6 text-blue-400 flex-shrink-0" />
              <div className="flex-1 min-w-0">
                <h3 className="font-semibold text-sm" style={{ color: 'rgb(var(--tx-primary))' }}>{c.name}</h3>
                <p className="text-xs truncate max-w-md" style={{ color: 'rgb(var(--tx-muted))' }}>{c.type} — {c.url?.substring(0, 50)}…</p>
                <div className="flex gap-1 mt-1 flex-wrap">
                  {(c.triggerEvents || []).map(e => <span key={e} className="badge badge-gray text-[10px]">{e}</span>)}
                </div>
              </div>
              <div className="text-right text-xs flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }}>
                <p>{c.totalNotifications || 0} sent</p>
                {c.lastTriggered && <p>Last: {new Date(c.lastTriggered).toLocaleString()}</p>}
              </div>
              <div className="flex gap-1.5 flex-shrink-0">
                <button onClick={() => testMut.mutate(c.id)} className="btn-secondary text-xs">Test</button>
                <button onClick={() => { setEditingConn(c); setForm({ name: c.name || '', type: c.type || 'SLACK', url: c.url || '', authToken: c.authToken || '', triggerEvents: c.triggerEvents || ['TRANSFER_FAILED'], minSeverity: c.minSeverity || 'HIGH', channel: c.channel || '', apiKey: c.apiKey || '', region: c.region || 'us', priority: c.priority || 'P3', headers: c.headers || '', authType: c.authType || 'NONE', bearerToken: c.bearerToken || '', basicUser: c.basicUser || '', basicPass: c.basicPass || '' }) }} className="btn-secondary text-xs"><PencilSquareIcon className="w-3.5 h-3.5" /></button>
                <button onClick={() => setConfirmDeleteConn(c)} className="btn-secondary text-xs text-red-500 hover:text-red-700"><TrashIcon className="w-3.5 h-3.5" /></button>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* ── Partner Webhooks ── */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>Partner Webhooks</h2>
            <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
              HTTP POST notifications to partner systems on flow COMPLETED / FAILED — optionally HMAC-signed
            </p>
          </div>
          <button className="btn-primary" onClick={openCreateWebhook}>
            <PlusIcon className="w-4 h-4" /> Add Webhook
          </button>
        </div>

        {webhooksLoading ? (
          <LoadingSpinner text="Loading webhooks…" />
        ) : partnerWebhooks.length === 0 ? (
          <div className="card text-center py-8" style={{ color: 'rgb(var(--tx-muted))' }}>
            No partner webhooks configured. Add one to notify a partner system when their transfers complete or fail.
          </div>
        ) : (
          <div className="space-y-3">
            {sortedWebhooks.map(hook => (
              <WebhookCard
                key={hook.id}
                hook={hook}
                onEdit={openEditWebhook}
                onDelete={(id) => setConfirmDelete({ id, name: hook.name || hook.partnerName || 'this webhook' })}
                onTest={(id) => testWebhookMut.mutate(id)}
                testPending={testWebhookMut.isPending}
              />
            ))}
          </div>
        )}
      </section>

      {/* ── Create Admin Connector Modal ── */}
      {showCreate && (
        <Modal title="Add Connector" size="lg" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="slack-alerts" /></div>
              <div><label>Type</label><select value={form.type} onChange={e => handleConnectorTypeChange(e.target.value)}>{CONN_TYPES.map(t => <option key={t} value={t}>{t}</option>)}</select></div>
            </div>

            {/* ── SLACK-specific fields ── */}
            {form.type === 'SLACK' && (
              <>
                <div><label>Webhook URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required placeholder="https://hooks.slack.com/services/T00/B00/xxx" /></div>
                <div><label>Channel <span className="text-xs font-normal opacity-60">(optional override)</span></label><input value={form.channel} onChange={e => setForm(f => ({...f, channel: e.target.value}))} placeholder="#mft-alerts" />
                  <p className="mt-1 text-xs text-muted">Leave blank to use the channel configured in the Slack webhook.</p>
                </div>
              </>
            )}

            {/* ── TEAMS-specific fields ── */}
            {form.type === 'TEAMS' && (
              <div><label>Webhook URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required placeholder="https://outlook.office.com/webhook/..." /></div>
            )}

            {/* ── WEBHOOK-specific fields ── */}
            {form.type === 'WEBHOOK' && (
              <>
                <div><label>URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required placeholder="https://your-system.com/webhook" /></div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label>Auth Type</label>
                    <select value={form.authType} onChange={e => setForm(f => ({...f, authType: e.target.value, bearerToken: '', basicUser: '', basicPass: ''}))}>
                      <option value="NONE">None</option>
                      <option value="BEARER">Bearer Token</option>
                      <option value="BASIC">Basic Auth</option>
                    </select>
                  </div>
                  {form.authType === 'BEARER' && (
                    <div><label>Bearer Token</label><input type="password" value={form.bearerToken} onChange={e => setForm(f => ({...f, bearerToken: e.target.value}))} placeholder="eyJhbGci..." /></div>
                  )}
                  {form.authType === 'BASIC' && (
                    <>
                      <div><label>Username</label><input value={form.basicUser} onChange={e => setForm(f => ({...f, basicUser: e.target.value}))} /></div>
                    </>
                  )}
                </div>
                {form.authType === 'BASIC' && (
                  <div><label>Password</label><input type="password" value={form.basicPass} onChange={e => setForm(f => ({...f, basicPass: e.target.value}))} /></div>
                )}
                <div>
                  <label>Custom Headers <span className="text-xs font-normal opacity-60">(JSON, optional)</span></label>
                  <input value={form.headers} onChange={e => setForm(f => ({...f, headers: e.target.value}))} placeholder='{"X-Custom-Header": "value"}' />
                  <p className="mt-1 text-xs text-muted">Additional headers sent with each webhook request (JSON key-value pairs).</p>
                </div>
              </>
            )}

            {/* ── OPSGENIE-specific fields ── */}
            {form.type === 'OPSGENIE' && (
              <>
                <div><label>API Key</label><input type="password" value={form.apiKey} onChange={e => setForm(f => ({...f, apiKey: e.target.value}))} required placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" /></div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label>Region</label>
                    <select value={form.region} onChange={e => setForm(f => ({...f, region: e.target.value}))}>
                      <option value="us">US (api.opsgenie.com)</option>
                      <option value="eu">EU (api.eu.opsgenie.com)</option>
                    </select>
                  </div>
                  <div>
                    <label>Default Priority</label>
                    <select value={form.priority} onChange={e => setForm(f => ({...f, priority: e.target.value}))}>
                      {['P1', 'P2', 'P3', 'P4', 'P5'].map(p => <option key={p} value={p}>{p}</option>)}
                    </select>
                  </div>
                </div>
              </>
            )}

            {/* ── SERVICENOW / PAGERDUTY — keep generic URL field ── */}
            {['SERVICENOW', 'PAGERDUTY'].includes(form.type) && (
              <div><label>URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required placeholder={form.type === 'SERVICENOW' ? 'https://instance.service-now.com/api/...' : 'https://events.pagerduty.com/v2/enqueue'} /></div>
            )}

            <div>
              <label>Trigger Events</label>
              <div className="mt-1 flex flex-wrap gap-2">
                {EVENTS.map(ev => (
                  <label key={ev} className="flex items-center gap-1.5 cursor-pointer mb-0 text-xs">
                    <input type="checkbox" className="w-auto" checked={form.triggerEvents.includes(ev)}
                      onChange={e => setForm(f => ({...f, triggerEvents: e.target.checked ? [...f.triggerEvents, ev] : f.triggerEvents.filter(x => x !== ev)}))} />
                    {ev}
                  </label>
                ))}
              </div>
            </div>
            <div><label>Min Severity</label>
              <select value={form.minSeverity} onChange={e => setForm(f => ({...f, minSeverity: e.target.value}))}>
                {['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map(s => <option key={s}>{s}</option>)}
              </select>
            </div>
            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>Create</button>
            </div>
          </form>
        </Modal>
      )}

      {/* ── Edit Admin Connector Modal ── */}
      {editingConn && (
        <Modal title="Edit Connector" size="lg" onClose={() => setEditingConn(null)}>
          <form onSubmit={e => { e.preventDefault(); updateConnMut.mutate({ id: editingConn.id, data: form }) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required /></div>
              <div><label>Type</label><select value={form.type} disabled><option>{form.type}</option></select></div>
              <div className="col-span-2"><label>URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required /></div>
              {form.type === 'SLACK' && <div className="col-span-2"><label>Channel</label><input value={form.channel} onChange={e => setForm(f => ({...f, channel: e.target.value}))} placeholder="#alerts" /></div>}
              {form.type === 'PAGERDUTY' && <div><label>API Key</label><input value={form.apiKey} onChange={e => setForm(f => ({...f, apiKey: e.target.value}))} /></div>}
            </div>
            <div><label>Events</label>
              <div className="flex flex-wrap gap-2 mt-1">
                {EVENTS.map(ev => (
                  <label key={ev} className="flex items-center gap-1 text-xs cursor-pointer">
                    <input type="checkbox" checked={form.triggerEvents.includes(ev)}
                      onChange={e => setForm(f => ({...f, triggerEvents: e.target.checked ? [...f.triggerEvents, ev] : f.triggerEvents.filter(x => x !== ev)}))} />
                    {ev}
                  </label>
                ))}
              </div>
            </div>
            <div><label>Min Severity</label>
              <select value={form.minSeverity} onChange={e => setForm(f => ({...f, minSeverity: e.target.value}))}>
                {['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map(s => <option key={s}>{s}</option>)}
              </select>
            </div>
            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={() => setEditingConn(null)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={updateConnMut.isPending}>{updateConnMut.isPending ? 'Saving...' : 'Save'}</button>
            </div>
          </form>
        </Modal>
      )}

      {/* ── Delete Admin Connector Confirm ── */}
      {confirmDeleteConn && (
        <ConfirmDialog
          title="Delete Connector"
          message={`Permanently delete connector "${confirmDeleteConn.name}"?`}
          confirmLabel="Delete"
          variant="danger"
          loading={deleteConnMut.isPending}
          onConfirm={() => { deleteConnMut.mutate(confirmDeleteConn.id); setConfirmDeleteConn(null) }}
          onCancel={() => setConfirmDeleteConn(null)}
        />
      )}

      {/* ── Create / Edit Partner Webhook Modal ── */}
      {showWebhookModal && (
        <Modal
          title={editingWebhook ? 'Edit Partner Webhook' : 'Add Partner Webhook'}
          size="lg"
          onClose={() => { setShowWebhookModal(false); setEditingWebhook(null); webhookValidation.clearAllErrors() }}
        >
          <form
            onSubmit={webhookValidation.handleSubmit(webhookForm)}
            onKeyDown={onWebhookKeyDown}
            className="space-y-4"
          >
            <div className="grid grid-cols-2 gap-3">
              <FormField
                label="Partner"
                required
                name="partnerName"
                error={webhookErrors.partnerName}
                valid={!webhookErrors.partnerName && !!webhookForm.partnerName}
                helper="Which partner this webhook belongs to"
              >
                <select
                  value={webhookForm.partnerName}
                  onChange={e => { setWebhookForm(f => ({...f, partnerName: e.target.value})); webhookValidation.clearFieldError('partnerName') }}
                >
                  <option value="">Select a partner…</option>
                  {partners.map(p => <option key={p.id} value={p.name}>{p.name}{p.industry ? ` (${p.industry})` : p.type ? ` (${p.type})` : ''}</option>)}
                </select>
              </FormField>
              <FormField
                label="Status"
                name="active"
                helper="Active webhooks fire on every matching event"
              >
                <select
                  value={webhookForm.active ? 'true' : 'false'}
                  onChange={e => setWebhookForm(f => ({...f, active: e.target.value === 'true'}))}
                >
                  <option value="true">Active</option>
                  <option value="false">Inactive</option>
                </select>
              </FormField>
            </div>
            <FormField
              label="Webhook URL"
              required
              name="url"
              error={webhookErrors.url}
              valid={!webhookErrors.url && !!webhookForm.url}
              helper="Where we'll POST event payloads. Must be https:// in production."
            >
              <input
                type="url"
                value={webhookForm.url}
                onChange={e => { setWebhookForm(f => ({...f, url: e.target.value})); webhookValidation.clearFieldError('url') }}
                placeholder="https://partner.example.com/webhook/mft"
              />
            </FormField>
            <FormField
              label="Signing Secret"
              name="secret"
              tooltip="Optional HMAC secret. Add this and we'll sign every webhook with SHA-256 so your receiver can verify."
              helper="Leave blank to send unsigned payloads — or paste a secret to enable HMAC-SHA256"
            >
              <input
                type="password"
                value={webhookForm.secret}
                onChange={e => setWebhookForm(f => ({...f, secret: e.target.value}))}
                placeholder="Leave blank to keep existing secret"
                autoComplete="off"
              />
            </FormField>
            <FormField
              label="Trigger Events"
              required
              name="events"
              error={webhookErrors.events}
              valid={!webhookErrors.events && webhookForm.events.length > 0}
              helper="Which platform events trigger this webhook. Pick at least one."
            >
              <div className="mt-1 flex gap-4" data-field-name="events">
                {WEBHOOK_EVENTS.map(ev => (
                  <label key={ev} className="flex items-center gap-2 cursor-pointer mb-0 text-sm">
                    <input
                      type="checkbox"
                      className="w-auto"
                      checked={webhookForm.events.includes(ev)}
                      onChange={e => {
                        setWebhookForm(f => ({...f, events: e.target.checked ? [...f.events, ev] : f.events.filter(x => x !== ev)}))
                        webhookValidation.clearFieldError('events')
                      }}
                    />
                    <span className={`badge text-xs ${ev === 'FLOW_COMPLETED' ? 'badge-green' : 'badge-red'}`}>{ev}</span>
                  </label>
                ))}
              </div>
            </FormField>
            <FormField
              label="Description"
              name="description"
              helper="Optional note about where this webhook goes"
            >
              <input
                value={webhookForm.description}
                onChange={e => setWebhookForm(f => ({...f, description: e.target.value}))}
                placeholder="Notifies Acme's ERP on file delivery"
              />
            </FormField>
            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={() => { setShowWebhookModal(false); setEditingWebhook(null); webhookValidation.clearAllErrors() }}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={saveWebhookMut.isPending}>
                {editingWebhook ? 'Save Changes' : 'Create Webhook'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      <ConfirmDialog
        open={!!confirmDelete}
        variant="danger"
        title="Delete webhook?"
        message={confirmDelete ? `Are you sure you want to delete "${confirmDelete.name}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteWebhookMut.isPending}
        onConfirm={() => { deleteWebhookMut.mutate(confirmDelete.id); setConfirmDelete(null) }}
        onCancel={() => setConfirmDelete(null)}
      />
    </div>
  )
}
