import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getPlatformSettings, createPlatformSetting, updatePlatformSetting,
  updatePlatformSettingValue, deletePlatformSetting, cloneEnvironment
} from '../api/platformSettings'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilIcon, DocumentDuplicateIcon,
  EyeIcon, EyeSlashIcon, CheckIcon, XMarkIcon
} from '@heroicons/react/24/outline'

const ENVIRONMENTS = ['DEV', 'TEST', 'CERT', 'STAGING', 'PROD']
const DATA_TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'JSON']
const SERVICE_NAMES = [
  'GLOBAL', 'SFTP', 'FTP', 'FTP_WEB', 'GATEWAY', 'ONBOARDING', 'CONFIG',
  'ANALYTICS', 'SCREENING', 'LICENSE', 'STORAGE', 'ENCRYPTION', 'DMZ',
  'AI_ENGINE', 'KEYSTORE', 'SCHEDULER', 'FORWARDER', 'NOTIFICATION'
]
const CATEGORIES = [
  'Security', 'Network', 'Storage', 'Platform', 'Messaging',
  'Analytics', 'Compliance', 'License', 'AI', 'EDI'
]

const ENV_COLORS = {
  DEV: 'bg-gray-100 text-gray-700',
  TEST: 'bg-yellow-100 text-yellow-800',
  CERT: 'bg-orange-100 text-orange-800',
  STAGING: 'bg-purple-100 text-purple-800',
  PROD: 'bg-red-100 text-red-800'
}

const SERVICE_COLORS = {
  GLOBAL: 'bg-blue-100 text-blue-800',
  SFTP: 'bg-green-100 text-green-800',
  FTP: 'bg-teal-100 text-teal-800',
  FTP_WEB: 'bg-cyan-100 text-cyan-800',
  GATEWAY: 'bg-purple-100 text-purple-800',
  ANALYTICS: 'bg-indigo-100 text-indigo-800',
  SCREENING: 'bg-red-100 text-red-800',
  ENCRYPTION: 'bg-amber-100 text-amber-800',
  STORAGE: 'bg-lime-100 text-lime-800'
}

function SettingForm({ initial, onSubmit, onCancel, isPending }) {
  const [form, setForm] = useState(initial || {
    settingKey: '', settingValue: '', environment: 'PROD', serviceName: 'GLOBAL',
    dataType: 'STRING', description: '', category: 'Platform', sensitive: false
  })
  return (
    <form onSubmit={e => { e.preventDefault(); onSubmit(form) }} className="space-y-4">
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label>Setting Key</label>
          <input value={form.settingKey} onChange={e => setForm(f => ({ ...f, settingKey: e.target.value }))}
            required placeholder="e.g. sftp.port" disabled={!!initial} />
        </div>
        <div>
          <label>Value</label>
          <input value={form.settingValue} onChange={e => setForm(f => ({ ...f, settingValue: e.target.value }))}
            placeholder="e.g. 2222" type={form.sensitive ? 'password' : 'text'} />
        </div>
      </div>
      <div className="grid grid-cols-3 gap-4">
        <div>
          <label>Environment</label>
          <select value={form.environment} onChange={e => setForm(f => ({ ...f, environment: e.target.value }))} disabled={!!initial}>
            {ENVIRONMENTS.map(e => <option key={e}>{e}</option>)}
          </select>
        </div>
        <div>
          <label>Service</label>
          <select value={form.serviceName} onChange={e => setForm(f => ({ ...f, serviceName: e.target.value }))} disabled={!!initial}>
            {SERVICE_NAMES.map(s => <option key={s}>{s}</option>)}
          </select>
        </div>
        <div>
          <label>Data Type</label>
          <select value={form.dataType} onChange={e => setForm(f => ({ ...f, dataType: e.target.value }))}>
            {DATA_TYPES.map(d => <option key={d}>{d}</option>)}
          </select>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label>Category</label>
          <select value={form.category || ''} onChange={e => setForm(f => ({ ...f, category: e.target.value }))}>
            <option value="">Uncategorized</option>
            {CATEGORIES.map(c => <option key={c}>{c}</option>)}
          </select>
        </div>
        <div className="flex items-center gap-2 pt-6">
          <input type="checkbox" id="sensitive" checked={form.sensitive}
            onChange={e => setForm(f => ({ ...f, sensitive: e.target.checked }))} className="w-4 h-4" />
          <label htmlFor="sensitive" className="text-sm text-gray-600 cursor-pointer">Sensitive (mask in UI)</label>
        </div>
      </div>
      <div>
        <label>Description</label>
        <input value={form.description || ''} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
          placeholder="Human-readable description" />
      </div>
      <div className="flex gap-3 justify-end pt-2">
        <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn-primary" disabled={isPending}>
          {isPending ? 'Saving...' : initial ? 'Update Setting' : 'Create Setting'}
        </button>
      </div>
    </form>
  )
}

