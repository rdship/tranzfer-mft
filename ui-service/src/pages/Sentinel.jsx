import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import * as sentinelApi from '../api/sentinel'
import {
  ShieldCheckIcon, ExclamationTriangleIcon, ChartBarIcon,
  AdjustmentsHorizontalIcon, ArrowPathIcon, CheckCircleIcon,
  LinkIcon, BoltIcon, CpuChipIcon, PlusIcon, TrashIcon, XMarkIcon
} from '@heroicons/react/24/outline'

const TABS = [
  { key: 'overview', label: 'Overview', icon: ChartBarIcon },
  { key: 'findings', label: 'Findings', icon: ExclamationTriangleIcon },
  { key: 'correlations', label: 'Correlations', icon: LinkIcon },
  { key: 'rules', label: 'Rules', icon: AdjustmentsHorizontalIcon },
]

const SEV_COLORS = {
  CRITICAL: 'bg-red-600', HIGH: 'bg-orange-500', MEDIUM: 'bg-yellow-500', LOW: 'bg-blue-500', INFO: 'bg-gray-500'
}
const SEV_TEXT = {
  CRITICAL: 'text-red-400', HIGH: 'text-orange-400', MEDIUM: 'text-yellow-400', LOW: 'text-blue-400', INFO: 'text-gray-400'
}
const STATUS_COLORS = {
  OPEN: 'bg-red-500/20 text-red-400', ACKNOWLEDGED: 'bg-yellow-500/20 text-yellow-400',
  DISMISSED: 'bg-gray-500/20 text-gray-400', RESOLVED: 'bg-green-500/20 text-green-400',
  REPORTED: 'bg-blue-500/20 text-blue-400'
}

function HealthGauge({ score }) {
  const color = score >= 80 ? 'text-green-400' : score >= 60 ? 'text-yellow-400' : score >= 40 ? 'text-orange-400' : 'text-red-400'
  const bgColor = score >= 80 ? 'bg-green-500' : score >= 60 ? 'bg-yellow-500' : score >= 40 ? 'bg-orange-500' : 'bg-red-500'
  return (
    <div className="flex flex-col items-center">
      <div className={`text-5xl font-bold ${color}`}>{score}</div>
      <div className="text-gray-400 text-sm mt-1">Platform Health</div>
      <div className="w-full bg-gray-700 rounded-full h-2 mt-2">
        <div className={`${bgColor} h-2 rounded-full transition-all`} style={{ width: `${score}%` }} />
      </div>
    </div>
  )
}

function StatCard({ label, value, color = 'text-white' }) {
  return (
    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
      <div className={`text-2xl font-bold ${color}`}>{value}</div>
      <div className="text-gray-400 text-xs mt-1">{label}</div>
    </div>
  )
}

