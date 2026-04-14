import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { notificationApi } from '../api/client'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  BellAlertIcon, PlusIcon, PencilSquareIcon, TrashIcon,
  PaperAirplaneIcon, ArrowPathIcon, DocumentTextIcon,
  MagnifyingGlassIcon, BeakerIcon, ExclamationTriangleIcon
} from '@heroicons/react/24/outline'

// ── Helpers ──────────────────────────────────────────────────────────────

const statusColor = (s) => {
  switch (s?.toUpperCase()) {
    case 'SENT':     return 'badge-green'
    case 'FAILED':   return 'badge-red'
    case 'PENDING':  return 'badge-yellow'
    case 'RETRYING': return 'badge-orange'
    default:         return 'badge-gray'
  }
}

const channelColor = (c) => {
  switch (c?.toUpperCase()) {
    case 'EMAIL':     return 'badge-blue'
    case 'WEBHOOK':   return 'badge-orange'
    case 'SMS':       return 'badge-green'
    case 'SLACK':     return 'badge-purple'
    case 'PAGERDUTY': return 'badge-red'
    default:          return 'badge-gray'
  }
}

const CHANNELS = ['EMAIL', 'WEBHOOK', 'SMS']

const EVENT_TYPES = [
  'transfer.completed', 'transfer.failed', 'transfer.started',
  'flow.completed', 'flow.failed',
  'security.threat.*', 'security.anomaly.*',
  'certificate.expiring', 'certificate.expired',
  'compliance.violation',
  'screening.hit', 'quarantine.created',
  'system.*'
]

const TEMPLATE_VARIABLES = [
  '${trackId}', '${filename}', '${status}', '${username}',
  '${serverName}', '${timestamp}', '${severity}', '${eventType}'
]

const defaultRuleForm = {
  name: '', eventTypePattern: '', channel: 'EMAIL',
  recipients: '', enabled: true, conditions: ''
}

const defaultTemplateForm = {
  name: '', channel: 'EMAIL', subjectTemplate: '',
  bodyTemplate: '', eventType: '', active: true
}

// ── Main Component ──────────────────────────────────────────────────────

