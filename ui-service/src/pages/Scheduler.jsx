import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { PlusIcon, PencilSquareIcon, TrashIcon, MagnifyingGlassIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

const TASK_TYPES = ['RUN_FLOW', 'PULL_FILES', 'PUSH_FILES', 'EXECUTE_SCRIPT', 'CLEANUP']

const CRON_PRESETS = [
  { label: 'Every minute', cron: '0 * * * * *' },
  { label: 'Every 15 minutes', cron: '0 */15 * * * *' },
  { label: 'Hourly', cron: '0 0 * * * *' },
  { label: 'Daily at 2 AM', cron: '0 0 2 * * *' },
  { label: 'Daily at midnight', cron: '0 0 0 * * *' },
  { label: 'Weekdays at 6 AM', cron: '0 0 6 * * MON-FRI' },
  { label: 'Weekly (Sunday midnight)', cron: '0 0 0 * * SUN' },
  { label: 'Monthly (1st at midnight)', cron: '0 0 0 1 * *' },
]

const EMPTY_FORM = { name: '', cronExpression: '0 0 2 * * *', taskType: 'RUN_FLOW', flowId: '', active: true, config: {} }

export default function Scheduler() {
  const qc = useQueryClient()
  const [showModal, setShowModal] = useState(false)
  const [editingTask, setEditingTask] = useState(null)
  const [form, setForm] = useState({ ...EMPTY_FORM })
  const [deleteConfirm, setDeleteConfirm] = useState(null)
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState('name')
  const [sortDir, setSortDir] = useState('asc')

  const { data: tasks = [], isLoading, isError, refetch } = useQuery({ queryKey: ['scheduler'], queryFn: () => configApi.get('/api/scheduler/all').then(r => r.data), retry: 1 })
  const { data: flows = [] } = useQuery({ queryKey: ['flows-for-scheduler'], queryFn: () => configApi.get('/api/flows').then(r => r.data).catch(() => []), staleTime: 60000 })

  const createMut = useMutation({
    mutationFn: (d) => configApi.post('/api/scheduler', d).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['scheduler']); closeModal(); toast.success('Schedule created') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create schedule')
  })
  const updateMut = useMutation({
    mutationFn: ({ id, data }) => configApi.put(`/api/scheduler/${id}`, data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['scheduler']); closeModal(); toast.success('Schedule updated') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update schedule')
  })
  const deleteMut = useMutation({
    mutationFn: (id) => configApi.delete(`/api/scheduler/${id}`),
    onSuccess: () => { qc.invalidateQueries(['scheduler']); setDeleteConfirm(null); toast.success('Schedule deleted') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to delete schedule')
  })
  const toggleMut = useMutation({ mutationFn: (id) => configApi.patch(`/api/scheduler/${id}/toggle`),
    onSuccess: () => { qc.invalidateQueries(['scheduler']); toast.success('Toggled') },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to toggle schedule — please try again') })

  const openCreate = () => { setEditingTask(null); setForm({ ...EMPTY_FORM }); setShowModal(true) }
  const openEdit = (t) => {
    setEditingTask(t)
    setForm({
      name: t.name || '', cronExpression: t.cronExpression || '0 0 2 * * *',
      taskType: t.taskType || 'RUN_FLOW', flowId: t.flowId || t.config?.flowId || '',
      active: t.enabled !== false, config: t.config || {}
    })
    setShowModal(true)
  }
  const closeModal = () => { setShowModal(false); setEditingTask(null); setForm({ ...EMPTY_FORM }) }

  const handleSave = () => {
    const payload = {
      name: form.name, cronExpression: form.cronExpression,
      taskType: form.taskType, enabled: form.active,
      config: { ...form.config, ...(form.flowId ? { flowId: form.flowId } : {}) },
      flowId: form.flowId || null
    }
    if (editingTask) {
      updateMut.mutate({ id: editingTask.id, data: payload })
    } else {
      createMut.mutate(payload)
    }
  }

  const toggleSort = (col) => {
    if (sortBy === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortBy(col); setSortDir('asc') }
  }

  const sortedTasks = useMemo(() => {
    const list = (tasks || []).filter(t => {
      if (!search) return true
      const q = search.toLowerCase()
      const flowName = flows.find(f => f.id === t.flowId || f.id === t.config?.flowId)?.name || ''
      return t.name?.toLowerCase().includes(q) || flowName.toLowerCase().includes(q) || t.cronExpression?.toLowerCase().includes(q)
    })
    const arr = [...list]
    arr.sort((a, b) => {
      let va, vb
      if (sortBy === 'nextRun') {
        va = a.lastRun ? new Date(a.lastRun).getTime() : 0
        vb = b.lastRun ? new Date(b.lastRun).getTime() : 0
      } else if (sortBy === 'active') {
        va = a.enabled ? 1 : 0; vb = b.enabled ? 1 : 0
      } else {
        va = a[sortBy] ?? ''; vb = b[sortBy] ?? ''
      }
      if (typeof va === 'number') return sortDir === 'asc' ? va - vb : vb - va
      return sortDir === 'asc' ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va))
    })
    return arr
  }, [tasks, search, flows, sortBy, sortDir])

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
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
        <div><h1 className="text-2xl font-bold text-primary">Scheduler</h1>
          <p className="text-secondary text-sm">Cron-based task scheduling — {tasks.length} tasks</p></div>
        <div className="flex items-center gap-3">
          <div className="relative">
            <MagnifyingGlassIcon className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
            <input value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search tasks..." className="pl-9 w-64" />
          </div>
          <button className="btn-primary" onClick={openCreate}><PlusIcon className="w-4 h-4" /> Create Task</button>
        </div>
      </div>
      <div className="card">
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('name')} aria-sort={sortBy === 'name' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Name {sortBy === 'name' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th><th className="table-header">Cron</th><th className="table-header">Type</th>
          <th className="table-header cursor-pointer select-none" onClick={() => toggleSort('nextRun')} aria-sort={sortBy === 'nextRun' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Last Run {sortBy === 'nextRun' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th><th className="table-header">Status</th><th className="table-header">Runs</th><th className="table-header cursor-pointer select-none" onClick={() => toggleSort('active')} aria-sort={sortBy === 'active' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>Actions {sortBy === 'active' && (sortDir === 'asc' ? '\u2191' : '\u2193')}</th>
        </tr></thead><tbody>
          {tasks.length === 0 ? (
            <tr><td colSpan={7} className="text-center py-8 text-secondary text-sm">No scheduled tasks yet. Create your first schedule to automate recurring jobs.</td></tr>
          ) : sortedTasks.map(t => (
            <tr key={t.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => openEdit(t)}>
              <td className="table-cell font-medium">{t.name}</td>
              <td className="table-cell font-mono text-xs">{t.cronExpression}</td>
              <td className="table-cell"><span className="badge badge-blue">{t.taskType}</span></td>
              <td className="table-cell text-xs text-secondary">{t.lastRun ? new Date(t.lastRun).toLocaleString() : 'Never'}</td>
              <td className="table-cell"><span className={`badge ${t.lastStatus === 'SUCCESS' ? 'badge-green' : t.lastStatus === 'FAILED' ? 'badge-red' : 'badge-gray'}`}>{t.lastStatus || 'PENDING'}</span></td>
              <td className="table-cell text-xs">{t.totalRuns} ({t.failedRuns} failed)</td>
              <td className="table-cell">
                <div className="flex items-center gap-1">
                  <button onClick={(e) => { e.stopPropagation(); openEdit(t) }} className="p-1 rounded hover:bg-hover" title="Edit task" aria-label="Edit task">
                    <PencilSquareIcon className="w-4 h-4 text-secondary" />
                  </button>
                  <button onClick={(e) => { e.stopPropagation(); toggleMut.mutate(t.id) }} className={`text-xs px-2 py-0.5 rounded ${t.enabled ? 'text-red-600 hover:bg-red-50' : 'text-green-600 hover:bg-green-50'}`}>
                    {t.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button onClick={(e) => { e.stopPropagation(); setDeleteConfirm(t) }} className="p-1 rounded hover:bg-hover" title="Delete task" aria-label="Delete task">
                    <TrashIcon className="w-4 h-4 text-red-500" />
                  </button>
                </div>
              </td>
            </tr>))}
        </tbody></table>
      </div>

      {/* Create/Edit Task Modal */}
      {showModal && (
        <Modal title={editingTask ? 'Edit Scheduled Task' : 'Create Scheduled Task'} onClose={closeModal}>
          <form onSubmit={e => { e.preventDefault(); handleSave() }} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-primary mb-1">Task Name *</label>
              <input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="daily-partner-flow" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Cron Expression</label>
                <input value={form.cronExpression} onChange={e => setForm(f => ({...f, cronExpression: e.target.value}))} className="font-mono" placeholder="0 0 2 * * *" />
                <div className="mt-2">
                  <label className="block text-xs text-secondary mb-1">Quick presets:</label>
                  <div className="flex flex-wrap gap-1">
                    {CRON_PRESETS.map(p => (
                      <button key={p.cron} type="button"
                        onClick={() => setForm(f => ({...f, cronExpression: p.cron}))}
                        className={`text-xs px-2 py-0.5 rounded border transition-colors ${form.cronExpression === p.cron ? 'bg-blue-100 border-blue-300 text-blue-700' : 'border-border text-secondary hover:bg-hover'}`}>
                        {p.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Task Type</label>
                <select value={form.taskType} onChange={e => setForm(f => ({...f, taskType: e.target.value}))}>
                  {TASK_TYPES.map(t => <option key={t}>{t}</option>)}
                </select>
              </div>
            </div>

            {/* Flow selection (shown when task type is RUN_FLOW) */}
            {form.taskType === 'RUN_FLOW' && (
              <div>
                <label className="block text-sm font-medium text-primary mb-1">Flow to Execute</label>
                <select value={form.flowId} onChange={e => setForm(f => ({...f, flowId: e.target.value}))}>
                  <option value="">-- Select a flow --</option>
                  {flows.filter(f => f.active !== false).map(f => (
                    <option key={f.id} value={f.id}>{f.name}</option>
                  ))}
                </select>
                {flows.length === 0 && (
                  <p className="text-xs text-amber-500 mt-1">No flows available. Create a flow first.</p>
                )}
              </div>
            )}

            {/* Active toggle */}
            <div className="flex items-center gap-2">
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <div className={`w-9 h-5 rounded-full relative transition-colors ${form.active ? 'bg-indigo-600' : 'bg-gray-300'}`}
                  onClick={() => setForm(f => ({...f, active: !f.active}))}>
                  <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${form.active ? 'translate-x-4' : 'translate-x-0.5'}`} />
                </div>
                <span className="text-sm text-primary">Active</span>
              </label>
            </div>

            <div className="flex gap-3 justify-end pt-3 border-t">
              <button type="button" className="btn-secondary" onClick={closeModal}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={!form.name.trim() || createMut.isPending || updateMut.isPending}>
                {(createMut.isPending || updateMut.isPending) ? 'Saving...' : (editingTask ? 'Update Task' : 'Create Task')}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Delete confirmation */}
      {deleteConfirm && (
        <Modal title="Delete Scheduled Task" onClose={() => setDeleteConfirm(null)}>
          <div className="space-y-4">
            <p className="text-sm text-secondary">
              Are you sure you want to delete the task <strong>"{deleteConfirm.name}"</strong>? This action cannot be undone.
            </p>
            <div className="flex gap-3 justify-end">
              <button className="btn-secondary" onClick={() => setDeleteConfirm(null)}>Cancel</button>
              <button className="btn-danger" onClick={() => deleteMut.mutate(deleteConfirm.id)} disabled={deleteMut.isPending}>
                {deleteMut.isPending ? 'Deleting...' : 'Delete Task'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
