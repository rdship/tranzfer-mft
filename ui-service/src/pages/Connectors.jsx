import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, BoltIcon } from '@heroicons/react/24/outline'

const CONN_TYPES = ['SERVICENOW', 'PAGERDUTY', 'SLACK', 'TEAMS', 'OPSGENIE', 'WEBHOOK']
const EVENTS = ['TRANSFER_FAILED', 'AI_BLOCKED', 'INTEGRITY_FAIL', 'FLOW_FAIL', 'QUARANTINE', 'ANOMALY_DETECTED', 'SLA_BREACH', 'LICENSE_EXPIRED']

export default function Connectors() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', type: 'SLACK', url: '', authToken: '', triggerEvents: ['TRANSFER_FAILED'], minSeverity: 'HIGH' })

  const { data: connectors = [], isLoading } = useQuery({ queryKey: ['connectors'], queryFn: () => configApi.get('/api/connectors').then(r => r.data).catch(() => []) })
  const createMut = useMutation({ mutationFn: (d) => configApi.post('/api/connectors', d),
    onSuccess: () => { qc.invalidateQueries(['connectors']); setShowCreate(false); toast.success('Connector created') } })
  const testMut = useMutation({ mutationFn: (id) => configApi.post(`/api/connectors/${id}/test`).then(r => r.data),
    onSuccess: (d) => toast.success('Test: ' + d.status), onError: () => toast.error('Test failed') })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">External Connectors</h1>
          <p className="text-gray-500 text-sm">ServiceNow, Slack, PagerDuty, Teams integrations</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> Add Connector</button>
      </div>
      <div className="space-y-3">
        {connectors.map(c => (
          <div key={c.id} className="card flex items-center gap-4">
            <BoltIcon className="w-6 h-6 text-blue-500" />
            <div className="flex-1">
              <h3 className="font-semibold text-gray-900">{c.name}</h3>
              <p className="text-xs text-gray-500">{c.type} — {c.url?.substring(0, 40)}...</p>
              <div className="flex gap-1 mt-1">{(c.triggerEvents || []).map(e => <span key={e} className="badge badge-gray text-xs">{e}</span>)}</div>
            </div>
            <div className="text-right text-xs text-gray-500">
              <p>{c.totalNotifications || 0} sent</p>
              {c.lastTriggered && <p>Last: {new Date(c.lastTriggered).toLocaleString()}</p>}
            </div>
            <button onClick={() => testMut.mutate(c.id)} className="btn-secondary text-xs">Test</button>
          </div>
        ))}
      </div>
      {showCreate && (
        <Modal title="Add Connector" size="lg" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="slack-alerts" /></div>
              <div><label>Type</label><select value={form.type} onChange={e => setForm(f => ({...f, type: e.target.value}))}>{CONN_TYPES.map(t => <option key={t}>{t}</option>)}</select></div>
            </div>
            <div><label>URL</label><input value={form.url} onChange={e => setForm(f => ({...f, url: e.target.value}))} required placeholder="https://hooks.slack.com/services/..." /></div>
            <div><label>Trigger Events</label>
              <div className="mt-1 flex flex-wrap gap-1">{EVENTS.map(ev => (
                <label key={ev} className="flex items-center gap-1 cursor-pointer mb-0">
                  <input type="checkbox" className="w-auto" checked={form.triggerEvents.includes(ev)} onChange={e => setForm(f => ({...f, triggerEvents: e.target.checked ? [...f.triggerEvents, ev] : f.triggerEvents.filter(x => x !== ev)}))} />
                  <span className="text-xs">{ev}</span>
                </label>
              ))}</div></div>
            <div><label>Min Severity</label><select value={form.minSeverity} onChange={e => setForm(f => ({...f, minSeverity: e.target.value}))}><option>LOW</option><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option></select></div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary">Create</button></div>
          </form>
        </Modal>
      )}
    </div>
  )
}