// --- Overview Tab ---
function OverviewTab() {
  const { data: dashboard, isLoading } = useQuery({
    queryKey: ['sentinel-dashboard'], queryFn: sentinelApi.getDashboard, refetchInterval: 30000
  })
  const { data: history } = useQuery({
    queryKey: ['sentinel-health-history'], queryFn: () => sentinelApi.getHealthScoreHistory(24), refetchInterval: 60000
  })

  if (isLoading) return <div className="flex justify-center py-12"><div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" /></div>

  const hs = dashboard?.healthScore
  const bySev = dashboard?.openBySeverity || {}

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className="md:col-span-1 bg-gray-800 rounded-lg p-6 border border-gray-700">
          <HealthGauge score={hs?.overallScore ?? 100} />
        </div>
        <div className="md:col-span-4 grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Open Findings" value={dashboard?.totalOpen ?? 0} color="text-white" />
          <StatCard label="Critical" value={bySev.CRITICAL ?? 0} color="text-red-400" />
          <StatCard label="High" value={bySev.HIGH ?? 0} color="text-orange-400" />
          <StatCard label="Today" value={dashboard?.totalToday ?? 0} color="text-blue-400" />
        </div>
      </div>

      {/* Score breakdown */}
      {hs && (
        <div className="grid grid-cols-3 gap-4">
          {[
            { label: 'Infrastructure', score: hs.infrastructureScore, icon: CpuChipIcon },
            { label: 'Data', score: hs.dataScore, icon: ChartBarIcon },
            { label: 'Security', score: hs.securityScore, icon: ShieldCheckIcon },
          ].map(({ label, score, icon: Icon }) => (
            <div key={label} className="bg-gray-800 rounded-lg p-4 border border-gray-700">
              <div className="flex items-center gap-2 mb-2">
                <Icon className="w-4 h-4 text-gray-400" />
                <span className="text-gray-300 text-sm">{label}</span>
              </div>
              <div className="text-xl font-bold text-white">{score}/100</div>
              <div className="w-full bg-gray-700 rounded-full h-1.5 mt-2">
                <div className={`h-1.5 rounded-full ${score >= 80 ? 'bg-green-500' : score >= 60 ? 'bg-yellow-500' : 'bg-red-500'}`}
                     style={{ width: `${score}%` }} />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Health score history */}
      {history?.length > 0 && (
        <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
          <h3 className="text-gray-300 text-sm font-medium mb-3">Health Score (24h)</h3>
          <div className="flex items-end gap-1 h-24">
            {history.slice(-48).map((h, i) => (
              <div key={i} className="flex-1 flex flex-col items-center justify-end">
                <div className={`w-full rounded-sm ${h.overallScore >= 80 ? 'bg-green-500' : h.overallScore >= 60 ? 'bg-yellow-500' : 'bg-red-500'}`}
                     style={{ height: `${h.overallScore}%` }}
                     title={`${h.overallScore} at ${new Date(h.recordedAt).toLocaleTimeString()}`} />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Recent findings */}
      <div className="bg-gray-800 rounded-lg border border-gray-700">
        <div className="px-4 py-3 border-b border-gray-700">
          <h3 className="text-gray-300 text-sm font-medium">Recent Findings</h3>
        </div>
        <div className="divide-y divide-gray-700">
          {(dashboard?.recentFindings || []).map(f => (
            <div key={f.id} className="px-4 py-3 flex items-center gap-3">
              <span className={`w-2 h-2 rounded-full ${SEV_COLORS[f.severity]}`} />
              <span className={`text-xs font-mono px-1.5 py-0.5 rounded ${STATUS_COLORS[f.status] || ''}`}>{f.status}</span>
              <span className="text-gray-300 text-sm flex-1 truncate">{f.title}</span>
              <span className="text-gray-500 text-xs">{f.analyzer}</span>
              <span className="text-gray-500 text-xs">{new Date(f.createdAt).toLocaleString()}</span>
            </div>
          ))}
          {(!dashboard?.recentFindings?.length) && (
            <div className="px-4 py-8 text-center text-gray-500">
              <CheckCircleIcon className="w-8 h-8 mx-auto mb-2 text-green-500" />
              <div>All clear — no findings detected</div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// --- Findings Tab ---
function FindingsTab() {
  const [filters, setFilters] = useState({ status: '', severity: '', analyzer: '', page: 0 })
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['sentinel-findings', filters],
    queryFn: () => sentinelApi.getFindings({ ...filters, size: 20 }),
    refetchInterval: 15000
  })

  const dismiss = useMutation({ mutationFn: sentinelApi.dismissFinding, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sentinel-findings'] }) })
  const ack = useMutation({ mutationFn: sentinelApi.acknowledgeFinding, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sentinel-findings'] }) })

  const findings = data?.content || []
  const totalPages = data?.totalPages || 0

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex gap-3 flex-wrap">
        {[
          { key: 'status', options: ['', 'OPEN', 'ACKNOWLEDGED', 'DISMISSED', 'RESOLVED', 'REPORTED'], label: 'Status' },
          { key: 'severity', options: ['', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'], label: 'Severity' },
          { key: 'analyzer', options: ['', 'SECURITY', 'PERFORMANCE'], label: 'Analyzer' },
        ].map(({ key, options, label }) => (
          <select key={key} value={filters[key]}
                  onChange={e => setFilters(f => ({ ...f, [key]: e.target.value, page: 0 }))}
                  className="bg-gray-800 text-gray-300 text-sm border border-gray-600 rounded px-3 py-1.5">
            <option value="">{label}: All</option>
            {options.filter(Boolean).map(o => <option key={o} value={o}>{o}</option>)}
          </select>
        ))}
      </div>

      {isLoading ? (
        <div className="flex justify-center py-8"><div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" /></div>
      ) : (
        <div className="bg-gray-800 rounded-lg border border-gray-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-700 text-gray-400 text-xs">
                <th className="px-4 py-2 text-left">Sev</th>
                <th className="px-4 py-2 text-left">Status</th>
                <th className="px-4 py-2 text-left">Title</th>
                <th className="px-4 py-2 text-left">Analyzer</th>
                <th className="px-4 py-2 text-left">Service</th>
                <th className="px-4 py-2 text-left">Time</th>
                <th className="px-4 py-2 text-left">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-700">
              {findings.map(f => (
                <tr key={f.id} className="hover:bg-gray-750">
                  <td className="px-4 py-2"><span className={`inline-block w-2 h-2 rounded-full ${SEV_COLORS[f.severity]}`} title={f.severity} /></td>
                  <td className="px-4 py-2"><span className={`text-xs px-1.5 py-0.5 rounded ${STATUS_COLORS[f.status] || ''}`}>{f.status}</span></td>
                  <td className="px-4 py-2 text-gray-300 max-w-md truncate">{f.title}
                    {f.githubIssueUrl && <a href={f.githubIssueUrl} target="_blank" rel="noreferrer" className="ml-2 text-blue-400 text-xs hover:underline">GitHub</a>}
                  </td>
                  <td className="px-4 py-2 text-gray-400">{f.analyzer}</td>
                  <td className="px-4 py-2 text-gray-400">{f.affectedService || '—'}</td>
                  <td className="px-4 py-2 text-gray-500 text-xs">{new Date(f.createdAt).toLocaleString()}</td>
                  <td className="px-4 py-2 flex gap-1">
                    {f.status === 'OPEN' && (
                      <>
                        <button onClick={() => ack.mutate(f.id)} className="text-xs px-2 py-0.5 bg-yellow-600/20 text-yellow-400 rounded hover:bg-yellow-600/30">Ack</button>
                        <button onClick={() => dismiss.mutate(f.id)} className="text-xs px-2 py-0.5 bg-gray-600/20 text-gray-400 rounded hover:bg-gray-600/30">Dismiss</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
              {!findings.length && (
                <tr><td colSpan={7} className="px-4 py-8 text-center text-gray-500">No findings match filters</td></tr>
              )}
            </tbody>
          </table>

          {totalPages > 1 && (
            <div className="flex justify-center gap-2 py-3 border-t border-gray-700">
              <button disabled={filters.page === 0} onClick={() => setFilters(f => ({ ...f, page: f.page - 1 }))}
                      className="text-xs px-3 py-1 bg-gray-700 text-gray-300 rounded disabled:opacity-50">Prev</button>
              <span className="text-gray-400 text-xs py-1">Page {filters.page + 1} of {totalPages}</span>
              <button disabled={filters.page >= totalPages - 1} onClick={() => setFilters(f => ({ ...f, page: f.page + 1 }))}
                      className="text-xs px-3 py-1 bg-gray-700 text-gray-300 rounded disabled:opacity-50">Next</button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// --- Correlations Tab ---
function CorrelationsTab() {
  const { data: correlations, isLoading } = useQuery({
    queryKey: ['sentinel-correlations'], queryFn: sentinelApi.getCorrelations, refetchInterval: 30000
  })

  if (isLoading) return <div className="flex justify-center py-8"><div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" /></div>

  if (!correlations?.length) {
    return (
      <div className="bg-gray-800 rounded-lg border border-gray-700 p-8 text-center">
        <LinkIcon className="w-8 h-8 mx-auto mb-2 text-gray-500" />
        <div className="text-gray-400">No correlated events detected</div>
        <div className="text-gray-500 text-sm mt-1">Correlations appear when multiple findings occur within the same time window</div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {correlations.map((c, i) => (
        <div key={i} className="bg-gray-800 rounded-lg border border-gray-700 p-4">
          <div className="flex items-start justify-between mb-3">
            <div>
              <h3 className="text-gray-200 font-medium">{c.group?.title || 'Correlation Group'}</h3>
              {c.group?.rootCause && <p className="text-orange-400 text-sm mt-1">Root cause: {c.group.rootCause}</p>}
            </div>
            <span className="text-gray-500 text-xs">{c.group?.findingCount} findings</span>
          </div>
          <div className="space-y-2">
            {(c.findings || []).map(f => (
              <div key={f.id} className="flex items-center gap-2 text-sm pl-4 border-l-2 border-gray-600">
                <span className={`w-2 h-2 rounded-full ${SEV_COLORS[f.severity]}`} />
                <span className="text-gray-400">{f.analyzer}</span>
                <span className="text-gray-300 flex-1">{f.title}</span>
                <span className="text-gray-500 text-xs">{new Date(f.createdAt).toLocaleTimeString()}</span>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

const BLANK_RULE = { analyzer: 'SECURITY', name: '', description: '', severity: 'MEDIUM', thresholdValue: '', windowMinutes: 60, cooldownMinutes: 30 }

// --- Rules Tab ---
function RulesTab() {
  const queryClient = useQueryClient()
  const [showAdd, setShowAdd] = useState(false)
  const [form, setForm] = useState(BLANK_RULE)
  const [confirmDelete, setConfirmDelete] = useState(null) // rule id pending delete
  const [formError, setFormError] = useState('')

  const { data: rules, isLoading } = useQuery({
    queryKey: ['sentinel-rules'], queryFn: sentinelApi.getRules
  })

  const update = useMutation({
    mutationFn: ({ id, data }) => sentinelApi.updateRule(id, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sentinel-rules'] })
  })

  const create = useMutation({
    mutationFn: (data) => sentinelApi.createRule(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sentinel-rules'] })
      setShowAdd(false)
      setForm(BLANK_RULE)
      setFormError('')
    },
    onError: (err) => setFormError(err?.response?.data?.error || 'Failed to create rule')
  })

  const remove = useMutation({
    mutationFn: (id) => sentinelApi.deleteRule(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sentinel-rules'] })
      setConfirmDelete(null)
    },
    onError: (err) => {
      alert(err?.response?.data?.error || 'Failed to delete rule')
      setConfirmDelete(null)
    }
  })

  if (isLoading) return <div className="flex justify-center py-8"><div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" /></div>

  const grouped = {}
  for (const rule of (rules || [])) {
    if (!grouped[rule.analyzer]) grouped[rule.analyzer] = []
    grouped[rule.analyzer].push(rule)
  }

  return (
    <div className="space-y-6">
      {/* Header row with Add Rule button */}
      <div className="flex items-center justify-between">
        <p className="text-gray-400 text-sm">{rules?.length ?? 0} rules · {rules?.filter(r => r.enabled).length ?? 0} enabled</p>
        <button onClick={() => { setShowAdd(true); setFormError('') }}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-purple-600 hover:bg-purple-700 text-white text-sm rounded-lg transition-colors">
          <PlusIcon className="w-4 h-4" /> Add Rule
        </button>
      </div>

      {/* Add Rule modal */}
      {showAdd && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setShowAdd(false)}>
          <div className="bg-gray-800 border border-gray-700 rounded-xl p-6 w-full max-w-lg shadow-2xl" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-white font-semibold">Add Custom Rule</h2>
              <button onClick={() => setShowAdd(false)} className="text-gray-400 hover:text-white"><XMarkIcon className="w-5 h-5" /></button>
            </div>
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-gray-400 text-xs mb-1 block">Analyzer *</label>
                  <select value={form.analyzer} onChange={e => setForm(f => ({ ...f, analyzer: e.target.value }))}
                          className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white">
                    <option>SECURITY</option>
                    <option>PERFORMANCE</option>
                    <option>RESILIENCE</option>
                    <option>SLA</option>
                  </select>
                </div>
                <div>
                  <label className="text-gray-400 text-xs mb-1 block">Severity *</label>
                  <select value={form.severity} onChange={e => setForm(f => ({ ...f, severity: e.target.value }))}
                          className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white">
                    <option>CRITICAL</option>
                    <option>HIGH</option>
                    <option>MEDIUM</option>
                    <option>LOW</option>
                    <option>INFO</option>
                  </select>
                </div>
              </div>
              <div>
                <label className="text-gray-400 text-xs mb-1 block">Rule Name * (unique, e.g. partner_abc_failure)</label>
                <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                       placeholder="rule_name_snake_case"
                       className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white placeholder-gray-500" />
              </div>
              <div>
                <label className="text-gray-400 text-xs mb-1 block">Description</label>
                <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                       placeholder="What this rule detects"
                       className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white placeholder-gray-500" />
              </div>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className="text-gray-400 text-xs mb-1 block">Threshold</label>
                  <input type="number" value={form.thresholdValue} onChange={e => setForm(f => ({ ...f, thresholdValue: e.target.value }))}
                         className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white" />
                </div>
                <div>
                  <label className="text-gray-400 text-xs mb-1 block">Window (min)</label>
                  <input type="number" value={form.windowMinutes} onChange={e => setForm(f => ({ ...f, windowMinutes: +e.target.value }))}
                         className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white" />
                </div>
                <div>
                  <label className="text-gray-400 text-xs mb-1 block">Cooldown (min)</label>
                  <input type="number" value={form.cooldownMinutes} onChange={e => setForm(f => ({ ...f, cooldownMinutes: +e.target.value }))}
                         className="w-full bg-gray-700 border border-gray-600 rounded px-2 py-1.5 text-sm text-white" />
                </div>
              </div>
              {formError && <p className="text-red-400 text-xs">{formError}</p>}
              <div className="flex gap-3 pt-2">
                <button onClick={() => create.mutate({ ...form, thresholdValue: form.thresholdValue === '' ? null : +form.thresholdValue })}
                        disabled={create.isPending || !form.name.trim()}
                        className="flex-1 bg-purple-600 hover:bg-purple-700 disabled:opacity-50 text-white text-sm py-2 rounded-lg transition-colors">
                  {create.isPending ? 'Creating…' : 'Create Rule'}
                </button>
                <button onClick={() => setShowAdd(false)} className="px-4 bg-gray-700 hover:bg-gray-600 text-white text-sm rounded-lg transition-colors">
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Rules table grouped by analyzer */}
      {Object.entries(grouped).map(([analyzer, analyzerRules]) => (
        <div key={analyzer}>
          <h3 className="text-gray-300 text-sm font-semibold uppercase tracking-wider mb-2">{analyzer}</h3>
          <div className="bg-gray-800 rounded-lg border border-gray-700 overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-700 text-gray-400 text-xs">
                  <th className="px-4 py-2 text-left">On</th>
                  <th className="px-4 py-2 text-left">Rule</th>
                  <th className="px-4 py-2 text-left">Description</th>
                  <th className="px-4 py-2 text-left">Severity</th>
                  <th className="px-4 py-2 text-left">Threshold</th>
                  <th className="px-4 py-2 text-left">Window</th>
                  <th className="px-4 py-2 text-left">Cooldown</th>
                  <th className="px-4 py-2 text-left">Last Triggered</th>
                  <th className="px-4 py-2 text-left w-8"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-700">
                {analyzerRules.map(rule => (
                  <tr key={rule.id} className="hover:bg-gray-750">
                    <td className="px-4 py-2">
                      <button onClick={() => update.mutate({ id: rule.id, data: { enabled: !rule.enabled } })}
                              className={`w-8 h-4 rounded-full flex items-center transition-colors ${rule.enabled ? 'bg-green-600 justify-end' : 'bg-gray-600 justify-start'}`}>
                        <span className="w-3 h-3 bg-white rounded-full mx-0.5" />
                      </button>
                    </td>
                    <td className="px-4 py-2 text-gray-200 font-mono text-xs">
                      {rule.name}
                      {rule.builtin && <span className="ml-1.5 text-gray-600 text-xs">[builtin]</span>}
                    </td>
                    <td className="px-4 py-2 text-gray-400 max-w-xs truncate">{rule.description}</td>
                    <td className="px-4 py-2"><span className={`text-xs ${SEV_TEXT[rule.severity]}`}>{rule.severity}</span></td>
                    <td className="px-4 py-2 text-gray-300">{rule.thresholdValue ?? '—'}</td>
                    <td className="px-4 py-2 text-gray-400">{rule.windowMinutes}m</td>
                    <td className="px-4 py-2 text-gray-400">{rule.cooldownMinutes}m</td>
                    <td className="px-4 py-2 text-gray-500 text-xs">
                      {rule.lastTriggered ? new Date(rule.lastTriggered).toLocaleString() : '—'}
                    </td>
                    <td className="px-4 py-2">
                      {!rule.builtin && (
                        confirmDelete === rule.id ? (
                          <div className="flex items-center gap-1">
                            <button onClick={() => remove.mutate(rule.id)}
                                    className="text-xs text-red-400 hover:text-red-300">Yes</button>
                            <span className="text-gray-600">/</span>
                            <button onClick={() => setConfirmDelete(null)}
                                    className="text-xs text-gray-400 hover:text-gray-300">No</button>
                          </div>
                        ) : (
                          <button onClick={() => setConfirmDelete(rule.id)}
                                  className="text-gray-600 hover:text-red-400 transition-colors">
                            <TrashIcon className="w-4 h-4" />
                          </button>
                        )
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}
    </div>
  )
}

// --- Main Page ---
export default function Sentinel() {
  const [activeTab, setActiveTab] = useState('overview')
  const queryClient = useQueryClient()

  const { data: health } = useQuery({
    queryKey: ['sentinel-health'], queryFn: sentinelApi.getSentinelHealth, refetchInterval: 30000,
    retry: 1
  })

  const trigger = useMutation({
    mutationFn: sentinelApi.triggerAnalysis,
    onSuccess: () => {
      setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: ['sentinel-dashboard'] })
        queryClient.invalidateQueries({ queryKey: ['sentinel-findings'] })
      }, 5000)
    }
  })

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <BoltIcon className="w-6 h-6 text-purple-400" />
          <div>
            <h1 className="text-xl font-bold text-white">Platform Sentinel</h1>
            <p className="text-gray-400 text-sm">Autonomous observer — security, performance, and health monitoring</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className={`px-2 py-1 rounded text-xs font-medium ${health?.status === 'UP' ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'}`}>
            {health?.status || 'OFFLINE'}
          </span>
          {health && <span className="text-gray-500 text-xs">{health.totalRules} rules · {health.openFindings} open</span>}
          <button onClick={() => trigger.mutate()}
                  disabled={trigger.isPending}
                  className="flex items-center gap-1 px-3 py-1.5 bg-purple-600 text-white text-sm rounded hover:bg-purple-700 disabled:opacity-50">
            <ArrowPathIcon className={`w-4 h-4 ${trigger.isPending ? 'animate-spin' : ''}`} />
            Analyze Now
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-700">
        {TABS.map(tab => (
          <button key={tab.key} onClick={() => setActiveTab(tab.key)}
                  className={`flex items-center gap-1.5 px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-purple-500 text-purple-400'
                      : 'border-transparent text-gray-400 hover:text-gray-300'
                  }`}>
            <tab.icon className="w-4 h-4" />
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && <OverviewTab />}
      {activeTab === 'findings' && <FindingsTab />}
      {activeTab === 'correlations' && <CorrelationsTab />}
      {activeTab === 'rules' && <RulesTab />}
    </div>
  )
}
