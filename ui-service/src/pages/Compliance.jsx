import { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { configApi } from '../api/client'
import Modal from '../components/Modal'
import { friendlyError } from '../components/FormField'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import {
  PlusIcon, PencilSquareIcon, TrashIcon, CheckCircleIcon,
  ShieldExclamationIcon, ExclamationTriangleIcon
} from '@heroicons/react/24/outline'

// ── Default form state ──────────────────────────────────────────────────

const defaultProfileForm = {
  name: '', description: '', severity: 'HIGH',
  allowPciData: false, allowPhiData: false, allowPiiData: true, allowClassifiedData: false,
  maxAllowedRiskLevel: 'MEDIUM', maxAllowedRiskScore: 70,
  requireEncryption: false, requireScreening: true, requireChecksum: false,
  allowedFileExtensions: '', blockedFileExtensions: '', maxFileSizeBytes: '',
  requireTls: true, allowAnonymousAccess: false, requireMfa: false,
  auditAllTransfers: true, notifyOnViolation: true, violationAction: 'BLOCK'
}

const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
const RISK_LEVELS = ['NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
const ACTIONS = ['BLOCK', 'WARN', 'LOG']

const severityColor = (s) => {
  switch (s?.toUpperCase()) {
    case 'CRITICAL': return 'badge-red'
    case 'HIGH':     return 'badge-orange'
    case 'MEDIUM':   return 'badge-yellow'
    case 'LOW':      return 'badge-blue'
    default:         return 'badge-gray'
  }
}

const actionColor = (a) => {
  switch (a?.toUpperCase()) {
    case 'BLOCKED': case 'BLOCK': return 'badge-red'
    case 'WARNED':  case 'WARN':  return 'badge-yellow'
    case 'LOGGED':  case 'LOG':   return 'badge-blue'
    default: return 'badge-gray'
  }
}

// ── Main Component ──────────────────────────────────────────────────────

export default function Compliance() {
  const qc = useQueryClient()
  const [tab, setTab] = useState('profiles')
  const [showModal, setShowModal] = useState(false)
  const [editingProfile, setEditingProfile] = useState(null)
  const [form, setForm] = useState({ ...defaultProfileForm })
  const [resolveId, setResolveId] = useState(null)
  const [resolveNote, setResolveNote] = useState('')
  const [violationFilter, setViolationFilter] = useState({ severity: '', resolved: 'false', serverId: '', username: '' })

  // ── Queries ──

  const { data: profiles = [], isLoading: loadingProfiles } = useQuery({
    queryKey: ['compliance-profiles'],
    queryFn: () => configApi.get('/api/compliance/profiles').then(r => r.data).catch(() => [])
  })

  const { data: allProfiles = [] } = useQuery({
    queryKey: ['compliance-profiles-all'],
    queryFn: () => configApi.get('/api/compliance/profiles/all').then(r => r.data).catch(() => [])
  })

  const violationParams = new URLSearchParams()
  if (violationFilter.severity) violationParams.set('severity', violationFilter.severity)
  if (violationFilter.resolved !== '') violationParams.set('resolved', violationFilter.resolved)

  const { data: violations = [], isLoading: loadingViolations } = useQuery({
    queryKey: ['compliance-violations', violationFilter],
    queryFn: () => {
      // Use server-specific endpoint when filtering by server
      if (violationFilter.serverId) {
        return configApi.get(`/api/compliance/violations/server/${violationFilter.serverId}?${violationParams}`).then(r => r.data).catch(() => [])
      }
      // Use user-specific endpoint when filtering by username
      if (violationFilter.username) {
        return configApi.get(`/api/compliance/violations/user/${encodeURIComponent(violationFilter.username)}?${violationParams}`).then(r => r.data).catch(() => [])
      }
      return configApi.get(`/api/compliance/violations?${violationParams}`).then(r => r.data).catch(() => [])
    },
    refetchInterval: 15000
  })

  const { data: violationCount = {} } = useQuery({
    queryKey: ['compliance-violation-count'],
    queryFn: () => configApi.get('/api/compliance/violations/count').then(r => r.data).catch(() => ({ unresolved: 0 })),
    refetchInterval: 15000
  })

  const { data: servers = [] } = useQuery({
    queryKey: ['server-instances'],
    queryFn: () => configApi.get('/api/server-instances').then(r => r.data).catch(() => [])
  })

  // ── Mutations ──

  const createProfile = useMutation({
    mutationFn: (data) => configApi.post('/api/compliance/profiles', data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['compliance-profiles']); qc.invalidateQueries(['compliance-profiles-all']); setShowModal(false); setForm({ ...defaultProfileForm }); toast.success('Compliance profile created') },
    onError: err => toast.error(friendlyError(err))
  })

  const updateProfile = useMutation({
    mutationFn: ({ id, data }) => configApi.put(`/api/compliance/profiles/${id}`, data).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['compliance-profiles']); qc.invalidateQueries(['compliance-profiles-all']); setShowModal(false); setEditingProfile(null); setForm({ ...defaultProfileForm }); toast.success('Profile updated') },
    onError: err => toast.error(friendlyError(err))
  })

  const deleteProfile = useMutation({
    mutationFn: (id) => configApi.delete(`/api/compliance/profiles/${id}`),
    onSuccess: () => { qc.invalidateQueries(['compliance-profiles']); qc.invalidateQueries(['compliance-profiles-all']); toast.success('Profile deactivated') }
  })

  const resolveMut = useMutation({
    mutationFn: ({ id, note }) => configApi.post(`/api/compliance/violations/${id}/resolve`, { note }).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['compliance-violations']); qc.invalidateQueries(['compliance-violation-count']); setResolveId(null); setResolveNote(''); toast.success('Violation resolved') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to resolve')
  })

  const assignProfile = useMutation({
    mutationFn: ({ serverId, profileId }) => configApi.put(`/api/compliance/servers/${serverId}/profile`, { profileId: profileId || '' }).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['server-instances']); toast.success('Profile assigned') },
    onError: err => toast.error(err.response?.data?.message || 'Failed to assign')
  })

  // ── Handlers ──

  const openCreate = () => { setEditingProfile(null); setForm({ ...defaultProfileForm }); setShowModal(true) }

  const openEdit = (p) => {
    setEditingProfile(p)
    setForm({
      name: p.name || '', description: p.description || '', severity: p.severity || 'HIGH',
      allowPciData: !!p.allowPciData, allowPhiData: !!p.allowPhiData, allowPiiData: p.allowPiiData !== false, allowClassifiedData: !!p.allowClassifiedData,
      maxAllowedRiskLevel: p.maxAllowedRiskLevel || 'MEDIUM', maxAllowedRiskScore: p.maxAllowedRiskScore ?? 70,
      requireEncryption: !!p.requireEncryption, requireScreening: p.requireScreening !== false, requireChecksum: !!p.requireChecksum,
      allowedFileExtensions: p.allowedFileExtensions || '', blockedFileExtensions: p.blockedFileExtensions || '',
      maxFileSizeBytes: p.maxFileSizeBytes || '',
      requireTls: p.requireTls !== false, allowAnonymousAccess: !!p.allowAnonymousAccess, requireMfa: !!p.requireMfa,
      auditAllTransfers: p.auditAllTransfers !== false, notifyOnViolation: p.notifyOnViolation !== false,
      violationAction: p.violationAction || 'BLOCK'
    })
    setShowModal(true)
  }

  const handleSave = () => {
    const payload = {
      ...form,
      maxFileSizeBytes: form.maxFileSizeBytes ? Number(form.maxFileSizeBytes) : null,
      maxAllowedRiskScore: Number(form.maxAllowedRiskScore)
    }
    if (editingProfile) {
      updateProfile.mutate({ id: editingProfile.id, data: payload })
    } else {
      createProfile.mutate(payload)
    }
  }

  const Toggle = ({ label, checked, onChange }) => (
    <label className="flex items-center gap-2 cursor-pointer select-none">
      <div className={`w-9 h-5 rounded-full relative transition-colors ${checked ? 'bg-indigo-600' : 'bg-gray-300'}`}
        onClick={onChange}>
        <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${checked ? 'translate-x-4' : 'translate-x-0.5'}`} />
      </div>
      <span className="text-sm text-gray-700">{label}</span>
    </label>
  )

  // ── Render: Profiles Tab ──

  const renderProfiles = () => (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <p className="text-sm text-gray-500">{profiles.length} active compliance profile(s)</p>
        <button onClick={openCreate} className="btn btn-primary flex items-center gap-1.5">
          <PlusIcon className="w-4 h-4" /> New Profile
        </button>
      </div>

      {loadingProfiles ? <LoadingSpinner /> : profiles.length === 0 ? (
        <EmptyState title="No compliance profiles" description="Create a compliance profile to enforce data rules on your servers." />
      ) : (
        <div className="card overflow-hidden p-0">
          <table className="w-full">
            <thead><tr className="border-b">
              <th className="table-header">Name</th>
              <th className="table-header">Severity</th>
              <th className="table-header">Data Rules</th>
              <th className="table-header">Max Risk</th>
              <th className="table-header">Action</th>
              <th className="table-header">Key Requirements</th>
              <th className="table-header w-24">Actions</th>
            </tr></thead>
            <tbody>
              {profiles.map(p => (
                <tr key={p.id} className="table-row">
                  <td className="table-cell">
                    <div className="font-medium text-gray-900">{p.name}</div>
                    {p.description && <div className="text-xs text-gray-500 mt-0.5 max-w-xs truncate">{p.description}</div>}
                  </td>
                  <td className="table-cell"><span className={`badge ${severityColor(p.severity)}`}>{p.severity}</span></td>
                  <td className="table-cell text-xs space-x-1">
                    {!p.allowPciData && <span className="badge badge-red">No PCI</span>}
                    {!p.allowPhiData && <span className="badge badge-red">No PHI</span>}
                    {!p.allowPiiData && <span className="badge badge-orange">No PII</span>}
                    {!p.allowClassifiedData && <span className="badge badge-red">No Classified</span>}
                  </td>
                  <td className="table-cell text-sm">{p.maxAllowedRiskLevel} / {p.maxAllowedRiskScore}</td>
                  <td className="table-cell"><span className={`badge ${actionColor(p.violationAction)}`}>{p.violationAction}</span></td>
                  <td className="table-cell text-xs space-x-1">
                    {p.requireEncryption && <span className="badge badge-blue">Encryption</span>}
                    {p.requireTls && <span className="badge badge-blue">TLS</span>}
                    {p.requireMfa && <span className="badge badge-blue">MFA</span>}
                    {p.requireChecksum && <span className="badge badge-blue">Checksum</span>}
                    {p.requireScreening && <span className="badge badge-blue">Screening</span>}
                  </td>
                  <td className="table-cell">
                    <div className="flex gap-1">
                      <button onClick={() => openEdit(p)} className="p-1 rounded hover:bg-gray-100" title="Edit">
                        <PencilSquareIcon className="w-4 h-4 text-gray-500" />
                      </button>
                      <button onClick={() => { if (confirm('Deactivate this profile?')) deleteProfile.mutate(p.id) }} className="p-1 rounded hover:bg-gray-100" title="Deactivate">
                        <TrashIcon className="w-4 h-4 text-red-500" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )

  // ── Render: Violations Tab ──

  const renderViolations = () => (
    <div className="space-y-4">
      <div className="flex gap-3 items-center flex-wrap">
        <select className="input w-40" value={violationFilter.severity}
          onChange={e => setViolationFilter(f => ({ ...f, severity: e.target.value }))}>
          <option value="">All Severities</option>
          {SEVERITIES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <select className="input w-40" value={violationFilter.resolved}
          onChange={e => setViolationFilter(f => ({ ...f, resolved: e.target.value }))}>
          <option value="">All</option>
          <option value="false">Unresolved</option>
          <option value="true">Resolved</option>
        </select>
        <select className="input w-48" value={violationFilter.serverId}
          onChange={e => setViolationFilter(f => ({ ...f, serverId: e.target.value, username: '' }))}
          title="Filter by server">
          <option value="">All Servers</option>
          {servers.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <input
          className="input w-44"
          placeholder="Filter by username..."
          value={violationFilter.username}
          onChange={e => setViolationFilter(f => ({ ...f, username: e.target.value, serverId: '' }))}
          title="Filter by user"
        />
        <span className="text-sm text-gray-500">{violations.length} result(s)</span>
      </div>

      {loadingViolations ? <LoadingSpinner /> : violations.length === 0 ? (
        <EmptyState title="No violations found" description="No compliance violations match your filters." />
      ) : (
        <div className="card overflow-hidden p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead><tr className="border-b">
                <th className="table-header">Time</th>
                <th className="table-header">Severity</th>
                <th className="table-header">Type</th>
                <th className="table-header">File</th>
                <th className="table-header">User</th>
                <th className="table-header">Server</th>
                <th className="table-header">Profile</th>
                <th className="table-header">Action</th>
                <th className="table-header">Status</th>
                <th className="table-header w-20"></th>
              </tr></thead>
              <tbody>
                {violations.map(v => (
                  <tr key={v.id} className="table-row">
                    <td className="table-cell text-xs text-gray-500 whitespace-nowrap">
                      {v.createdAt ? format(new Date(v.createdAt), 'MMM dd HH:mm:ss') : ''}
                    </td>
                    <td className="table-cell"><span className={`badge ${severityColor(v.severity)}`}>{v.severity}</span></td>
                    <td className="table-cell text-xs font-mono">{v.violationType}</td>
                    <td className="table-cell text-sm max-w-[200px] truncate" title={v.filename}>{v.filename || '-'}</td>
                    <td className="table-cell text-sm">{v.username || '-'}</td>
                    <td className="table-cell text-sm">{v.serverName || '-'}</td>
                    <td className="table-cell text-sm">{v.profileName || '-'}</td>
                    <td className="table-cell"><span className={`badge ${actionColor(v.action)}`}>{v.action}</span></td>
                    <td className="table-cell">
                      {v.resolved
                        ? <span className="badge badge-green">Resolved</span>
                        : <span className="badge badge-red">Open</span>}
                    </td>
                    <td className="table-cell">
                      {!v.resolved && (
                        <button onClick={() => { setResolveId(v.id); setResolveNote('') }}
                          className="text-xs text-indigo-600 hover:underline">Resolve</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Resolve modal */}
      {resolveId && (
        <Modal title="Resolve Violation" onClose={() => setResolveId(null)}>
          <div className="space-y-3">
            <label className="block text-sm font-medium text-gray-700">Resolution Note</label>
            <textarea className="input w-full" rows={3} value={resolveNote}
              onChange={e => setResolveNote(e.target.value)}
              placeholder="Describe why this violation is being resolved..." />
            <div className="flex justify-end gap-2">
              <button onClick={() => setResolveId(null)} className="btn btn-secondary">Cancel</button>
              <button onClick={() => resolveMut.mutate({ id: resolveId, note: resolveNote })} className="btn btn-primary">
                Resolve
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )

  // ── Render: Server Assignment Tab ──

  const renderServerAssignment = () => (
    <div className="space-y-4">
      <p className="text-sm text-gray-500">Assign a compliance profile to each server. Transfers on that server will be evaluated against the profile rules.</p>
      {servers.length === 0 ? (
        <EmptyState title="No servers" description="No server instances found." />
      ) : (
        <div className="card overflow-hidden p-0">
          <table className="w-full">
            <thead><tr className="border-b">
              <th className="table-header">Server</th>
              <th className="table-header">Protocol</th>
              <th className="table-header">Status</th>
              <th className="table-header">Compliance Profile</th>
            </tr></thead>
            <tbody>
              {servers.map(s => (
                <tr key={s.id} className="table-row">
                  <td className="table-cell">
                    <div className="font-medium text-gray-900">{s.name}</div>
                    <div className="text-xs text-gray-500">{s.instanceId}</div>
                  </td>
                  <td className="table-cell"><span className="badge badge-blue">{s.protocol}</span></td>
                  <td className="table-cell">
                    {s.active ? <span className="badge badge-green">Active</span> : <span className="badge badge-gray">Inactive</span>}
                  </td>
                  <td className="table-cell">
                    <select
                      className="input w-56"
                      value={s.complianceProfileId || ''}
                      onChange={e => assignProfile.mutate({ serverId: s.id, profileId: e.target.value })}
                    >
                      <option value="">-- None --</option>
                      {allProfiles.filter(p => p.active !== false).map(p => (
                        <option key={p.id} value={p.id}>{p.name}</option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )

  // ── Profile Create/Edit Modal ──

  const renderProfileModal = () => (
    <Modal title={editingProfile ? 'Edit Compliance Profile' : 'Create Compliance Profile'} onClose={() => { setShowModal(false); setEditingProfile(null) }}>
      <div className="space-y-5 max-h-[70vh] overflow-y-auto pr-2">

        {/* Basic Info */}
        <div>
          <h4 className="text-sm font-semibold text-gray-900 mb-2 border-b pb-1">Basic Information</h4>
          <div className="grid grid-cols-2 gap-3">
            <div className="col-span-2">
              <label className="block text-xs font-medium text-gray-600 mb-1">Name *</label>
              <input className="input w-full" value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="PCI-DSS Strict" />
            </div>
            <div className="col-span-2">
              <label className="block text-xs font-medium text-gray-600 mb-1">Description</label>
              <textarea className="input w-full" rows={2} value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Severity</label>
              <select className="input w-full" value={form.severity} onChange={e => setForm(f => ({ ...f, severity: e.target.value }))}>
                {SEVERITIES.map(s => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
          </div>
        </div>

        {/* Data Classification */}
        <div>
          <h4 className="text-sm font-semibold text-gray-900 mb-2 border-b pb-1">Data Classification Rules</h4>
          <div className="grid grid-cols-2 gap-3">
            <Toggle label="Allow PCI Data (credit cards)" checked={form.allowPciData} onChange={() => setForm(f => ({ ...f, allowPciData: !f.allowPciData }))} />
            <Toggle label="Allow PHI Data (health info)" checked={form.allowPhiData} onChange={() => setForm(f => ({ ...f, allowPhiData: !f.allowPhiData }))} />
            <Toggle label="Allow PII Data (personal info)" checked={form.allowPiiData} onChange={() => setForm(f => ({ ...f, allowPiiData: !f.allowPiiData }))} />
            <Toggle label="Allow Classified Data" checked={form.allowClassifiedData} onChange={() => setForm(f => ({ ...f, allowClassifiedData: !f.allowClassifiedData }))} />
          </div>
        </div>

        {/* AI Risk Thresholds */}
        <div>
          <h4 className="text-sm font-semibold text-gray-900 mb-2 border-b pb-1">AI Risk Thresholds</h4>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Max Allowed Risk Level</label>
              <select className="input w-full" value={form.maxAllowedRiskLevel} onChange={e => setForm(f => ({ ...f, maxAllowedRiskLevel: e.target.value }))}>
                {RISK_LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Max Risk Score (0-100): {form.maxAllowedRiskScore}</label>
              <input type="range" min="0" max="100" className="w-full" value={form.maxAllowedRiskScore}
                onChange={e => setForm(f => ({ ...f, maxAllowedRiskScore: Number(e.target.value) }))} />
            </div>
          </div>
        </div>

        {/* File Rules */}
        <div>
          <h4 className="text-sm font-semibold text-gray-900 mb-2 border-b pb-1">File Rules</h4>
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <Toggle label="Require Encryption (PGP/AES)" checked={form.requireEncryption} onChange={() => setForm(f => ({ ...f, requireEncryption: !f.requireEncryption }))} />
              <Toggle label="Require AV/Sanctions Screening" checked={form.requireScreening} onChange={() => setForm(f => ({ ...f, requireScreening: !f.requireScreening }))} />
              <Toggle label="Require SHA-256 Checksum" checked={form.requireChecksum} onChange={() => setForm(f => ({ ...f, requireChecksum: !f.requireChecksum }))} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">Allowed Extensions (comma-separated)</label>
                <input className="input w-full" value={form.allowedFileExtensions} onChange={e => setForm(f => ({ ...f, allowedFileExtensions: e.target.value }))} placeholder="edi,xml,json,csv" />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">Blocked Extensions (comma-separated)</label>
                <input className="input w-full" value={form.blockedFileExtensions} onChange={e => setForm(f => ({ ...f, blockedFileExtensions: e.target.value }))} placeholder="exe,bat,cmd,ps1,sh" />
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Max File Size (bytes, empty = no limit)</label>
              <input type="number" className="input w-full" value={form.maxFileSizeBytes} onChange={e => setForm(f => ({ ...f, maxFileSizeBytes: e.target.value }))} placeholder="104857600" />
            </div>
          </div>
        </div>

        {/* Connection Rules */}
        <div>
          <h4 className="text-sm font-semibold text-gray-900 mb-2 border-b pb-1">Connection Rules</h4>
          <div className="grid grid-cols-2 gap-3">
            <Toggle label="Require TLS/SFTP" checked={form.requireTls} onChange={() => setForm(f => ({ ...f, requireTls: !f.requireTls }))} />
            <Toggle label="Allow Anonymous Access" checked={form.allowAnonymousAccess} onChange={() => setForm(f => ({ ...f, allowAnonymousAccess: !f.allowAnonymousAccess }))} />
            <Toggle label="Require Multi-Factor Auth" checked={form.requireMfa} onChange={() => setForm(f => ({ ...f, requireMfa: !f.requireMfa }))} />
          </div>
        </div>

        {/* Enforcement */}
        <div>
          <h4 className="text-sm font-semibold text-gray-900 mb-2 border-b pb-1">Enforcement</h4>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">Violation Action</label>
              <select className="input w-full" value={form.violationAction} onChange={e => setForm(f => ({ ...f, violationAction: e.target.value }))}>
                {ACTIONS.map(a => <option key={a} value={a}>{a}</option>)}
              </select>
            </div>
            <div className="flex flex-col justify-end gap-2">
              <Toggle label="Audit All Transfers" checked={form.auditAllTransfers} onChange={() => setForm(f => ({ ...f, auditAllTransfers: !f.auditAllTransfers }))} />
              <Toggle label="Notify on Violation" checked={form.notifyOnViolation} onChange={() => setForm(f => ({ ...f, notifyOnViolation: !f.notifyOnViolation }))} />
            </div>
          </div>
        </div>
      </div>

      <div className="flex justify-end gap-2 mt-4 pt-3 border-t">
        <button onClick={() => { setShowModal(false); setEditingProfile(null) }} className="btn btn-secondary">Cancel</button>
        <button onClick={handleSave} disabled={!form.name.trim()} className="btn btn-primary">
          {editingProfile ? 'Update' : 'Create'} Profile
        </button>
      </div>
    </Modal>
  )

  // ── Main Render ──

  const unresolvedCount = violationCount.unresolved || 0

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
          <ShieldExclamationIcon className="w-7 h-7 text-indigo-600" />
          Compliance Management
        </h1>
        <p className="text-gray-500 text-sm">Define compliance profiles, track violations, and assign enforcement rules to servers</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b">
        <button
          onClick={() => setTab('profiles')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'profiles' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-gray-500 hover:text-gray-700'}`}
        >
          Profiles
        </button>
        <button
          onClick={() => setTab('violations')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors flex items-center gap-1.5 ${tab === 'violations' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-gray-500 hover:text-gray-700'}`}
        >
          Violations
          {unresolvedCount > 0 && (
            <span className="bg-red-500 text-white text-xs font-bold rounded-full px-1.5 py-0.5 min-w-[20px] text-center">
              {unresolvedCount}
            </span>
          )}
        </button>
        <button
          onClick={() => setTab('servers')}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${tab === 'servers' ? 'border-indigo-600 text-indigo-600' : 'border-transparent text-gray-500 hover:text-gray-700'}`}
        >
          Server Assignment
        </button>
      </div>

      {/* Tab Content */}
      {tab === 'profiles' && renderProfiles()}
      {tab === 'violations' && renderViolations()}
      {tab === 'servers' && renderServerAssignment()}

      {/* Profile modal */}
      {showModal && renderProfileModal()}
    </div>
  )
}
