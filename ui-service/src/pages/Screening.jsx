import { useState, useEffect, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { screeningApi } from '../api/client'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  ShieldExclamationIcon, CheckBadgeIcon, PlusIcon, PencilSquareIcon,
  TrashIcon, ArrowPathIcon, DocumentMagnifyingGlassIcon,
  ExclamationTriangleIcon, CheckCircleIcon, XCircleIcon,
  ArrowUpTrayIcon, EyeIcon
} from '@heroicons/react/24/outline'

// ── Helpers ──────────────────────────────────────────────────────────────

const severityColor = (s) => {
  switch (s?.toUpperCase()) {
    case 'CRITICAL': return 'badge-red'
    case 'HIGH':     return 'badge-orange'
    case 'MEDIUM':   return 'badge-yellow'
    case 'LOW':      return 'badge-blue'
    default:         return 'badge-gray'
  }
}

const statusColor = (s) => {
  switch (s?.toUpperCase()) {
    case 'QUARANTINED': return 'badge-red'
    case 'RELEASED':    return 'badge-green'
    case 'DELETED':     return 'badge-gray'
    default:            return 'badge-gray'
  }
}

const actionColor = (a) => {
  switch (a?.toUpperCase()) {
    case 'BLOCK': return 'badge-red'
    case 'FLAG':  return 'badge-yellow'
    case 'LOG':   return 'badge-blue'
    default:      return 'badge-gray'
  }
}

const sourceColor = (s) => {
  switch (s?.toUpperCase()) {
    case 'AV':     return 'badge-red'
    case 'DLP':    return 'badge-orange'
    case 'MANUAL': return 'badge-blue'
    default:       return 'badge-gray'
  }
}