function InlineValueEditor({ setting, onSave }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(setting.settingValue || '')
  const [showSensitive, setShowSensitive] = useState(false)
  const updateMut = useMutation({ mutationFn: () => onSave(setting.id, value),
    onSuccess: () => { setEditing(false); toast.success('Updated') } })

  if (!editing) {
    return (
      <div className="flex items-center gap-1 group">
        {setting.sensitive ? (
          <div className="flex items-center gap-1">
            <span className="font-mono text-xs">{showSensitive ? setting.settingValue : '••••••••'}</span>
            <button onClick={() => setShowSensitive(!showSensitive)}
              className="p-0.5 opacity-0 group-hover:opacity-100 text-gray-400 hover:text-gray-600">
              {showSensitive ? <EyeSlashIcon className="w-3.5 h-3.5" /> : <EyeIcon className="w-3.5 h-3.5" />}
            </button>
          </div>
        ) : (
          <span className="font-mono text-xs">{setting.settingValue || <span className="text-gray-300">empty</span>}</span>
        )}
        <button onClick={() => setEditing(true)}
          className="p-0.5 opacity-0 group-hover:opacity-100 text-gray-400 hover:text-blue-600">
          <PencilIcon className="w-3.5 h-3.5" />
        </button>
      </div>
    )
  }

  return (
    <div className="flex items-center gap-1">
      <input value={value} onChange={e => setValue(e.target.value)}
        className="text-xs py-0.5 px-1.5 w-40" autoFocus
        type={setting.sensitive ? 'password' : 'text'}
        onKeyDown={e => { if (e.key === 'Enter') updateMut.mutate(); if (e.key === 'Escape') setEditing(false) }} />
      <button onClick={() => updateMut.mutate()} className="p-0.5 text-green-600 hover:text-green-800">
        <CheckIcon className="w-4 h-4" />
      </button>
      <button onClick={() => setEditing(false)} className="p-0.5 text-gray-400 hover:text-gray-600">
        <XMarkIcon className="w-4 h-4" />
      </button>
    </div>
  )
}

