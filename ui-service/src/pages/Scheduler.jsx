import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, PlayIcon } from '@heroicons/react/24/outline'

const TASK_TYPES = ['RUN_FLOW', 'PULL_FILES', 'PUSH_FILES', 'EXECUTE_SCRIPT', 'CLEANUP']

export default function Scheduler() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({ name: '', cronExpression: '0 0 2 * * *', taskType: 'RUN_FLOW', config: {} })

  const { data: tasks = [], isLoading } = useQuery({ queryKey: ['scheduler'], queryFn: () => configApi.get('/api/scheduler/all').then(r => r.data).catch(() => []) })
  const createMut = useMutation({ mutationFn: (d) => configApi.post('/api/scheduler', d).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['scheduler']); setShowCreate(false); toast.success('Schedule created') } })
  const toggleMut = useMutation({ mutationFn: (id) => configApi.patch(`/api/scheduler/${id}/toggle`),
    onSuccess: () => { qc.invalidateQueries(['scheduler']); toast.success('Toggled') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Scheduler</h1>
          <p className="text-gray-500 text-sm">Cron-based task scheduling — {tasks.length} tasks</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> New Schedule</button>
      </div>
      <div className="card">
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Name</th><th className="table-header">Cron</th><th className="table-header">Type</th>
          <th className="table-header">Last Run</th><th className="table-header">Status</th><th className="table-header">Runs</th><th className="table-header">Actions</th>
        </tr></thead><tbody>
          {tasks.length === 0 ? (
            <tr><td colSpan={7} className="text-center py-8 text-gray-500 text-sm">No scheduled tasks yet. Create your first schedule to automate recurring jobs.</td></tr>
          ) : tasks.map(t => (
            <tr key={t.id} className="table-row">
              <td className="table-cell font-medium">{t.name}</td>
              <td className="table-cell font-mono text-xs">{t.cronExpression}</td>
              <td className="table-cell"><span className="badge badge-blue">{t.taskType}</span></td>
              <td className="table-cell text-xs text-gray-500">{t.lastRun ? new Date(t.lastRun).toLocaleString() : 'Never'}</td>
              <td className="table-cell"><span className={`badge ${t.lastStatus === 'SUCCESS' ? 'badge-green' : t.lastStatus === 'FAILED' ? 'badge-red' : 'badge-gray'}`}>{t.lastStatus || 'PENDING'}</span></td>
              <td className="table-cell text-xs">{t.totalRuns} ({t.failedRuns} failed)</td>
              <td className="table-cell"><button onClick={() => toggleMut.mutate(t.id)} className={`text-xs ${t.enabled ? 'text-red-600' : 'text-green-600'}`}>{t.enabled ? 'Disable' : 'Enable'}</button></td>
            </tr>))}
        </tbody></table>
      </div>
      {showCreate && (
        <Modal title="New Schedule" onClose={() => setShowCreate(false)}>
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="daily-partner-flow" /></div>
            <div className="grid grid-cols-2 gap-3">
              <div><label>Cron Expression</label><input value={form.cronExpression} onChange={e => setForm(f => ({...f, cronExpression: e.target.value}))} className="font-mono" placeholder="0 0 2 * * *" /></div>
              <div><label>Task Type</label><select value={form.taskType} onChange={e => setForm(f => ({...f, taskType: e.target.value}))}>{TASK_TYPES.map(t => <option key={t}>{t}</option>)}</select></div>
            </div>
            <div className="flex gap-3 justify-end"><button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary">Create</button></div>
          </form>
        </Modal>
      )}
    </div>
  )
}
