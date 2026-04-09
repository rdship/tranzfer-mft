import { useState, useEffect, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getPlatformSettings, createPlatformSetting, updatePlatformSetting,
  updatePlatformSettingValue, deletePlatformSetting, cloneEnvironment,
  getSnapshotRetention, updateSnapshotRetention, purgeSnapshotsNow,
} from '../api/platformSettings'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import {
  PlusIcon, TrashIcon, PencilIcon, DocumentDuplicateIcon,
  EyeIcon, EyeSlashIcon, CheckIcon, XMarkIcon,
  ArchiveBoxXMarkIcon, ClockIcon as ClockSolid,
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

function AiLlmSection({ activeEnv }) {
  const qc = useQueryClient()
  const [llmEnabled, setLlmEnabled] = useState(false)
  const [llmApiKey, setLlmApiKey] = useState('')
  const [llmModel, setLlmModel] = useState('claude-sonnet-4-20250514')
  const [customModel, setCustomModel] = useState('')
  const [llmBaseUrl, setLlmBaseUrl] = useState('https://api.anthropic.com')
  const [saving, setSaving] = useState(false)

  const { data: aiSettings = [] } = useQuery({
    queryKey: ['platform-settings', 'AI', activeEnv],
    queryFn: () => getPlatformSettings({ category: 'AI', env: activeEnv })
  })

  useEffect(() => {
    if (!aiSettings.length) return
    const findVal = (key) => {
      const s = aiSettings.find(s => s.settingKey === key)
      return s ? s.settingValue : null
    }
    const enabled = findVal('ai.llm.enabled')
    if (enabled !== null) setLlmEnabled(enabled === 'true')
    const key = findVal('ai.llm.api-key')
    if (key !== null) setLlmApiKey(key)
    const model = findVal('ai.llm.model')
    if (model !== null) {
      const knownModels = ['claude-opus-4-6','claude-sonnet-4-6','claude-sonnet-4-20250514','claude-haiku-4-5-20251001','gpt-4.1','gpt-4.1-mini','gpt-4.1-nano','o4-mini','o3','gemini-2.5-pro','gemini-2.5-flash','gemini-2.0-flash','llama-4-maverick','llama-4-scout','mistral-large-latest','mistral-medium-latest','codestral-latest','deepseek-r1','deepseek-v3','command-r-plus','command-r']
      if (knownModels.includes(model)) { setLlmModel(model) }
      else { setLlmModel('__custom__'); setCustomModel(model) }
    }
    const baseUrl = findVal('ai.llm.base-url')
    if (baseUrl !== null) setLlmBaseUrl(baseUrl)
  }, [aiSettings])

  const findSetting = (key) => aiSettings.find(s => s.settingKey === key)

  const saveSetting = async (key, value) => {
    const setting = findSetting(key)
    if (setting) {
      await updatePlatformSettingValue(setting.id, String(value))
    }
  }

  const toggleLlm = async () => {
    const next = !llmEnabled
    setLlmEnabled(next)
    try {
      await saveSetting('ai.llm.enabled', next)
      qc.invalidateQueries({ queryKey: ['platform-settings'] })
      toast.success(next ? 'LLM enabled' : 'LLM disabled')
    } catch (e) {
      setLlmEnabled(!next)
      toast.error('Failed to update setting')
    }
  }

  const saveAiSettings = async () => {
    if (llmBaseUrl && !llmBaseUrl.startsWith('https://')) {
      toast.error('API endpoint must use HTTPS — API keys must not be sent over unencrypted connections')
      return
    }
    setSaving(true)
    try {
      await saveSetting('ai.llm.api-key', llmApiKey)
      await saveSetting('ai.llm.model', llmModel === '__custom__' ? customModel : llmModel)
      await saveSetting('ai.llm.base-url', llmBaseUrl)
      qc.invalidateQueries({ queryKey: ['platform-settings'] })
      toast.success('AI settings saved')
    } catch (e) {
      toast.error('Failed to save settings')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="card">
      <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
        <span className="badge text-xs bg-purple-100 text-purple-800">AI</span>
        AI / LLM Configuration
      </h3>

      {/* LLM Toggle */}
      <div className="flex items-center justify-between p-4 bg-gray-50 rounded-lg mb-3">
        <div>
          <h4 className="font-medium text-gray-900 text-sm">Enable External LLM</h4>
          <p className="text-xs text-gray-500">Connect an LLM for enhanced AI security verdicts and recommendations</p>
        </div>
        <button onClick={toggleLlm} className={`relative w-12 h-6 rounded-full transition-colors ${llmEnabled ? 'bg-blue-600' : 'bg-gray-300'}`}>
          <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full transition-transform ${llmEnabled ? 'translate-x-6' : ''}`} />
        </button>
      </div>

      {llmEnabled && (
        <div className="space-y-3 p-4 border border-blue-100 rounded-lg bg-white">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">API Key</label>
            <input type="password" value={llmApiKey} onChange={e => setLlmApiKey(e.target.value)}
              placeholder="sk-ant-..." className="w-full" />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Model</label>
            <select value={llmModel} onChange={e => { setLlmModel(e.target.value); if (e.target.value !== '__custom__') setCustomModel('') }} className="w-full">
              <optgroup label="Anthropic">
                <option value="claude-opus-4-6">Claude Opus 4.6 (Most capable)</option>
                <option value="claude-sonnet-4-6">Claude Sonnet 4.6</option>
                <option value="claude-sonnet-4-20250514">Claude Sonnet 4</option>
                <option value="claude-haiku-4-5-20251001">Claude Haiku 4.5 (Fastest)</option>
              </optgroup>
              <optgroup label="OpenAI">
                <option value="gpt-4.1">GPT-4.1</option>
                <option value="gpt-4.1-mini">GPT-4.1 Mini</option>
                <option value="gpt-4.1-nano">GPT-4.1 Nano</option>
                <option value="o4-mini">o4-mini (Reasoning)</option>
                <option value="o3">o3 (Reasoning)</option>
              </optgroup>
              <optgroup label="Google">
                <option value="gemini-2.5-pro">Gemini 2.5 Pro</option>
                <option value="gemini-2.5-flash">Gemini 2.5 Flash</option>
                <option value="gemini-2.0-flash">Gemini 2.0 Flash</option>
              </optgroup>
              <optgroup label="Meta">
                <option value="llama-4-maverick">Llama 4 Maverick</option>
                <option value="llama-4-scout">Llama 4 Scout</option>
              </optgroup>
              <optgroup label="Mistral">
                <option value="mistral-large-latest">Mistral Large</option>
                <option value="mistral-medium-latest">Mistral Medium</option>
                <option value="codestral-latest">Codestral</option>
              </optgroup>
              <optgroup label="DeepSeek">
                <option value="deepseek-r1">DeepSeek R1 (Reasoning)</option>
                <option value="deepseek-v3">DeepSeek V3</option>
              </optgroup>
              <optgroup label="Cohere">
                <option value="command-r-plus">Command R+</option>
                <option value="command-r">Command R</option>
              </optgroup>
              <optgroup label="Custom">
                <option value="__custom__">Enter custom model ID...</option>
              </optgroup>
            </select>
            {llmModel === '__custom__' && (
              <input value={customModel} onChange={e => setCustomModel(e.target.value)}
                placeholder="e.g., my-org/fine-tuned-model-v2" className="w-full mt-2" />
            )}
            <p className="text-xs text-gray-500 mt-1">Select a model or enter a custom model ID for self-hosted / fine-tuned models.</p>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">API Endpoint</label>
            <input value={llmBaseUrl} onChange={e => setLlmBaseUrl(e.target.value)}
              placeholder="https://api.anthropic.com" className="w-full" />
            <p className="text-xs text-gray-500 mt-1">HTTPS only — API keys must not be sent over unencrypted connections.</p>
          </div>
          <div className="flex gap-3 pt-1">
            <button className="btn-primary" onClick={saveAiSettings} disabled={saving}>
              {saving ? 'Saving...' : 'Save AI Settings'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Snapshot Retention Card ─────────────────────────────────────────────────

function SnapshotRetentionCard() {
  const qc = useQueryClient()
  const [editDays, setEditDays] = useState(null) // null = view mode, number = edit mode

  const { data, isLoading, isError } = useQuery({
    queryKey: ['snapshot-retention'],
    queryFn: getSnapshotRetention,
    refetchInterval: 30000,
    retry: false,
  })

  const updateMut = useMutation({
    mutationFn: (days) => updateSnapshotRetention(days),
    onSuccess: (r) => {
      qc.invalidateQueries(['snapshot-retention'])
      setEditDays(null)
      toast.success(r.message || 'Retention policy updated')
    },
    onError: (e) => toast.error(e.response?.data?.message || 'Update failed'),
  })

  const purgeMut = useMutation({
    mutationFn: purgeSnapshotsNow,
    onSuccess: (r) => {
      qc.invalidateQueries(['snapshot-retention'])
      toast.success(r.message || `Purge complete`)
    },
    onError: (e) => toast.error(e.response?.data?.message || 'Purge failed'),
  })

  const days       = data?.retentionDays ?? 90
  const enabled    = data?.enabled ?? true
  const total      = data?.totalSnapshots ?? 0
  const eligible   = data?.eligibleForPurge ?? 0
  const lastAt     = data?.lastPurgeAt
  const lastCount  = data?.lastPurgeCount ?? -1

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <ArchiveBoxXMarkIcon className="w-5 h-5 text-amber-500" />
          <h3 className="font-semibold text-gray-900">Snapshot Retention Policy</h3>
          <span className={`badge ${enabled ? 'badge-yellow' : 'badge-gray'}`}>
            {enabled ? `${days}d` : 'Disabled'}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => purgeMut.mutate()}
            disabled={purgeMut.isPending || !enabled || isLoading}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-50 transition-colors"
          >
            <ArchiveBoxXMarkIcon className={`w-3.5 h-3.5 ${purgeMut.isPending ? 'animate-spin' : ''}`} />
            {purgeMut.isPending ? 'Purging…' : 'Purge Now'}
          </button>
        </div>
      </div>

      {isError && (
        <p className="text-xs text-amber-600 mb-3">Retention endpoint unavailable — is onboarding-api running?</p>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-4">
        {[
          { label: 'Total snapshots', value: isLoading ? '—' : total.toLocaleString(), color: 'text-gray-900' },
          { label: 'Eligible for purge', value: isLoading ? '—' : eligible.toLocaleString(), color: eligible > 0 ? 'text-amber-600' : 'text-gray-500' },
          { label: 'Last purge', value: lastAt ? new Date(lastAt).toLocaleString() : '—', color: 'text-gray-600' },
          { label: 'Last count', value: lastCount >= 0 ? lastCount.toLocaleString() : 'Never', color: 'text-gray-600' },
        ].map(({ label, value, color }) => (
          <div key={label}>
            <p className="text-xs text-gray-400 uppercase tracking-wider">{label}</p>
            <p className={`text-sm font-semibold mt-0.5 ${color}`}>{value}</p>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-3 pt-3 border-t border-gray-100">
        <ClockSolid className="w-4 h-4 text-gray-400 flex-shrink-0" />
        {editDays === null ? (
          <>
            <p className="text-sm text-gray-600 flex-1">
              {enabled
                ? `Snapshots older than ${days} days are purged at 02:00 UTC daily.`
                : 'Retention is disabled — snapshots accumulate indefinitely.'}
            </p>
            <button
              onClick={() => setEditDays(days)}
              className="text-xs text-blue-600 hover:underline flex-shrink-0"
            >
              Change
            </button>
          </>
        ) : (
          <>
            <label className="text-sm text-gray-600 flex-shrink-0">Retain for</label>
            <input
              type="number"
              value={editDays}
              onChange={e => setEditDays(Number(e.target.value))}
              min={0} max={3650}
              className="w-24 text-sm"
              placeholder="90"
            />
            <label className="text-sm text-gray-500 flex-shrink-0">days (0 = disabled)</label>
            <div className="flex gap-2 ml-auto">
              <button
                onClick={() => updateMut.mutate(editDays)}
                disabled={updateMut.isPending}
                className="px-3 py-1 text-xs font-medium rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {updateMut.isPending ? 'Saving…' : 'Save'}
              </button>
              <button
                onClick={() => setEditDays(null)}
                className="px-3 py-1 text-xs font-medium rounded-lg border border-gray-200 text-gray-600 hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </>
        )}
      </div>
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

      {/* AI / LLM Configuration */}
      <AiLlmSection activeEnv={activeEnv} />

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
      {/* Snapshot Retention */}
      <SnapshotRetentionCard />

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