export default function PlatformConfig() {
  const qc = useQueryClient()
  const [activeEnv, setActiveEnv] = useState('PROD')
  const [filterService, setFilterService] = useState('')
  const [filterCategory, setFilterCategory] = useState('')
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [editSetting, setEditSetting] = useState(null)
  const [showClone, setShowClone] = useState(false)
  const [cloneTarget, setCloneTarget] = useState('CERT')

  const params = { env: activeEnv }
  if (filterService) params.service = filterService
  if (filterCategory) params.category = filterCategory

  const { data: settings = [], isLoading } = useQuery({
    queryKey: ['platform-settings', activeEnv, filterService, filterCategory],
    queryFn: () => getPlatformSettings(params)
  })

  const createMut = useMutation({ mutationFn: createPlatformSetting,
    onSuccess: () => { qc.invalidateQueries(['platform-settings']); setShowCreate(false); toast.success('Setting created') },
    onError: err => toast.error(err.response?.data?.message || 'Failed') })

  const updateMut = useMutation({ mutationFn: ({ id, data }) => updatePlatformSetting(id, data),
    onSuccess: () => { qc.invalidateQueries(['platform-settings']); setEditSetting(null); toast.success('Setting updated') } })

  const deleteMut = useMutation({ mutationFn: deletePlatformSetting,
    onSuccess: () => { qc.invalidateQueries(['platform-settings']); toast.success('Setting deleted') } })

  const cloneMut = useMutation({ mutationFn: () => cloneEnvironment(activeEnv, cloneTarget),
    onSuccess: (data) => { qc.invalidateQueries(['platform-settings']); setShowClone(false); toast.success(`Cloned ${data.length} settings to ${cloneTarget}`) } })

  const handleInlineUpdate = async (id, value) => {
    await updatePlatformSettingValue(id, value)
    qc.invalidateQueries(['platform-settings'])
  }

  // Filter and group by service
  const filtered = settings.filter(s =>
    (!search || s.settingKey?.toLowerCase().includes(search.toLowerCase()) ||
     s.description?.toLowerCase().includes(search.toLowerCase())))

  const grouped = useMemo(() => {
    const groups = {}
    filtered.forEach(s => {
      const svc = s.serviceName || 'GLOBAL'
      if (!groups[svc]) groups[svc] = []
      groups[svc].push(s)
    })
    // Sort: GLOBAL first, then alphabetical
    const sorted = {}
    if (groups['GLOBAL']) sorted['GLOBAL'] = groups['GLOBAL']
    Object.keys(groups).filter(k => k !== 'GLOBAL').sort().forEach(k => { sorted[k] = groups[k] })
    return sorted
  }, [filtered])

  const totalSettings = settings.length
  const sensitiveCount = settings.filter(s => s.sensitive).length
  const serviceCount = new Set(settings.map(s => s.serviceName)).size

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Platform Configuration</h1>
          <p className="text-gray-500 text-sm">Database-backed settings for all microservices — survives crashes</p>
        </div>
        <div className="flex gap-2">
          <button className="btn-secondary" onClick={() => setShowClone(true)}>
            <DocumentDuplicateIcon className="w-4 h-4" /> Clone Env
          </button>
          <button className="btn-primary" onClick={() => setShowCreate(true)}>
            <PlusIcon className="w-4 h-4" /> New Setting
          </button>
        </div>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-4 gap-4">
        <div className="card p-4">
          <p className="text-2xl font-bold text-gray-900">{totalSettings}</p>
          <p className="text-xs text-gray-500">Settings in {activeEnv}</p>
        </div>
        <div className="card p-4">
          <p className="text-2xl font-bold text-gray-900">{serviceCount}</p>
          <p className="text-xs text-gray-500">Services Configured</p>
        </div>
        <div className="card p-4">
          <p className="text-2xl font-bold text-amber-600">{sensitiveCount}</p>
          <p className="text-xs text-gray-500">Sensitive Values</p>
        </div>
        <div className="card p-4">
          <p className="text-2xl font-bold text-green-600">{ENVIRONMENTS.length}</p>
          <p className="text-xs text-gray-500">Environments</p>
        </div>
      </div>

      {/* Environment tabs */}
      <div className="card">
        <div className="flex items-center gap-2 mb-4 border-b border-gray-100 pb-3">
          {ENVIRONMENTS.map(env => (
            <button key={env} onClick={() => setActiveEnv(env)}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                activeEnv === env
                  ? ENV_COLORS[env] + ' ring-2 ring-offset-1 ring-current'
                  : 'text-gray-500 hover:bg-gray-100'
              }`}>
              {env}
            </button>
          ))}
        </div>

        {/* Filters */}
        <div className="flex gap-3 mb-4">
          <input placeholder="Search settings..." value={search}
            onChange={e => setSearch(e.target.value)} className="flex-1 max-w-sm" />
          <select value={filterService} onChange={e => setFilterService(e.target.value)}>
            <option value="">All Services</option>
            {SERVICE_NAMES.map(s => <option key={s}>{s}</option>)}
          </select>
          <select value={filterCategory} onChange={e => setFilterCategory(e.target.value)}>
            <option value="">All Categories</option>
            {CATEGORIES.map(c => <option key={c}>{c}</option>)}
          </select>
        </div>

        {/* Grouped settings */}
        {Object.keys(grouped).length === 0 ? (
          <EmptyState title="No settings found" description={`No settings configured for ${activeEnv} environment.`}
            action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />Add Setting</button>} />
        ) : (
          Object.entries(grouped).map(([svc, items]) => (
            <div key={svc} className="mb-6">
              <h3 className="text-sm font-semibold text-gray-700 mb-2 flex items-center gap-2">
                <span className={`badge text-xs ${SERVICE_COLORS[svc] || 'bg-gray-100 text-gray-700'}`}>{svc}</span>
                <span className="text-gray-400 text-xs">{items.length} settings</span>
              </h3>
              <table className="w-full">
                <thead>
                  <tr className="border-b border-gray-100">
                    <th className="table-header">Key</th>
                    <th className="table-header">Value</th>
                    <th className="table-header">Type</th>
                    <th className="table-header">Category</th>
                    <th className="table-header">Description</th>
                    <th className="table-header w-20">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map(s => (
                    <tr key={s.id} className="table-row">
                      <td className="table-cell font-mono text-xs font-medium text-gray-900">{s.settingKey}</td>
                      <td className="table-cell">
                        <InlineValueEditor setting={s} onSave={handleInlineUpdate} />
                      </td>
                      <td className="table-cell"><span className="text-xs text-gray-500">{s.dataType}</span></td>
                      <td className="table-cell"><span className="text-xs text-gray-500">{s.category || '—'}</span></td>
                      <td className="table-cell text-xs text-gray-500 max-w-xs truncate" title={s.description}>{s.description || '—'}</td>
                      <td className="table-cell">
                        <div className="flex gap-1">
                          <button onClick={() => setEditSetting(s)}
                            className="p-1 rounded hover:bg-blue-50 text-blue-500 hover:text-blue-700">
                            <PencilIcon className="w-3.5 h-3.5" />
                          </button>
                          <button onClick={() => { if (confirm(`Delete ${s.settingKey}?`)) deleteMut.mutate(s.id) }}
                            className="p-1 rounded hover:bg-red-50 text-red-500 hover:text-red-700">
                            <TrashIcon className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))
        )}
      </div>

      {/* Create Modal */}
      {showCreate && (
        <Modal title="Create Platform Setting" onClose={() => setShowCreate(false)}>
          <SettingForm
            initial={{ settingKey: '', settingValue: '', environment: activeEnv, serviceName: 'GLOBAL',
              dataType: 'STRING', description: '', category: 'Platform', sensitive: false }}
            onSubmit={data => createMut.mutate(data)} onCancel={() => setShowCreate(false)}
            isPending={createMut.isPending} />
        </Modal>
      )}

      {/* Edit Modal */}
      {editSetting && (
        <Modal title={`Edit: ${editSetting.settingKey}`} onClose={() => setEditSetting(null)}>
          <SettingForm
            initial={editSetting}
            onSubmit={data => updateMut.mutate({ id: editSetting.id, data })}
            onCancel={() => setEditSetting(null)} isPending={updateMut.isPending} />
        </Modal>
      )}

      {/* Clone Modal */}
      {showClone && (
        <Modal title={`Clone ${activeEnv} Settings`} onClose={() => setShowClone(false)}>
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              Clone all settings from <span className="font-semibold">{activeEnv}</span> to another environment.
              Existing settings in the target will not be overwritten.
            </p>
            <div>
              <label>Target Environment</label>
              <select value={cloneTarget} onChange={e => setCloneTarget(e.target.value)}>
                {ENVIRONMENTS.filter(e => e !== activeEnv).map(e => <option key={e}>{e}</option>)}
              </select>
            </div>
            <div className="flex gap-3 justify-end pt-2">
              <button className="btn-secondary" onClick={() => setShowClone(false)}>Cancel</button>
              <button className="btn-primary" onClick={() => cloneMut.mutate()} disabled={cloneMut.isPending}>
                {cloneMut.isPending ? 'Cloning...' : `Clone to ${cloneTarget}`}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
