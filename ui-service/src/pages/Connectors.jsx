import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi, onboardingApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import {
  PlusIcon, BoltIcon, LinkIcon, TrashIcon, PencilSquareIcon,
  CheckCircleIcon, XCircleIcon, ArrowPathIcon,
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
  const [form, setForm] = useState({ name: '', type: 'SLACK', url: '', authToken: '', triggerEvents: ['TRANSFER_FAILED', 'FLOW_FAILED'], minSeverity: 'HIGH' })

  // Partner webhooks
  const [showWebhookModal, setShowWebhookModal] = useState(false)
  const [editingWebhook, setEditingWebhook] = useState(null) // null = create, object = edit
  const [webhookForm, setWebhookForm] = useState(emptyWebhookForm)

  // ── Queries ──
  const { data: connectors = [], isLoading } = useQuery({
    queryKey: ['connectors'],
    queryFn: () => configApi.get('/api/connectors').then(r => r.data).catch(() => [])
  })

  const { data: partnerWebhooks = [], isLoading: webhooksLoading } = useQuery({
    queryKey: ['partner-webhooks'],
    queryFn: () => onboardingApi.get('/api/partner-webhooks').then(r => r.data).catch(() => []),
    refetchInterval: 30000,
  })

  // ── Admin connector mutations ──
  const createMut = useMutation({
    mutationFn: (d) => configApi.post('/api/connectors', d),
    onSuccess: () => { qc.invalidateQueries(['connectors']); setShowCreate(false); toast.success('Connector created') }
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

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-8">

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

        <div className="space-y-3">
          {connectors.length === 0 ? (
            <div className="card text-center py-8" style={{ color: 'rgb(var(--tx-muted))' }}>
              No connectors configured. Add one to receive alerts in Slack, PagerDuty, or ServiceNow.
            </div>
          ) : connectors.map(c => (
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
              <button onClick={() => testMut.mutate(c.id)} className="btn-secondary text-xs flex-shrink-0">Test</button>
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
            {partnerWebhooks.map(hook => (
              <WebhookCard
                key={hook.id}
                hook={hook}
                onEdit={openEditWebhook}
                onDelete={(id) => { if (confirm('Delete this webhook?')) deleteWebhookMut.mutate(id) }}
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
              <div><label>Type</label><select value={form.type} onChange={e => setForm(f => ({...f, type: e.target.value}))}>{CONN_TYPES.map(t => <option key={t}>{t}</option>)}</select></div>
            </div>
            <div><label>URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required placeholder="https://hooks.slack.com/services/…" /></div>
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

      {/* ── Create / Edit Partner Webhook Modal ── */}
      {showWebhookModal && (
        <Modal
          title={editingWebhook ? 'Edit Partner Webhook' : 'Add Partner Webhook'}
          size="lg"
          onClose={() => { setShowWebhookModal(false); setEditingWebhook(null) }}
        >
          <form onSubmit={e => { e.preventDefault(); saveWebhookMut.mutate(webhookForm) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label>Partner Name</label>
                <input value={webhookForm.partnerName}
                  onChange={e => setWebhookForm(f => ({...f, partnerName: e.target.value}))}
                  required placeholder="Acme Corp" />
              </div>
              <div>
                <label>Active</label>
                <select value={webhookForm.active ? 'true' : 'false'}
                  onChange={e => setWebhookForm(f => ({...f, active: e.target.value === 'true'}))}>
                  <option value="true">Active</option>
                  <option value="false">Inactive</option>
                </select>
              </div>
            </div>
            <div>
              <label>Webhook URL</label>
              <input value={webhookForm.url}
                onChange={e => setWebhookForm(f => ({...f, url: e.target.value}))}
                required type="url" placeholder="https://partner.example.com/webhook/mft" />
            </div>
            <div>
              <label>Signing Secret <span className="text-xs font-normal opacity-60">(optional — for HMAC-SHA256 verification)</span></label>
              <input value={webhookForm.secret}
                onChange={e => setWebhookForm(f => ({...f, secret: e.target.value}))}
                type="password" placeholder="Leave blank to keep existing secret" autoComplete="off" />
              <p className="text-xs mt-1 opacity-60">If set, adds <code>X-Webhook-Signature: sha256=&lt;hex&gt;</code> to each request.</p>
            </div>
            <div>
              <label>Trigger Events</label>
              <div className="mt-1 flex gap-4">
                {WEBHOOK_EVENTS.map(ev => (
                  <label key={ev} className="flex items-center gap-2 cursor-pointer mb-0 text-sm">
                    <input type="checkbox" className="w-auto" checked={webhookForm.events.includes(ev)}
                      onChange={e => setWebhookForm(f => ({...f, events: e.target.checked ? [...f.events, ev] : f.events.filter(x => x !== ev)}))} />
                    <span className={`badge text-xs ${ev === 'FLOW_COMPLETED' ? 'badge-green' : 'badge-red'}`}>{ev}</span>
                  </label>
                ))}
              </div>
            </div>
            <div>
              <label>Description <span className="text-xs font-normal opacity-60">(optional)</span></label>
              <input value={webhookForm.description}
                onChange={e => setWebhookForm(f => ({...f, description: e.target.value}))}
                placeholder="Notifies Acme's ERP on file delivery" />
            </div>
            <div className="flex gap-3 justify-end">
              <button type="button" className="btn-secondary" onClick={() => { setShowWebhookModal(false); setEditingWebhook(null) }}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={saveWebhookMut.isPending}>
                {editingWebhook ? 'Save Changes' : 'Create Webhook'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