export default function Notifications() {
  const qc = useQueryClient()
  const [tab, setTab] = useState('rules')

  // ── Rule state ──
  const [showRuleModal, setShowRuleModal] = useState(false)
  const [editingRule, setEditingRule] = useState(null)
  const [ruleForm, setRuleForm] = useState({ ...defaultRuleForm })

  // ── Template state ──
  const [showTemplateModal, setShowTemplateModal] = useState(false)
  const [editingTemplate, setEditingTemplate] = useState(null)
  const [templateForm, setTemplateForm] = useState({ ...defaultTemplateForm })

  // ── Log state ──
  const [trackIdFilter, setTrackIdFilter] = useState('')
  const [searchTrackId, setSearchTrackId] = useState('')

  // ── Confirm delete state ──
  const [confirmDeleteRule, setConfirmDeleteRule] = useState(null)
  const [confirmDeleteTemplate, setConfirmDeleteTemplate] = useState(null)

  // ── Test state ──
  const [testForm, setTestForm] = useState({ channel: 'EMAIL', recipient: '', subject: '', body: '' })
  const [testResult, setTestResult] = useState(null)
  const [sending, setSending] = useState(false)

  // ═══════════════════════════════════════════════════════════════════════
  // Queries
  // ═══════════════════════════════════════════════════════════════════════

  const { data: rules = [], isLoading: loadingRules, isError: rulesError, refetch: refetchRules } = useQuery({
    queryKey: ['notification-rules'],
    queryFn: () => notificationApi.get('/api/notifications/rules').then(r => r.data),
    retry: 1
  })

  const { data: templates = [], isLoading: loadingTemplates, isError: templatesError, refetch: refetchTemplates } = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () => notificationApi.get('/api/notifications/templates').then(r => r.data),
    retry: 1
  })

  const { data: recentLogs = [], isLoading: loadingLogs, isError: logsError, refetch: refetchLogs } = useQuery({
    queryKey: ['notification-logs', searchTrackId],
    queryFn: () => {
      if (searchTrackId.trim()) {
        return notificationApi.get(`/api/notifications/logs/by-track-id/${searchTrackId}`).then(r => r.data)
      }
      return notificationApi.get('/api/notifications/logs/recent').then(r => r.data)
    },
    meta: { silent: true }, refetchInterval: searchTrackId ? false : 15000,
    retry: 1
  })

  const isError = rulesError || templatesError || logsError
  const refetchAll = () => { refetchRules(); refetchTemplates(); refetchLogs() }

  // ═══════════════════════════════════════════════════════════════════════
  // Mutations — Rules
  // ═══════════════════════════════════════════════════════════════════════

  const createRule = useMutation({
    mutationFn: (data) => notificationApi.post('/api/notifications/rules', data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['notification-rules'])
      setShowRuleModal(false)
      setRuleForm({ ...defaultRuleForm })
      toast.success('Notification rule created')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create rule')
  })

  const updateRule = useMutation({
    mutationFn: ({ id, data }) => notificationApi.put(`/api/notifications/rules/${id}`, data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['notification-rules'])
      setShowRuleModal(false)
      setEditingRule(null)
      setRuleForm({ ...defaultRuleForm })
      toast.success('Notification rule updated')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update rule')
  })

  const deleteRule = useMutation({
    mutationFn: (id) => notificationApi.delete(`/api/notifications/rules/${id}`),
    onSuccess: () => {
      qc.invalidateQueries(['notification-rules'])
      toast.success('Notification rule deleted')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to delete rule')
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Mutations — Templates
  // ═══════════════════════════════════════════════════════════════════════

  const createTemplate = useMutation({
    mutationFn: (data) => notificationApi.post('/api/notifications/templates', data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['notification-templates'])
      setShowTemplateModal(false)
      setTemplateForm({ ...defaultTemplateForm })
      toast.success('Notification template created')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create template')
  })

  const updateTemplate = useMutation({
    mutationFn: ({ id, data }) => notificationApi.put(`/api/notifications/templates/${id}`, data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['notification-templates'])
      setShowTemplateModal(false)
      setEditingTemplate(null)
      setTemplateForm({ ...defaultTemplateForm })
      toast.success('Notification template updated')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update template')
  })

  const deleteTemplate = useMutation({
    mutationFn: (id) => notificationApi.delete(`/api/notifications/templates/${id}`),
    onSuccess: () => {
      qc.invalidateQueries(['notification-templates'])
      toast.success('Notification template deleted')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to delete template')
  })

  // ── Rule Handlers ──

  const openCreateRule = () => {
    setEditingRule(null)
    setRuleForm({ ...defaultRuleForm })
    setShowRuleModal(true)
  }

  const openEditRule = (r) => {
    setEditingRule(r)
    setRuleForm({
      name: r.name || '',
      eventTypePattern: r.eventTypePattern || '',
      channel: r.channel || 'EMAIL',
      recipients: Array.isArray(r.recipients) ? r.recipients.join(', ') : (r.recipients || ''),
      enabled: r.enabled !== false,
      conditions: r.conditions ? JSON.stringify(r.conditions, null, 2) : ''
    })
    setShowRuleModal(true)
  }

  const handleSaveRule = () => {
    let conditions = null
    if (ruleForm.conditions.trim()) {
      try {
        conditions = JSON.parse(ruleForm.conditions)
      } catch {
        toast.error('Conditions must be valid JSON')
        return
      }
    }
    const payload = {
      name: ruleForm.name,
      eventTypePattern: ruleForm.eventTypePattern,
      channel: ruleForm.channel,
      recipients: ruleForm.recipients.split(',').map(s => s.trim()).filter(Boolean),
      enabled: ruleForm.enabled,
      conditions
    }
    if (editingRule) {
      updateRule.mutate({ id: editingRule.id, data: payload })
    } else {
      createRule.mutate(payload)
    }
  }

  // ── Template Handlers ──

  const openCreateTemplate = () => {
    setEditingTemplate(null)
    setTemplateForm({ ...defaultTemplateForm })
    setShowTemplateModal(true)
  }

  const openEditTemplate = (t) => {
    setEditingTemplate(t)
    setTemplateForm({
      name: t.name || '',
      channel: t.channel || 'EMAIL',
      subjectTemplate: t.subjectTemplate || '',
      bodyTemplate: t.bodyTemplate || '',
      eventType: t.eventType || '',
      active: t.active !== false
    })
    setShowTemplateModal(true)
  }

  const handleSaveTemplate = () => {
    const payload = { ...templateForm }
    if (editingTemplate) {
      updateTemplate.mutate({ id: editingTemplate.id, data: payload })
    } else {
      createTemplate.mutate(payload)
    }
  }

  // ── Test Handler ──

  const handleSendTest = async () => {
    setSending(true)
    setTestResult(null)
    try {
      const res = await notificationApi.post('/api/notifications/test', {
        channel: testForm.channel,
        recipient: testForm.recipient,
        subject: testForm.subject || undefined,
        body: testForm.body || undefined
      })
      setTestResult(res.data)
      toast.success('Test notification sent')
    } catch (err) {
      setTestResult({ status: 'FAILED', errorMessage: err.response?.data?.message || 'Failed to send' })
      toast.error('Test notification failed')
    } finally {
      setSending(false)
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Render: Rules Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderRules = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <p className="text-sm text-secondary">
          Rules define when to send notifications based on system events. Each rule matches an event pattern and dispatches to a channel.
        </p>
        <button onClick={openCreateRule} className="btn btn-primary flex items-center gap-1.5">
          <PlusIcon className="w-4 h-4" /> New Rule
        </button>
      </div>

      {loadingRules ? <LoadingSpinner /> : rules.length === 0 ? (
        <EmptyState
          title="No notification rules yet"
          description="Rules decide when the platform sends alerts (email / webhook / SMS) based on file events."
          action={
            <button onClick={openCreateRule} className="btn btn-primary flex items-center gap-1.5">
              <PlusIcon className="w-4 h-4" /> Create Rule
            </button>
          }
        />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Name</th>
                <th className="table-header">Trigger Event</th>
                <th className="table-header">Channel</th>
                <th className="table-header">Recipients</th>
                <th className="table-header">Conditions</th>
                <th className="table-header">Active</th>
                <th className="table-header w-24">Actions</th>
              </tr></thead>
              <tbody>
                {rules.map(r => (
                  <tr key={r.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => openEditRule(r)}>
                    <td className="table-cell">
                      <div className="font-medium text-primary">{r.name}</div>
                    </td>
                    <td className="table-cell">
                      <span className="font-mono text-xs text-indigo-700 bg-indigo-50 px-1.5 py-0.5 rounded">{r.eventTypePattern}</span>
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${channelColor(r.channel)}`}>{r.channel}</span>
                    </td>
                    <td className="table-cell text-xs max-w-[200px]">
                      <div className="truncate" title={Array.isArray(r.recipients) ? r.recipients.join(', ') : r.recipients}>
                        {Array.isArray(r.recipients) ? r.recipients.join(', ') : (r.recipients || '-')}
                      </div>
                    </td>
                    <td className="table-cell text-xs">
                      {r.conditions && Object.keys(r.conditions).length > 0
                        ? <span className="badge badge-gray">{Object.keys(r.conditions).length} condition(s)</span>
                        : <span className="text-muted">None</span>}
                    </td>
                    <td className="table-cell">
                      {r.enabled
                        ? <span className="badge badge-green">Enabled</span>
                        : <span className="badge badge-gray">Disabled</span>}
                    </td>
                    <td className="table-cell">
                      <div className="flex gap-1">
                        <button onClick={(e) => { e.stopPropagation(); openEditRule(r) }} className="p-1 rounded hover:bg-hover" title="Edit" aria-label="Edit">
                          <PencilSquareIcon className="w-4 h-4 text-secondary" />
                        </button>
                        <button onClick={(e) => { e.stopPropagation(); setConfirmDeleteRule(r) }}
                          className="p-1 rounded hover:bg-hover" title="Delete" aria-label="Delete">
                          <TrashIcon className="w-4 h-4 text-red-500" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Rule Create/Edit Modal */}
      {showRuleModal && (
        <Modal title={editingRule ? 'Edit Notification Rule' : 'Create Notification Rule'} onClose={() => { setShowRuleModal(false); setEditingRule(null) }} size="lg">
          <div className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Name *</label>
              <input className="input w-full" value={ruleForm.name}
                onChange={e => setRuleForm(f => ({ ...f, name: e.target.value }))}
                placeholder="Transfer Failure Alert" />
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Trigger Event Pattern *</label>
              {(() => {
                const isKnown = ruleForm.eventTypePattern === '' || ruleForm.eventTypePattern === '*' || EVENT_TYPES.includes(ruleForm.eventTypePattern)
                const selectValue = isKnown ? ruleForm.eventTypePattern : '__custom__'
                return (
                  <>
                    <select className="input w-full" value={selectValue}
                      onChange={e => {
                        const v = e.target.value
                        if (v === '__custom__') {
                          setRuleForm(f => ({ ...f, eventTypePattern: f.eventTypePattern && !EVENT_TYPES.includes(f.eventTypePattern) && f.eventTypePattern !== '*' ? f.eventTypePattern : '' }))
                        } else {
                          setRuleForm(f => ({ ...f, eventTypePattern: v }))
                        }
                      }}>
                      <option value="">Select event type...</option>
                      <option value="*">All Events (*)</option>
                      {EVENT_TYPES.map(e => <option key={e} value={e}>{e}</option>)}
                      <option value="__custom__">Other (custom pattern)...</option>
                    </select>
                    {selectValue === '__custom__' && (
                      <input className="input w-full mt-1" value={ruleForm.eventTypePattern}
                        onChange={e => setRuleForm(f => ({ ...f, eventTypePattern: e.target.value }))}
                        placeholder="e.g. transfer.failed or security.threat.*"
                        autoFocus />
                    )}
                    <p className="text-xs text-muted mt-1">
                      {selectValue === '__custom__'
                        ? 'Enter a custom wildcard pattern (e.g. transfer.*, security.threat.*)'
                        : 'Choose "Other" to enter a custom wildcard pattern'}
                    </p>
                  </>
                )
              })()}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Channel *</label>
                <select className="input w-full" value={ruleForm.channel}
                  onChange={e => setRuleForm(f => ({ ...f, channel: e.target.value }))}>
                  {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
              </div>
              <div className="flex items-end">
                <label className="flex items-center gap-2 cursor-pointer select-none">
                  <div className={`w-9 h-5 rounded-full relative transition-colors ${ruleForm.enabled ? 'bg-indigo-600' : 'bg-gray-300'}`}
                    onClick={() => setRuleForm(f => ({ ...f, enabled: !f.enabled }))}>
                    <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-surface shadow transition-transform ${ruleForm.enabled ? 'translate-x-4' : 'translate-x-0.5'}`} />
                  </div>
                  <span className="text-sm text-primary">Enabled</span>
                </label>
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Recipients * (comma-separated)</label>
              <input className="input w-full" value={ruleForm.recipients}
                onChange={e => setRuleForm(f => ({ ...f, recipients: e.target.value }))}
                placeholder="admin@company.com, ops@company.com" />
              <p className="text-xs text-muted mt-1">Email addresses for EMAIL, webhook URLs for WEBHOOK, phone numbers for SMS</p>
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Conditions (optional JSON)</label>
              <textarea className="input w-full font-mono text-xs" rows={3} value={ruleForm.conditions}
                onChange={e => setRuleForm(f => ({ ...f, conditions: e.target.value }))}
                placeholder='{"severity": "CRITICAL"}' />
              <p className="text-xs text-muted mt-1">Advanced: filter events by key-value conditions</p>
            </div>
          </div>

          <div className="flex justify-end gap-2 mt-4 pt-3 border-t">
            <button onClick={() => { setShowRuleModal(false); setEditingRule(null) }} className="btn btn-secondary">Cancel</button>
            <button onClick={handleSaveRule}
              disabled={!ruleForm.name.trim() || !ruleForm.eventTypePattern.trim() || !ruleForm.recipients.trim()}
              className="btn btn-primary">
              {editingRule ? 'Update' : 'Create'} Rule
            </button>
          </div>
        </Modal>
      )}
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: Templates Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderTemplates = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <p className="text-sm text-secondary">
          Templates define the notification message format. Use ${'${variable}'} syntax for dynamic values.
        </p>
        <button onClick={openCreateTemplate} className="btn btn-primary flex items-center gap-1.5">
          <PlusIcon className="w-4 h-4" /> New Template
        </button>
      </div>

      {loadingTemplates ? <LoadingSpinner /> : templates.length === 0 ? (
        <EmptyState title="No notification templates" description="Create templates to format notification messages for different events and channels." />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Name</th>
                <th className="table-header">Event Type</th>
                <th className="table-header">Channel</th>
                <th className="table-header">Subject</th>
                <th className="table-header">Active</th>
                <th className="table-header w-24">Actions</th>
              </tr></thead>
              <tbody>
                {templates.map(t => (
                  <tr key={t.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => openEditTemplate(t)}>
                    <td className="table-cell">
                      <div className="font-medium text-primary">{t.name}</div>
                    </td>
                    <td className="table-cell">
                      <span className="font-mono text-xs text-indigo-700 bg-indigo-50 px-1.5 py-0.5 rounded">{t.eventType}</span>
                    </td>
                    <td className="table-cell"><span className={`badge ${channelColor(t.channel)}`}>{t.channel}</span></td>
                    <td className="table-cell text-sm max-w-[250px] truncate" title={t.subjectTemplate}>
                      {t.subjectTemplate || '-'}
                    </td>
                    <td className="table-cell">
                      {t.active
                        ? <span className="badge badge-green">Active</span>
                        : <span className="badge badge-gray">Disabled</span>}
                    </td>
                    <td className="table-cell">
                      <div className="flex gap-1">
                        <button onClick={(e) => { e.stopPropagation(); openEditTemplate(t) }} className="p-1 rounded hover:bg-hover" title="Edit" aria-label="Edit">
                          <PencilSquareIcon className="w-4 h-4 text-secondary" />
                        </button>
                        <button onClick={(e) => { e.stopPropagation(); setConfirmDeleteTemplate(t) }}
                          className="p-1 rounded hover:bg-hover" title="Delete" aria-label="Delete">
                          <TrashIcon className="w-4 h-4 text-red-500" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Template Create/Edit Modal */}
      {showTemplateModal && (
        <Modal title={editingTemplate ? 'Edit Notification Template' : 'Create Notification Template'} onClose={() => { setShowTemplateModal(false); setEditingTemplate(null) }} size="lg">
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Name *</label>
                <input className="input w-full" value={templateForm.name}
                  onChange={e => setTemplateForm(f => ({ ...f, name: e.target.value }))}
                  placeholder="transfer-completed-email" />
              </div>
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Channel *</label>
                <select className="input w-full" value={templateForm.channel}
                  onChange={e => setTemplateForm(f => ({ ...f, channel: e.target.value }))}>
                  {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Event Type *</label>
                {(() => {
                  const isKnown = templateForm.eventType === '' || EVENT_TYPES.includes(templateForm.eventType)
                  const selectValue = isKnown ? templateForm.eventType : '__custom__'
                  return (
                    <>
                      <select className="input w-full" value={selectValue}
                        onChange={e => {
                          const v = e.target.value
                          if (v === '__custom__') {
                            setTemplateForm(f => ({ ...f, eventType: f.eventType && !EVENT_TYPES.includes(f.eventType) ? f.eventType : '' }))
                          } else {
                            setTemplateForm(f => ({ ...f, eventType: v }))
                          }
                        }}>
                        <option value="">Select event type...</option>
                        {EVENT_TYPES.map(e => <option key={e} value={e}>{e}</option>)}
                        <option value="__custom__">Other (custom type)...</option>
                      </select>
                      {selectValue === '__custom__' && (
                        <input className="input w-full mt-1" value={templateForm.eventType}
                          onChange={e => setTemplateForm(f => ({ ...f, eventType: e.target.value }))}
                          placeholder="e.g. custom.event.type"
                          autoFocus />
                      )}
                    </>
                  )
                })()}
              </div>
              <div className="flex items-end">
                <label className="flex items-center gap-2 cursor-pointer select-none">
                  <div className={`w-9 h-5 rounded-full relative transition-colors ${templateForm.active ? 'bg-indigo-600' : 'bg-gray-300'}`}
                    onClick={() => setTemplateForm(f => ({ ...f, active: !f.active }))}>
                    <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-surface shadow transition-transform ${templateForm.active ? 'translate-x-4' : 'translate-x-0.5'}`} />
                  </div>
                  <span className="text-sm text-primary">Active</span>
                </label>
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Subject Line</label>
              <input className="input w-full" value={templateForm.subjectTemplate}
                onChange={e => setTemplateForm(f => ({ ...f, subjectTemplate: e.target.value }))}
                placeholder="Transfer ${trackId} ${status}" />
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Body *</label>
              <textarea className="input w-full font-mono text-xs" rows={6} value={templateForm.bodyTemplate}
                onChange={e => setTemplateForm(f => ({ ...f, bodyTemplate: e.target.value }))}
                placeholder="Transfer ${trackId} for file ${filename} has ${status} at ${timestamp}." />
            </div>
            <div className="flex flex-wrap gap-1.5">
              <span className="text-xs text-secondary">Available variables:</span>
              {TEMPLATE_VARIABLES.map(v => (
                <button key={v} onClick={() => setTemplateForm(f => ({ ...f, bodyTemplate: f.bodyTemplate + v }))}
                  className="text-xs font-mono bg-indigo-50 text-indigo-700 px-1.5 py-0.5 rounded hover:bg-indigo-100 cursor-pointer">
                  {v}
                </button>
              ))}
            </div>

            {/* Preview pane */}
            {templateForm.bodyTemplate && (
              <div className="bg-canvas border rounded-lg p-3">
                <p className="text-xs font-medium text-secondary mb-1">Preview (with sample data)</p>
                {templateForm.subjectTemplate && (
                  <p className="text-sm font-semibold text-gray-800 mb-1">
                    {templateForm.subjectTemplate
                      .replace(/\$\{trackId\}/g, 'TRK-20260409-001')
                      .replace(/\$\{filename\}/g, 'invoice_q1.csv')
                      .replace(/\$\{status\}/g, 'COMPLETED')
                      .replace(/\$\{username\}/g, 'admin')
                      .replace(/\$\{serverName\}/g, 'sftp-prod-1')
                      .replace(/\$\{timestamp\}/g, '2026-04-09 10:30:00')
                      .replace(/\$\{severity\}/g, 'HIGH')
                      .replace(/\$\{eventType\}/g, 'transfer.completed')}
                  </p>
                )}
                <p className="text-sm text-primary whitespace-pre-wrap">
                  {templateForm.bodyTemplate
                    .replace(/\$\{trackId\}/g, 'TRK-20260409-001')
                    .replace(/\$\{filename\}/g, 'invoice_q1.csv')
                    .replace(/\$\{status\}/g, 'COMPLETED')
                    .replace(/\$\{username\}/g, 'admin')
                    .replace(/\$\{serverName\}/g, 'sftp-prod-1')
                    .replace(/\$\{timestamp\}/g, '2026-04-09 10:30:00')
                    .replace(/\$\{severity\}/g, 'HIGH')
                    .replace(/\$\{eventType\}/g, 'transfer.completed')}
                </p>
              </div>
            )}
          </div>

          <div className="flex justify-end gap-2 mt-4 pt-3 border-t">
            <button onClick={() => { setShowTemplateModal(false); setEditingTemplate(null) }} className="btn btn-secondary">Cancel</button>
            <button onClick={handleSaveTemplate}
              disabled={!templateForm.name.trim() || !templateForm.eventType.trim() || !templateForm.bodyTemplate.trim()}
              className="btn btn-primary">
              {editingTemplate ? 'Update' : 'Create'} Template
            </button>
          </div>
        </Modal>
      )}
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: Delivery Log Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderLogs = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center flex-wrap gap-3">
        <p className="text-sm text-secondary">
          {searchTrackId
            ? `Showing logs for track ID: ${searchTrackId}`
            : 'Recent notification delivery attempts. Auto-refreshes every 15 seconds.'}
        </p>
        <div className="flex gap-2 items-center">
          <div className="relative">
            <MagnifyingGlassIcon className="w-4 h-4 text-muted absolute left-2.5 top-2.5" />
            <input className="input pl-8 w-56" value={trackIdFilter}
              onChange={e => setTrackIdFilter(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') setSearchTrackId(trackIdFilter.trim()) }}
              placeholder="Filter by Track ID..." />
          </div>
          {searchTrackId && (
            <button onClick={() => { setSearchTrackId(''); setTrackIdFilter('') }}
              className="text-xs text-indigo-600 hover:underline">Clear filter</button>
          )}
        </div>
      </div>

      {loadingLogs ? <LoadingSpinner /> : recentLogs.length === 0 ? (
        <EmptyState title="No delivery logs" description={searchTrackId ? 'No notifications found for this track ID.' : 'Notification delivery logs will appear here as notifications are sent.'} />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Time</th>
                <th className="table-header">Event</th>
                <th className="table-header">Channel</th>
                <th className="table-header">Recipient</th>
                <th className="table-header">Subject</th>
                <th className="table-header">Status</th>
                <th className="table-header">Track ID</th>
                <th className="table-header">Retries</th>
              </tr></thead>
              <tbody>
                {recentLogs.map(l => (
                  <tr key={l.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]">
                    <td className="table-cell text-xs text-secondary whitespace-nowrap">
                      {l.sentAt ? format(new Date(l.sentAt), 'MMM dd HH:mm:ss') : ''}
                    </td>
                    <td className="table-cell">
                      <span className="font-mono text-xs text-indigo-700 bg-indigo-50 px-1.5 py-0.5 rounded">{l.eventType}</span>
                    </td>
                    <td className="table-cell"><span className={`badge ${channelColor(l.channel)}`}>{l.channel}</span></td>
                    <td className="table-cell text-xs max-w-[180px] truncate" title={l.recipient}>{l.recipient}</td>
                    <td className="table-cell text-sm max-w-[200px] truncate" title={l.subject}>{l.subject || '-'}</td>
                    <td className="table-cell">
                      <span className={`badge ${statusColor(l.status)}`}>{l.status}</span>
                      {l.errorMessage && (
                        <div className="text-xs text-red-500 mt-0.5 max-w-[200px] truncate" title={l.errorMessage}>
                          {l.errorMessage}
                        </div>
                      )}
                    </td>
                    <td className="table-cell font-mono text-xs">{l.trackId || '-'}</td>
                    <td className="table-cell text-xs text-secondary">{l.retryCount || 0}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: Test Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderTest = () => (
    <div className="space-y-4">
      <p className="text-sm text-secondary">
        Send a test notification to verify your channel configuration is working correctly.
      </p>

      <div className="card max-w-xl">
        <h3 className="font-semibold text-primary mb-4 flex items-center gap-2">
          <BeakerIcon className="w-5 h-5 text-indigo-600" />
          Send Test Notification
        </h3>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">Channel *</label>
            <select className="input w-full" value={testForm.channel}
              onChange={e => setTestForm(f => ({ ...f, channel: e.target.value }))}>
              {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">Recipient *</label>
            <input className="input w-full" value={testForm.recipient}
              onChange={e => setTestForm(f => ({ ...f, recipient: e.target.value }))}
              placeholder={testForm.channel === 'EMAIL' ? 'admin@company.com' : testForm.channel === 'WEBHOOK' ? 'https://hooks.example.com/...' : '+1234567890'} />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">Subject (optional)</label>
            <input className="input w-full" value={testForm.subject}
              onChange={e => setTestForm(f => ({ ...f, subject: e.target.value }))}
              placeholder="Test notification" />
          </div>
          <div>
            <label className="block text-xs font-medium text-secondary mb-1">Body (optional)</label>
            <textarea className="input w-full" rows={3} value={testForm.body}
              onChange={e => setTestForm(f => ({ ...f, body: e.target.value }))}
              placeholder="This is a test notification from TranzFer MFT." />
          </div>
          <div className="flex justify-end">
            <button onClick={handleSendTest}
              disabled={sending || !testForm.recipient.trim()}
              className="btn btn-primary flex items-center gap-1.5">
              {sending
                ? <ArrowPathIcon className="w-4 h-4 animate-spin" />
                : <PaperAirplaneIcon className="w-4 h-4" />}
              {sending ? 'Sending...' : 'Send Test'}
            </button>
          </div>
        </div>

        {testResult && (
          <div className={`mt-4 p-3 rounded-lg border ${testResult.status === 'SENT' ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
            <p className={`text-sm font-medium ${testResult.status === 'SENT' ? 'text-green-800' : 'text-red-800'}`}>
              {testResult.status === 'SENT' ? 'Test notification sent successfully' : 'Test notification failed'}
            </p>
            {testResult.errorMessage && (
              <p className="text-sm text-red-700 mt-1">{testResult.errorMessage}</p>
            )}
            {testResult.id && (
              <p className="text-xs text-secondary mt-1">Log ID: {testResult.id}</p>
            )}
          </div>
        )}
      </div>
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Main Render
  // ═══════════════════════════════════════════════════════════════════════

  return (
    <div className="space-y-6">
      {isError && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ExclamationTriangleIcon className="w-5 h-5 text-red-400" />
            <span className="text-sm text-red-400">Failed to load data — service may be unavailable</span>
          </div>
          <button onClick={() => refetchAll()} className="text-xs text-red-400 hover:text-red-300 underline">Retry</button>
        </div>
      )}
      <div>
        <h1 className="text-2xl font-bold text-primary flex items-center gap-2">
          <BellAlertIcon className="w-7 h-7 text-indigo-600" />
          Notification Management
        </h1>
        <p className="text-secondary text-sm">Configure notification rules, templates, and delivery channels for system events</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b">
        <button onClick={() => setTab('rules')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'rules' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          Rules
        </button>
        <button onClick={() => setTab('templates')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'templates' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          Templates
        </button>
        <button onClick={() => setTab('logs')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'logs' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          Delivery Log
        </button>
        <button onClick={() => setTab('test')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'test' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          Test
        </button>
      </div>

      {/* Tab Content */}
      {tab === 'rules' && renderRules()}
      {tab === 'templates' && renderTemplates()}
      {tab === 'logs' && renderLogs()}
      {tab === 'test' && renderTest()}

      <ConfirmDialog
        open={!!confirmDeleteRule}
        variant="danger"
        title="Delete notification rule?"
        message={confirmDeleteRule ? `Are you sure you want to delete notification rule "${confirmDeleteRule.name}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteRule.isPending}
        onConfirm={() => { deleteRule.mutate(confirmDeleteRule.id); setConfirmDeleteRule(null) }}
        onCancel={() => setConfirmDeleteRule(null)}
      />

      <ConfirmDialog
        open={!!confirmDeleteTemplate}
        variant="danger"
        title="Delete notification template?"
        message={confirmDeleteTemplate ? `Are you sure you want to delete notification template "${confirmDeleteTemplate.name}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteTemplate.isPending}
        onConfirm={() => { deleteTemplate.mutate(confirmDeleteTemplate.id); setConfirmDeleteTemplate(null) }}
        onCancel={() => setConfirmDeleteTemplate(null)}
      />
    </div>
  )
}