const formatBytes = (bytes) => {
  if (!bytes) return '-'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

const ACTIONS = ['BLOCK', 'FLAG', 'LOG']
const PATTERN_TYPES = ['PCI_CREDIT_CARD', 'PCI_IBAN', 'PII_SSN', 'PII_EMAIL', 'PII_PHONE', 'CUSTOM']

const defaultPolicyForm = {
  name: '', description: '', action: 'BLOCK', active: true,
  patterns: [{ type: 'CUSTOM', regex: '', label: '' }]
}

// ── Main Component ──────────────────────────────────────────────────────

export default function Screening() {
  const qc = useQueryClient()
  const [tab, setTab] = useState('screening')

  // ── Quarantine state ──
  const [expandedRow, setExpandedRow] = useState(null)
  const [confirmAction, setConfirmAction] = useState(null) // { id, type: 'release'|'delete' }

  // ── DLP state ──
  const [showPolicyModal, setShowPolicyModal] = useState(false)
  const [editingPolicy, setEditingPolicy] = useState(null)
  const [policyForm, setPolicyForm] = useState({ ...defaultPolicyForm })

  // ── DLP Rules state ──
  const [showRuleModal, setShowRuleModal] = useState(false)
  const [editingRule, setEditingRule] = useState(null)
  const [ruleForm, setRuleForm] = useState({ name: '', pattern: '', category: 'CUSTOM', severity: 'MEDIUM', description: '', enabled: true })
  const [showScanModal, setShowScanModal] = useState(false)
  const [scanContent, setScanContent] = useState('')
  const [scanFile, setScanFile] = useState(null)
  const [scanResult, setScanResult] = useState(null)
  const [scanning, setScanning] = useState(false)
  const [confirmDeletePolicy, setConfirmDeletePolicy] = useState(null)
  const [confirmDeleteRule, setConfirmDeleteRule] = useState(null)
  const fileInputRef = useRef(null)

  // ═══════════════════════════════════════════════════════════════════════
  // Queries — OFAC Screening
  // ═══════════════════════════════════════════════════════════════════════
  // Previously every useQuery on this page used `.catch(() => [])` which
  // silently masked a screening-service outage. Now we surface the error
  // state via a shared toast (dedup'd via a stable toast id) and keep the
  // default data so the rest of the page still renders.

  const toastOnError = (id, label) => (e) => {
    const msg = e?.response?.data?.message || e?.message || 'request failed'
    toast.error(`${label}: ${msg}`, { id })
  }

  const { data: lists, isError: listsError } = useQuery({
    queryKey: ['screen-lists'],
    queryFn: () => screeningApi.get('/api/v1/screening/lists').then(r => r.data),
    retry: 1,
    onError: toastOnError('screen-lists-err', "Couldn't load sanctions lists"),
  })
  const { data: results = [] } = useQuery({
    queryKey: ['screen-results'],
    queryFn: () => screeningApi.get('/api/v1/screening/results').then(r => r.data),
    refetchInterval: 15000,
    retry: 1,
    onError: toastOnError('screen-results-err', "Couldn't load screening results"),
  })
  const { data: hits = [] } = useQuery({
    queryKey: ['screen-hits'],
    queryFn: () => screeningApi.get('/api/v1/screening/hits').then(r => r.data),
    retry: 1,
    onError: toastOnError('screen-hits-err', "Couldn't load sanctions hits"),
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Queries — Quarantine
  // ═══════════════════════════════════════════════════════════════════════

  const { data: quarantineStats = {}, isLoading: loadingStats } = useQuery({
    queryKey: ['quarantine-stats'],
    queryFn: () => screeningApi.get('/api/v1/quarantine/stats').then(r => r.data),
    refetchInterval: 30000,
    retry: 1,
    onError: toastOnError('quarantine-stats-err', "Couldn't load quarantine stats"),
  })

  const { data: quarantineItems = [], isLoading: loadingQuarantine } = useQuery({
    queryKey: ['quarantine-items'],
    queryFn: () => screeningApi.get('/api/v1/quarantine').then(r => r.data),
    refetchInterval: 30000,
    retry: 1,
    onError: toastOnError('quarantine-items-err', "Couldn't load quarantine items"),
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Queries — DLP
  // ═══════════════════════════════════════════════════════════════════════

  const { data: dlpPolicies = [], isLoading: loadingPolicies } = useQuery({
    queryKey: ['dlp-policies'],
    queryFn: () => screeningApi.get('/api/v1/dlp/policies').then(r => r.data),
    retry: 1,
    onError: toastOnError('dlp-policies-err', "Couldn't load DLP policies"),
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Mutations — Quarantine
  // ═══════════════════════════════════════════════════════════════════════

  const releaseFile = useMutation({
    mutationFn: (id) => screeningApi.post(`/api/v1/quarantine/${id}/release`, {}).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['quarantine-items'])
      qc.invalidateQueries(['quarantine-stats'])
      setConfirmAction(null)
      toast.success('File released successfully')
    },
    onError: err => toast.error(err.response?.data?.error || 'Failed to release file')
  })

  const deleteFile = useMutation({
    mutationFn: (id) => screeningApi.delete(`/api/v1/quarantine/${id}`).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['quarantine-items'])
      qc.invalidateQueries(['quarantine-stats'])
      setConfirmAction(null)
      toast.success('File permanently deleted')
    },
    onError: err => toast.error(err.response?.data?.error || 'Failed to delete file')
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Mutations — DLP
  // ═══════════════════════════════════════════════════════════════════════

  const createPolicy = useMutation({
    mutationFn: (data) => screeningApi.post('/api/v1/dlp/policies', data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-policies'])
      setShowPolicyModal(false)
      setPolicyForm({ ...defaultPolicyForm })
      toast.success('DLP policy created')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create policy')
  })

  const updatePolicy = useMutation({
    mutationFn: ({ id, data }) => screeningApi.put(`/api/v1/dlp/policies/${id}`, data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-policies'])
      setShowPolicyModal(false)
      setEditingPolicy(null)
      setPolicyForm({ ...defaultPolicyForm })
      toast.success('DLP policy updated')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update policy')
  })

  const deletePolicy = useMutation({
    mutationFn: (id) => screeningApi.delete(`/api/v1/dlp/policies/${id}`),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-policies'])
      toast.success('DLP policy deleted')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to delete policy')
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Queries — DLP Rules
  // ═══════════════════════════════════════════════════════════════════════

  const { data: dlpRules = [], isLoading: loadingRules } = useQuery({
    queryKey: ['dlp-rules'],
    queryFn: () => screeningApi.get('/api/v1/dlp/policies').then(r => r.data).catch(() => [])
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Queries — Screening Quarantine (paginated)
  // ═══════════════════════════════════════════════════════════════════════

  const { data: screeningQuarantine = [], isLoading: loadingScreeningQ } = useQuery({
    queryKey: ['screening-quarantine'],
    queryFn: () => screeningApi.get('/api/v1/quarantine', { params: { page: 0, size: 10 } })
      .then(r => r.data?.content || r.data || []).catch(() => []),
    refetchInterval: 30000
  })

  // ═══════════════════════════════════════════════════════════════════════
  // Mutations — DLP Rules
  // ═══════════════════════════════════════════════════════════════════════

  const createRule = useMutation({
    mutationFn: (data) => screeningApi.post('/api/v1/dlp/policies', data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-rules'])
      setShowRuleModal(false)
      setRuleForm({ name: '', pattern: '', category: 'CUSTOM', severity: 'MEDIUM', description: '', enabled: true })
      toast.success('DLP rule created')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to create rule')
  })

  const updateRule = useMutation({
    mutationFn: ({ id, data }) => screeningApi.put(`/api/v1/dlp/policies/${id}`, data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-rules'])
      setShowRuleModal(false)
      setEditingRule(null)
      setRuleForm({ name: '', pattern: '', category: 'CUSTOM', severity: 'MEDIUM', description: '', enabled: true })
      toast.success('DLP rule updated')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to update rule')
  })

  const deleteRule = useMutation({
    mutationFn: (id) => screeningApi.delete(`/api/v1/dlp/policies/${id}`),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-rules'])
      toast.success('DLP rule deleted')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to delete rule')
  })

  const toggleRuleEnabled = useMutation({
    mutationFn: ({ id, enabled }) => screeningApi.put(`/api/v1/dlp/policies/${id}`, { enabled }).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries(['dlp-rules'])
      toast.success('Rule toggled')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to toggle rule')
  })

  // ── DLP Rule Handlers ──

  const openCreateRule = () => {
    setEditingRule(null)
    setRuleForm({ name: '', pattern: '', category: 'CUSTOM', severity: 'MEDIUM', description: '', enabled: true })
    setShowRuleModal(true)
  }

  const openEditRule = (rule) => {
    setEditingRule(rule)
    setRuleForm({
      name: rule.name || '',
      pattern: rule.pattern || '',
      category: rule.category || 'CUSTOM',
      severity: rule.severity || 'MEDIUM',
      description: rule.description || '',
      enabled: rule.enabled !== false
    })
    setShowRuleModal(true)
  }

  const handleSaveRule = () => {
    if (editingRule) {
      updateRule.mutate({ id: editingRule.id, data: ruleForm })
    } else {
      createRule.mutate(ruleForm)
    }
  }

  const DLP_CATEGORIES = ['PII', 'PHI', 'PCI', 'CUSTOM']
  const DLP_SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

  // ── DLP Handlers ──

  const openCreatePolicy = () => {
    setEditingPolicy(null)
    setPolicyForm({ ...defaultPolicyForm, patterns: [{ type: 'CUSTOM', regex: '', label: '' }] })
    setShowPolicyModal(true)
  }

  const openEditPolicy = (p) => {
    setEditingPolicy(p)
    setPolicyForm({
      name: p.name || '',
      description: p.description || '',
      action: p.action || 'BLOCK',
      active: p.active !== false,
      patterns: p.patterns?.length ? p.patterns.map(pt => ({ ...pt })) : [{ type: 'CUSTOM', regex: '', label: '' }]
    })
    setShowPolicyModal(true)
  }

  const handleSavePolicy = () => {
    const payload = {
      ...policyForm,
      patterns: policyForm.patterns.filter(p => p.regex.trim())
    }
    if (editingPolicy) {
      updatePolicy.mutate({ id: editingPolicy.id, data: payload })
    } else {
      createPolicy.mutate(payload)
    }
  }

  const addPattern = () => {
    setPolicyForm(f => ({ ...f, patterns: [...f.patterns, { type: 'CUSTOM', regex: '', label: '' }] }))
  }

  const removePattern = (idx) => {
    setPolicyForm(f => ({ ...f, patterns: f.patterns.filter((_, i) => i !== idx) }))
  }

  const updatePattern = (idx, field, value) => {
    setPolicyForm(f => ({
      ...f,
      patterns: f.patterns.map((p, i) => i === idx ? { ...p, [field]: value } : p)
    }))
  }

  const handleDlpScan = async () => {
    setScanning(true)
    setScanResult(null)
    try {
      const formData = new FormData()
      if (scanFile) {
        formData.append('file', scanFile)
      } else if (scanContent.trim()) {
        const blob = new Blob([scanContent], { type: 'text/plain' })
        formData.append('file', blob, 'manual-scan.txt')
      } else {
        toast.error('Please provide content or select a file to scan')
        setScanning(false)
        return
      }
      const res = await screeningApi.post('/api/v1/dlp/scan', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      setScanResult(res.data)
    } catch (err) {
      toast.error(err.response?.data?.message || 'Scan failed')
    } finally {
      setScanning(false)
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Render: OFAC/AML Screening Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderScreening = () => (
    <div className="space-y-6">
      <div className="grid grid-cols-3 gap-4">
        {lists && Object.entries(lists.lists || {}).map(([name, count]) => (
          <div key={name} className="card text-center">
            <p className="text-2xl font-bold text-primary">{Number(count).toLocaleString()}</p>
            <p className="text-sm text-secondary">{name}</p>
          </div>
        ))}
      </div>
      {lists?.lastRefresh && <p className="text-xs text-muted">Last refreshed: {lists.lastRefresh} (auto-refresh every 6 hours)</p>}

      {hits.length > 0 && (
        <div className="card border-red-200 bg-red-50">
          <h3 className="font-semibold text-red-800 flex items-center gap-2"><ShieldExclamationIcon className="w-5 h-5" /> Sanctions Hits ({hits.length})</h3>
          <table className="w-full mt-3"><thead><tr className="border-b border-red-200">
            <th className="table-header">Track ID</th><th className="table-header">File</th><th className="table-header">Outcome</th><th className="table-header">Hits</th><th className="table-header">Action</th>
          </tr></thead><tbody>
            {hits.map(h => (
              <tr key={h.id} className="border-b border-red-100 cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]">
                <td className="table-cell font-mono text-xs font-bold text-red-700">{h.trackId}</td>
                <td className="table-cell text-sm">{h.filename}</td>
                <td className="table-cell"><span className="badge badge-red">{h.outcome}</span></td>
                <td className="table-cell">{h.hitsFound}</td>
                <td className="table-cell"><span className="badge badge-red">{h.actionTaken}</span></td>
              </tr>
            ))}
          </tbody></table>
        </div>
      )}

      <div className="card">
        <h3 className="font-semibold text-primary mb-3">Recent Screenings</h3>
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Track ID</th><th className="table-header">File</th><th className="table-header">Records</th>
          <th className="table-header">Outcome</th><th className="table-header">Duration</th><th className="table-header">Time</th>
        </tr></thead><tbody>
          {results.length === 0 ? (
            <tr><td colSpan={6} className="text-center py-8 text-secondary text-sm">No screening results yet. Results appear as files are screened during transfer.</td></tr>
          ) : results.map(r => (
            <tr key={r.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]">
              <td className="table-cell font-mono text-xs">{r.trackId}</td>
              <td className="table-cell text-sm">{r.filename}</td>
              <td className="table-cell text-xs">{r.recordsScanned}</td>
              <td className="table-cell"><span className={`badge ${r.outcome === 'CLEAR' ? 'badge-green' : r.outcome === 'HIT' ? 'badge-red' : 'badge-yellow'}`}>{r.outcome}</span></td>
              <td className="table-cell text-xs">{r.durationMs}ms</td>
              <td className="table-cell text-xs text-secondary">{r.screenedAt ? format(new Date(r.screenedAt), 'HH:mm:ss') : ''}</td>
            </tr>
          ))}
        </tbody></table>
      </div>
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: Quarantine Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderQuarantine = () => (
    <div className="space-y-4">
      {/* Stats cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="card text-center">
          <p className="text-2xl font-bold text-primary">{quarantineStats.total || 0}</p>
          <p className="text-sm text-secondary">Total Quarantined</p>
        </div>
        <div className="card text-center border-red-200 bg-red-50">
          <p className="text-2xl font-bold text-red-700">{quarantineStats.quarantined || 0}</p>
          <p className="text-sm text-red-600">Pending Review</p>
        </div>
        <div className="card text-center border-green-200 bg-green-50">
          <p className="text-2xl font-bold text-green-700">{quarantineStats.released || 0}</p>
          <p className="text-sm text-green-600">Released</p>
        </div>
        <div className="card text-center">
          <p className="text-2xl font-bold text-secondary">{quarantineStats.deleted || 0}</p>
          <p className="text-sm text-secondary">Deleted</p>
        </div>
      </div>

      <p className="text-sm text-secondary">
        Files quarantined by antivirus or DLP scans. Review each file and decide to release or permanently delete. Auto-refreshes every 30 seconds.
      </p>

      {loadingQuarantine ? <LoadingSpinner /> : quarantineItems.length === 0 ? (
        <EmptyState title="No quarantined files" description="Files flagged by AV or DLP scans will appear here for review." />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Time</th>
                <th className="table-header">Filename</th>
                <th className="table-header">Reason</th>
                <th className="table-header">Source</th>
                <th className="table-header">Size</th>
                <th className="table-header">User</th>
                <th className="table-header">Track ID</th>
                <th className="table-header">Status</th>
                <th className="table-header w-32">Actions</th>
              </tr></thead>
              <tbody>
                {quarantineItems.map(q => (
                  <>
                    <tr key={q.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => setExpandedRow(expandedRow === q.id ? null : q.id)}>
                      <td className="table-cell text-xs text-secondary whitespace-nowrap">
                        {q.quarantinedAt ? format(new Date(q.quarantinedAt), 'MMM dd HH:mm:ss') : ''}
                      </td>
                      <td className="table-cell">
                        <div className="font-medium text-sm text-primary max-w-[200px] truncate" title={q.filename}>{q.filename}</div>
                      </td>
                      <td className="table-cell text-xs max-w-[200px]">
                        <span className="text-red-700" title={q.reason}>{q.reason?.length > 50 ? q.reason.slice(0, 50) + '...' : q.reason}</span>
                      </td>
                      <td className="table-cell"><span className={`badge ${sourceColor(q.detectionSource)}`}>{q.detectionSource || 'AV'}</span></td>
                      <td className="table-cell text-xs">{formatBytes(q.fileSizeBytes)}</td>
                      <td className="table-cell text-sm">{q.accountUsername || '-'}</td>
                      <td className="table-cell font-mono text-xs">{q.trackId || '-'}</td>
                      <td className="table-cell"><span className={`badge ${statusColor(q.status)}`}>{q.status}</span></td>
                      <td className="table-cell">
                        <div className="flex gap-1">
                          <button onClick={(e) => { e.stopPropagation(); setExpandedRow(expandedRow === q.id ? null : q.id) }}
                            className="p-1 rounded hover:bg-hover" title="Details" aria-label="Details">
                            <EyeIcon className="w-4 h-4 text-secondary" />
                          </button>
                          {q.status === 'QUARANTINED' && (
                            <>
                              <button onClick={(e) => { e.stopPropagation(); setConfirmAction({ id: q.id, type: 'release', filename: q.filename }) }}
                                className="p-1 rounded hover:bg-green-50" title="Release" aria-label="Release">
                                <CheckCircleIcon className="w-4 h-4 text-green-600" />
                              </button>
                              <button onClick={(e) => { e.stopPropagation(); setConfirmAction({ id: q.id, type: 'delete', filename: q.filename }) }}
                                className="p-1 rounded hover:bg-red-50" title="Delete permanently" aria-label="Delete permanently">
                                <TrashIcon className="w-4 h-4 text-red-500" />
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                    {expandedRow === q.id && (
                      <tr key={`${q.id}-detail`} className="bg-canvas">
                        <td colSpan={9} className="p-4">
                          <div className="grid grid-cols-2 md:grid-cols-3 gap-3 text-sm">
                            <div><span className="font-medium text-secondary">Reason:</span> <span className="text-primary">{q.reason}</span></div>
                            <div><span className="font-medium text-secondary">Threat:</span> <span className="text-primary">{q.detectedThreat || '-'}</span></div>
                            <div><span className="font-medium text-secondary">SHA-256:</span> <span className="font-mono text-xs text-primary break-all">{q.sha256 || '-'}</span></div>
                            <div><span className="font-medium text-secondary">Original Path:</span> <span className="font-mono text-xs text-primary break-all">{q.originalPath}</span></div>
                            <div><span className="font-medium text-secondary">Quarantine Path:</span> <span className="font-mono text-xs text-primary break-all">{q.quarantinePath}</span></div>
                            <div><span className="font-medium text-secondary">Detection:</span> <span className="text-primary">{q.detectionSource || 'AV'}</span></div>
                            {q.reviewedBy && (
                              <>
                                <div><span className="font-medium text-secondary">Reviewed By:</span> <span className="text-primary">{q.reviewedBy}</span></div>
                                <div><span className="font-medium text-secondary">Reviewed At:</span> <span className="text-primary">{q.reviewedAt ? format(new Date(q.reviewedAt), 'MMM dd HH:mm:ss') : '-'}</span></div>
                                <div><span className="font-medium text-secondary">Notes:</span> <span className="text-primary">{q.reviewNotes || '-'}</span></div>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Confirm release/delete modal */}
      {confirmAction && (
        <Modal title={confirmAction.type === 'release' ? 'Release Quarantined File' : 'Permanently Delete File'} onClose={() => setConfirmAction(null)}>
          <div className="space-y-4">
            {confirmAction.type === 'release' ? (
              <div className="flex items-start gap-3 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
                <ExclamationTriangleIcon className="w-5 h-5 text-yellow-600 mt-0.5 flex-shrink-0" />
                <div>
                  <p className="text-sm font-medium text-yellow-800">Release requires re-scan</p>
                  <p className="text-sm text-yellow-700 mt-1">
                    The file <strong>{confirmAction.filename}</strong> will be re-scanned with ClamAV before release. If the scan still detects a threat, the release will be denied.
                  </p>
                </div>
              </div>
            ) : (
              <div className="flex items-start gap-3 p-3 bg-red-50 border border-red-200 rounded-lg">
                <XCircleIcon className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
                <div>
                  <p className="text-sm font-medium text-red-800">This action is irreversible</p>
                  <p className="text-sm text-red-700 mt-1">
                    The file <strong>{confirmAction.filename}</strong> will be permanently deleted from the quarantine storage. This cannot be undone.
                  </p>
                </div>
              </div>
            )}
            <div className="flex justify-end gap-2">
              <button onClick={() => setConfirmAction(null)} className="btn btn-secondary">Cancel</button>
              {confirmAction.type === 'release' ? (
                <button
                  onClick={() => releaseFile.mutate(confirmAction.id)}
                  disabled={releaseFile.isLoading}
                  className="btn bg-green-600 text-white hover:bg-green-700 disabled:opacity-50"
                >
                  {releaseFile.isLoading ? 'Re-scanning...' : 'Release File'}
                </button>
              ) : (
                <button
                  onClick={() => deleteFile.mutate(confirmAction.id)}
                  disabled={deleteFile.isLoading}
                  className="btn bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
                >
                  {deleteFile.isLoading ? 'Deleting...' : 'Delete Permanently'}
                </button>
              )}
            </div>
          </div>
        </Modal>
      )}
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: DLP Policies Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderDlp = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <p className="text-sm text-secondary">
          Data Loss Prevention policies scan file contents for sensitive data patterns (credit cards, SSNs, etc.)
        </p>
        <div className="flex gap-2">
          <button onClick={() => { setScanContent(''); setScanFile(null); setScanResult(null); setShowScanModal(true) }}
            className="btn btn-secondary flex items-center gap-1.5">
            <DocumentMagnifyingGlassIcon className="w-4 h-4" /> Manual Scan
          </button>
          <button onClick={openCreatePolicy} className="btn btn-primary flex items-center gap-1.5">
            <PlusIcon className="w-4 h-4" /> New Policy
          </button>
        </div>
      </div>

      {loadingPolicies ? <LoadingSpinner /> : dlpPolicies.length === 0 ? (
        <EmptyState title="No DLP policies" description="Create DLP policies to detect sensitive data in file transfers." />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Name</th>
                <th className="table-header">Patterns</th>
                <th className="table-header">Action</th>
                <th className="table-header">Active</th>
                <th className="table-header">Created</th>
                <th className="table-header w-24">Actions</th>
              </tr></thead>
              <tbody>
                {dlpPolicies.map(p => (
                  <tr key={p.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => openEditPolicy(p)}>
                    <td className="table-cell">
                      <div className="font-medium text-primary">{p.name}</div>
                      {p.description && <div className="text-xs text-secondary mt-0.5 max-w-xs truncate">{p.description}</div>}
                    </td>
                    <td className="table-cell">
                      <div className="flex flex-wrap gap-1">
                        {(p.patterns || []).slice(0, 3).map((pt, i) => (
                          <span key={i} className="badge badge-blue">{pt.type || pt.label || 'Pattern'}</span>
                        ))}
                        {(p.patterns || []).length > 3 && (
                          <span className="badge badge-gray">+{p.patterns.length - 3}</span>
                        )}
                      </div>
                    </td>
                    <td className="table-cell"><span className={`badge ${actionColor(p.action)}`}>{p.action}</span></td>
                    <td className="table-cell">
                      {p.active
                        ? <span className="badge badge-green">Active</span>
                        : <span className="badge badge-gray">Disabled</span>}
                    </td>
                    <td className="table-cell text-xs text-secondary">
                      {p.createdAt ? format(new Date(p.createdAt), 'MMM dd, yyyy') : ''}
                    </td>
                    <td className="table-cell">
                      <div className="flex gap-1">
                        <button onClick={(e) => { e.stopPropagation(); openEditPolicy(p) }} className="p-1 rounded hover:bg-hover" title="Edit" aria-label="Edit">
                          <PencilSquareIcon className="w-4 h-4 text-secondary" />
                        </button>
                        <button onClick={(e) => { e.stopPropagation(); setConfirmDeletePolicy(p) }}
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

      {/* DLP Policy Create/Edit Modal */}
      {showPolicyModal && (
        <Modal title={editingPolicy ? 'Edit DLP Policy' : 'Create DLP Policy'} onClose={() => { setShowPolicyModal(false); setEditingPolicy(null) }} size="lg">
          <div className="space-y-5 max-h-[70vh] overflow-y-auto pr-2">
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Name *</label>
              <input className="input w-full" value={policyForm.name}
                onChange={e => setPolicyForm(f => ({ ...f, name: e.target.value }))}
                placeholder="PCI Credit Card Detection" />
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Description</label>
              <textarea className="input w-full" rows={2} value={policyForm.description}
                onChange={e => setPolicyForm(f => ({ ...f, description: e.target.value }))}
                placeholder="Detects credit card numbers in file content" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Action</label>
                <select className="input w-full" value={policyForm.action}
                  onChange={e => setPolicyForm(f => ({ ...f, action: e.target.value }))}>
                  {ACTIONS.map(a => <option key={a} value={a}>{a}</option>)}
                </select>
              </div>
              <div className="flex items-end">
                <label className="flex items-center gap-2 cursor-pointer select-none">
                  <div className={`w-9 h-5 rounded-full relative transition-colors ${policyForm.active ? 'bg-indigo-600' : 'bg-gray-300'}`}
                    onClick={() => setPolicyForm(f => ({ ...f, active: !f.active }))}>
                    <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-surface shadow transition-transform ${policyForm.active ? 'translate-x-4' : 'translate-x-0.5'}`} />
                  </div>
                  <span className="text-sm text-primary">Active</span>
                </label>
              </div>
            </div>

            {/* Patterns */}
            <div>
              <div className="flex justify-between items-center mb-2">
                <h4 className="text-sm font-semibold text-primary border-b pb-1">Detection Patterns</h4>
                <button onClick={addPattern} className="text-xs text-indigo-600 hover:underline flex items-center gap-1">
                  <PlusIcon className="w-3 h-3" /> Add Pattern
                </button>
              </div>
              <div className="space-y-3">
                {policyForm.patterns.map((pat, idx) => (
                  <div key={idx} className="grid grid-cols-12 gap-2 items-end bg-canvas p-2 rounded-lg">
                    <div className="col-span-3">
                      <label className="block text-xs font-medium text-secondary mb-1">Type</label>
                      <select className="input w-full text-xs" value={pat.type}
                        onChange={e => updatePattern(idx, 'type', e.target.value)}>
                        {PATTERN_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                      </select>
                    </div>
                    <div className="col-span-5">
                      <label className="block text-xs font-medium text-secondary mb-1">Regex Pattern *</label>
                      <input className="input w-full text-xs font-mono" value={pat.regex}
                        onChange={e => updatePattern(idx, 'regex', e.target.value)}
                        placeholder="\\d{4}-\\d{4}-\\d{4}-\\d{4}" />
                    </div>
                    <div className="col-span-3">
                      <label className="block text-xs font-medium text-secondary mb-1">Label</label>
                      <input className="input w-full text-xs" value={pat.label}
                        onChange={e => updatePattern(idx, 'label', e.target.value)}
                        placeholder="Visa card" />
                    </div>
                    <div className="col-span-1 flex justify-center">
                      {policyForm.patterns.length > 1 && (
                        <button onClick={() => removePattern(idx)} className="p-1 rounded hover:bg-red-50" title="Remove" aria-label="Remove">
                          <TrashIcon className="w-4 h-4 text-red-400" />
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="flex justify-end gap-2 mt-4 pt-3 border-t">
            <button onClick={() => { setShowPolicyModal(false); setEditingPolicy(null) }} className="btn btn-secondary">Cancel</button>
            <button onClick={handleSavePolicy}
              disabled={!policyForm.name.trim() || !policyForm.patterns.some(p => p.regex.trim())}
              className="btn btn-primary">
              {editingPolicy ? 'Update' : 'Create'} Policy
            </button>
          </div>
        </Modal>
      )}

      {/* Manual DLP Scan Modal */}
      {showScanModal && (
        <Modal title="Manual DLP Scan" onClose={() => setShowScanModal(false)} size="lg">
          <div className="space-y-4">
            <p className="text-sm text-secondary">Paste text content or upload a file to scan for sensitive data matches.</p>

            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Paste content</label>
              <textarea className="input w-full font-mono text-xs" rows={6} value={scanContent}
                onChange={e => { setScanContent(e.target.value); setScanFile(null) }}
                placeholder="Paste file content here to scan..." />
            </div>

            <div className="flex items-center gap-3">
              <span className="text-xs text-secondary">OR</span>
              <button onClick={() => fileInputRef.current?.click()}
                className="btn btn-secondary flex items-center gap-1.5 text-sm">
                <ArrowUpTrayIcon className="w-4 h-4" />
                {scanFile ? scanFile.name : 'Upload File'}
              </button>
              <input ref={fileInputRef} type="file" className="hidden"
                onChange={e => { setScanFile(e.target.files[0]); setScanContent('') }} />
              {scanFile && (
                <button onClick={() => { setScanFile(null); if (fileInputRef.current) fileInputRef.current.value = '' }}
                  className="text-xs text-red-500 hover:underline">Remove</button>
              )}
            </div>

            <div className="flex justify-end">
              <button onClick={handleDlpScan} disabled={scanning || (!scanContent.trim() && !scanFile)}
                className="btn btn-primary flex items-center gap-1.5">
                {scanning ? <ArrowPathIcon className="w-4 h-4 animate-spin" /> : <DocumentMagnifyingGlassIcon className="w-4 h-4" />}
                {scanning ? 'Scanning...' : 'Run Scan'}
              </button>
            </div>

            {scanResult && (
              <div className={`p-4 rounded-lg border ${scanResult.matchCount > 0 ? 'bg-red-50 border-red-200' : 'bg-green-50 border-green-200'}`}>
                <h4 className={`font-semibold text-sm mb-2 ${scanResult.matchCount > 0 ? 'text-red-800' : 'text-green-800'}`}>
                  {scanResult.matchCount > 0
                    ? `${scanResult.matchCount} sensitive data match(es) found`
                    : 'No sensitive data detected'}
                </h4>
                {scanResult.matches && scanResult.matches.length > 0 && (
                  <div className="space-y-1 mt-2">
                    {scanResult.matches.map((m, i) => (
                      <div key={i} className="flex items-center gap-2 text-sm">
                        <span className="badge badge-red">{m.policyName || m.type || 'Match'}</span>
                        <span className="text-red-700">{m.description || m.pattern || ''}</span>
                        {m.count > 0 && <span className="text-xs text-secondary">({m.count} occurrences)</span>}
                      </div>
                    ))}
                  </div>
                )}
                {scanResult.action && (
                  <div className="mt-2 text-xs text-secondary">
                    Action taken: <span className={`badge ${actionColor(scanResult.action)}`}>{scanResult.action}</span>
                  </div>
                )}
              </div>
            )}
          </div>
        </Modal>
      )}
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: DLP Rules Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderDlpRules = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <p className="text-sm text-secondary">
          Individual DLP detection rules with regex patterns. Rules are grouped by category and severity.
        </p>
        <button onClick={openCreateRule} className="btn btn-primary flex items-center gap-1.5">
          <PlusIcon className="w-4 h-4" /> New Rule
        </button>
      </div>

      {loadingRules ? <LoadingSpinner /> : dlpRules.length === 0 ? (
        <EmptyState title="No DLP rules" description="Create DLP rules to detect sensitive data patterns in file transfers." />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Rule Name</th>
                <th className="table-header">Pattern / Regex</th>
                <th className="table-header">Category</th>
                <th className="table-header">Severity</th>
                <th className="table-header">Enabled</th>
                <th className="table-header w-24">Actions</th>
              </tr></thead>
              <tbody>
                {dlpRules.map(rule => (
                  <tr key={rule.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => openEditRule(rule)}>
                    <td className="table-cell">
                      <div className="font-medium text-primary">{rule.name}</div>
                      {rule.description && <div className="text-xs text-secondary mt-0.5 max-w-xs truncate">{rule.description}</div>}
                    </td>
                    <td className="table-cell">
                      <code className="text-xs font-mono bg-hover px-1.5 py-0.5 rounded break-all max-w-[200px] block truncate" title={rule.pattern}>
                        {rule.pattern}
                      </code>
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${rule.category === 'PCI' ? 'badge-red' : rule.category === 'PHI' ? 'badge-orange' : rule.category === 'PII' ? 'badge-yellow' : 'badge-blue'}`}>
                        {rule.category}
                      </span>
                    </td>
                    <td className="table-cell">
                      <span className={`badge ${severityColor(rule.severity)}`}>{rule.severity}</span>
                    </td>
                    <td className="table-cell">
                      <div
                        className={`w-9 h-5 rounded-full relative transition-colors cursor-pointer ${rule.enabled ? 'bg-indigo-600' : 'bg-gray-300'}`}
                        onClick={(e) => { e.stopPropagation(); toggleRuleEnabled.mutate({ id: rule.id, enabled: !rule.enabled }) }}
                      >
                        <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-surface shadow transition-transform ${rule.enabled ? 'translate-x-4' : 'translate-x-0.5'}`} />
                      </div>
                    </td>
                    <td className="table-cell">
                      <div className="flex gap-1">
                        <button onClick={(e) => { e.stopPropagation(); openEditRule(rule) }} className="p-1 rounded hover:bg-hover" title="Edit" aria-label="Edit">
                          <PencilSquareIcon className="w-4 h-4 text-secondary" />
                        </button>
                        <button onClick={(e) => { e.stopPropagation(); setConfirmDeleteRule(rule) }}
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

      {/* DLP Rule Create/Edit Modal */}
      {showRuleModal && (
        <Modal title={editingRule ? 'Edit DLP Rule' : 'Create DLP Rule'} onClose={() => { setShowRuleModal(false); setEditingRule(null) }}>
          <div className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Rule Name *</label>
              <input className="input w-full" value={ruleForm.name}
                onChange={e => setRuleForm(f => ({ ...f, name: e.target.value }))}
                placeholder="Credit Card Number Detection" />
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Pattern / Regex *</label>
              <input className="input w-full font-mono text-sm" value={ruleForm.pattern}
                onChange={e => setRuleForm(f => ({ ...f, pattern: e.target.value }))}
                placeholder="\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Category</label>
                <select className="input w-full" value={ruleForm.category}
                  onChange={e => setRuleForm(f => ({ ...f, category: e.target.value }))}>
                  {DLP_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Severity</label>
                <select className="input w-full" value={ruleForm.severity}
                  onChange={e => setRuleForm(f => ({ ...f, severity: e.target.value }))}>
                  {DLP_SEVERITIES.map(s => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Description</label>
              <textarea className="input w-full" rows={2} value={ruleForm.description}
                onChange={e => setRuleForm(f => ({ ...f, description: e.target.value }))}
                placeholder="Detects credit card numbers in transferred files" />
            </div>
            <div className="flex items-center gap-2">
              <div
                className={`w-9 h-5 rounded-full relative transition-colors cursor-pointer ${ruleForm.enabled ? 'bg-indigo-600' : 'bg-gray-300'}`}
                onClick={() => setRuleForm(f => ({ ...f, enabled: !f.enabled }))}
              >
                <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-surface shadow transition-transform ${ruleForm.enabled ? 'translate-x-4' : 'translate-x-0.5'}`} />
              </div>
              <span className="text-sm text-primary">Enabled</span>
            </div>
          </div>
          <div className="flex justify-end gap-2 mt-4 pt-3 border-t">
            <button onClick={() => { setShowRuleModal(false); setEditingRule(null) }} className="btn btn-secondary">Cancel</button>
            <button onClick={handleSaveRule}
              disabled={!ruleForm.name.trim() || !ruleForm.pattern.trim()}
              className="btn btn-primary">
              {editingRule ? 'Update' : 'Create'} Rule
            </button>
          </div>
        </Modal>
      )}
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Render: Quarantine Summary Tab
  // ═══════════════════════════════════════════════════════════════════════

  const renderQuarantineSummary = () => (
    <div className="space-y-4">
      {/* Stats cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="card text-center">
          <p className="text-2xl font-bold text-primary">{quarantineStats.total || 0}</p>
          <p className="text-sm text-secondary">Total Quarantined</p>
        </div>
        <div className="card text-center border-red-200 bg-red-50">
          <p className="text-2xl font-bold text-red-700">{quarantineStats.quarantined || 0}</p>
          <p className="text-sm text-red-600">Malware</p>
        </div>
        <div className="card text-center border-orange-200 bg-orange-50">
          <p className="text-2xl font-bold text-orange-700">{quarantineStats.dlp || 0}</p>
          <p className="text-sm text-orange-600">DLP Violations</p>
        </div>
        <div className="card text-center border-yellow-200 bg-yellow-50">
          <p className="text-2xl font-bold text-yellow-700">{quarantineStats.sanctions || 0}</p>
          <p className="text-sm text-yellow-600">Sanctions</p>
        </div>
      </div>

      {/* Link to full Quarantine Manager */}
      <div className="card bg-blue-50 border border-blue-100">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="font-semibold text-blue-900 mb-1">Full Quarantine Manager</h3>
            <p className="text-sm text-blue-700">
              View all quarantined files with detailed review, release, and delete capabilities.
            </p>
          </div>
          <a href="/quarantine" className="btn btn-primary flex items-center gap-1.5 text-sm whitespace-nowrap">
            Open Quarantine Manager
          </a>
        </div>
      </div>

      {/* Recent quarantined files */}
      <div className="card">
        <h3 className="font-semibold text-primary mb-3">Recent Quarantined Files (Last 10)</h3>
        {loadingScreeningQ ? <LoadingSpinner /> : screeningQuarantine.length === 0 ? (
          <EmptyState title="No recent quarantined files" description="Files flagged by screening will appear here." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Time</th>
                <th className="table-header">Filename</th>
                <th className="table-header">Reason</th>
                <th className="table-header">Source</th>
                <th className="table-header">Status</th>
                <th className="table-header w-24">Actions</th>
              </tr></thead>
              <tbody>
                {screeningQuarantine.map(q => (
                  <tr key={q.id} className="table-row cursor-pointer transition-colors duration-150 hover:bg-[rgba(100,140,255,0.06)]" onClick={() => setTab('quarantine')}>
                    <td className="table-cell text-xs text-secondary whitespace-nowrap">
                      {q.quarantinedAt ? format(new Date(q.quarantinedAt), 'MMM dd HH:mm:ss') : ''}
                    </td>
                    <td className="table-cell">
                      <span className="font-medium text-sm text-primary max-w-[200px] truncate block" title={q.filename}>{q.filename}</span>
                    </td>
                    <td className="table-cell text-xs max-w-[200px]">
                      <span className="text-red-700" title={q.reason}>{q.reason?.length > 50 ? q.reason.slice(0, 50) + '...' : q.reason}</span>
                    </td>
                    <td className="table-cell"><span className={`badge ${sourceColor(q.detectionSource)}`}>{q.detectionSource || 'AV'}</span></td>
                    <td className="table-cell"><span className={`badge ${statusColor(q.status)}`}>{q.status}</span></td>
                    <td className="table-cell">
                      <div className="flex gap-1">
                        {q.status === 'QUARANTINED' && (
                          <>
                            <button onClick={(e) => { e.stopPropagation(); releaseFile.mutate(q.id) }}
                              disabled={releaseFile.isPending}
                              className="p-1 rounded hover:bg-green-50" title="Release" aria-label="Release">
                              <CheckCircleIcon className="w-4 h-4 text-green-600" />
                            </button>
                            <button onClick={(e) => { e.stopPropagation(); deleteFile.mutate(q.id) }}
                              disabled={deleteFile.isPending}
                              className="p-1 rounded hover:bg-red-50" title="Delete permanently" aria-label="Delete permanently">
                              <TrashIcon className="w-4 h-4 text-red-500" />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )

  // ═══════════════════════════════════════════════════════════════════════
  // Main Render
  // ═══════════════════════════════════════════════════════════════════════

  const pendingCount = quarantineStats.quarantined || 0

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-primary flex items-center gap-2">
          <ShieldExclamationIcon className="w-7 h-7 text-indigo-600" />
          Security Screening
        </h1>
        <p className="text-secondary text-sm">OFAC/AML sanctions screening, file quarantine management, and DLP policy enforcement</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b">
        <button onClick={() => setTab('screening')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'screening' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          OFAC/AML Screening
        </button>
        <button onClick={() => setTab('quarantine')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors flex items-center gap-1.5 ${tab === 'quarantine' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          Quarantine
          {pendingCount > 0 && (
            <span className="bg-red-500 text-white text-xs font-bold rounded-full px-1.5 py-0.5 min-w-[20px] text-center">
              {pendingCount}
            </span>
          )}
        </button>
        <button onClick={() => setTab('dlp')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'dlp' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          DLP Policies
        </button>
        <button onClick={() => setTab('dlpRules')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'dlpRules' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          DLP Rules
        </button>
        <button onClick={() => setTab('quarantineSummary')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'quarantineSummary' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-secondary hover:text-primary'}`}>
          Quarantine Summary
        </button>
      </div>

      {/* Tab Content */}
      {tab === 'screening' && renderScreening()}
      {tab === 'quarantine' && renderQuarantine()}
      {tab === 'dlp' && renderDlp()}
      {tab === 'dlpRules' && renderDlpRules()}
      {tab === 'quarantineSummary' && renderQuarantineSummary()}

      <ConfirmDialog
        open={!!confirmDeletePolicy}
        variant="danger"
        title="Delete DLP policy?"
        message={confirmDeletePolicy ? `Are you sure you want to delete DLP policy "${confirmDeletePolicy.name}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deletePolicy.isPending}
        onConfirm={() => { deletePolicy.mutate(confirmDeletePolicy.id); setConfirmDeletePolicy(null) }}
        onCancel={() => setConfirmDeletePolicy(null)}
      />

      <ConfirmDialog
        open={!!confirmDeleteRule}
        variant="danger"
        title="Delete DLP rule?"
        message={confirmDeleteRule ? `Are you sure you want to delete DLP rule "${confirmDeleteRule.name}"? This action cannot be undone.` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteRule.isPending}
        onConfirm={() => { deleteRule.mutate(confirmDeleteRule.id); setConfirmDeleteRule(null) }}
        onCancel={() => setConfirmDeleteRule(null)}
      />
    </div>
  )
}
