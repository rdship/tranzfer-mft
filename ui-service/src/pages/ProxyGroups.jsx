import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  getProxyGroups, createProxyGroup, updateProxyGroup, deleteProxyGroup,
} from '../api/proxyGroups'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import toast from 'react-hot-toast'
import {
  PlusIcon, PencilSquareIcon, TrashIcon, ArrowPathIcon,
  ShieldCheckIcon, GlobeAltIcon, BuildingOfficeIcon, CloudIcon,
  SignalIcon, ExclamationTriangleIcon, CheckCircleIcon,
} from '@heroicons/react/24/outline'

// ── Constants ──────────────────────────────────────────────────────────────────

const GROUP_TYPES = ['INTERNAL', 'EXTERNAL', 'PARTNER', 'CLOUD', 'CUSTOM']
const ALL_PROTOCOLS = ['SFTP', 'FTP', 'AS2', 'HTTPS', 'HTTP', 'FTP-TLS']

const TYPE_CFG = {
  INTERNAL: { color: '#22d3ee', icon: BuildingOfficeIcon, bg: 'rgb(8 145 178 / 0.12)',  label: 'Internal Network',   desc: 'Corporate / private LAN / VPN traffic' },
  EXTERNAL: { color: '#f87171', icon: GlobeAltIcon,       bg: 'rgb(239 68 68 / 0.12)',  label: 'Internet-Facing',     desc: 'Public partner integrations / cloud' },
  PARTNER:  { color: '#a78bfa', icon: BuildingOfficeIcon, bg: 'rgb(139 92 246 / 0.12)', label: 'Partner-Dedicated',   desc: 'Dedicated to a specific partner network' },
  CLOUD:    { color: '#34d399', icon: CloudIcon,           bg: 'rgb(52 211 153 / 0.12)', label: 'Cloud',               desc: 'Cloud VPC / hosted environment traffic' },
  CUSTOM:   { color: '#fbbf24', icon: SignalIcon,          bg: 'rgb(251 191 36 / 0.12)', label: 'Custom',              desc: 'User-defined network segment' },
}

const emptyForm = {
  name: '', type: 'INTERNAL', description: '', allowedProtocols: ['SFTP', 'FTP', 'AS2', 'HTTPS'],
  tlsRequired: false, trustedCidrs: '', maxConnectionsPerInstance: 1000, routingPriority: 100,
  notes: '', active: true,
}

// ── Sub-components ─────────────────────────────────────────────────────────────

function InstanceBadge({ inst }) {
  const healthy = inst.healthy !== false
  return (
    <div
      className="flex items-center gap-2 px-3 py-2 rounded-lg text-xs"
      style={{
        background: healthy ? 'rgb(20 83 45 / 0.15)' : 'rgb(127 29 29 / 0.15)',
        border: `1px solid ${healthy ? '#22c55e' : '#ef4444'}30`,
      }}
    >
      <div
        className="w-2 h-2 rounded-full flex-shrink-0"
        style={{ background: healthy ? '#22c55e' : '#ef4444', animation: healthy ? 'pulse-dot 2.2s ease-in-out infinite' : 'none' }}
      />
      <div className="flex-1 min-w-0">
        <p className="font-mono font-semibold truncate" style={{ color: 'rgb(var(--tx-primary))', fontFamily: "'JetBrains Mono',monospace" }}>
          {inst.url || `${inst.host}:${inst.port}`}
        </p>
        <p className="text-[10px] mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
          {inst.instanceId?.substring(0, 8)}…
          {inst.activeConnections != null && ` · ${inst.activeConnections} conns`}
          {inst.lastSeen && ` · seen ${new Date(inst.lastSeen).toLocaleTimeString()}`}
        </p>
      </div>
      {!healthy && <ExclamationTriangleIcon className="w-4 h-4 flex-shrink-0" style={{ color: '#ef4444' }} />}
      {healthy  && <CheckCircleIcon          className="w-4 h-4 flex-shrink-0" style={{ color: '#22c55e' }} />}
    </div>
  )
}

function GroupCard({ group, onEdit, onDelete }) {
  const cfg = TYPE_CFG[group.type] || TYPE_CFG.CUSTOM
  const Icon = cfg.icon
  const instances = group.liveInstances || []
  const isOrphan = group.orphaned

  return (
    <div
      className="rounded-xl overflow-hidden"
      style={{
        background: 'rgb(var(--surface))',
        border: `1.5px solid ${isOrphan ? '#ef4444' : cfg.color}40`,
        boxShadow: instances.length > 0 ? `0 0 20px ${cfg.color}10` : 'none',
      }}
    >
      {/* Header */}
      <div
        className="px-5 py-4 flex items-start justify-between gap-3"
        style={{ background: cfg.bg, borderBottom: `1px solid ${cfg.color}25` }}
      >
        <div className="flex items-center gap-3">
          <div
            className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
            style={{ background: `${cfg.color}20` }}
          >
            <Icon className="w-5 h-5" style={{ color: cfg.color }} />
          </div>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-sm font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
                {group.name}
              </h3>
              <span
                className="text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                style={{ background: `${cfg.color}25`, color: cfg.color }}
              >
                {cfg.label}
              </span>
              {group.tlsRequired && (
                <span className="text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                  style={{ background: 'rgb(20 83 45 / 0.25)', color: '#22c55e' }}>
                  TLS Required
                </span>
              )}
              {isOrphan && (
                <span className="text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                  style={{ background: 'rgb(127 29 29 / 0.25)', color: '#ef4444' }}>
                  Not in DB
                </span>
              )}
            </div>
            <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
              {group.description || cfg.desc}
            </p>
          </div>
        </div>

        {/* Actions */}
        {!isOrphan && (
          <div className="flex items-center gap-1 flex-shrink-0">
            <button onClick={() => onEdit(group)}
              className="p-1.5 rounded-lg transition-colors"
              style={{ color: 'rgb(var(--tx-muted))' }}
              onMouseEnter={e => e.currentTarget.style.color = '#60a5fa'}
              onMouseLeave={e => e.currentTarget.style.color = 'rgb(var(--tx-muted))'}
              title="Edit group" aria-label="Edit group">
              <PencilSquareIcon className="w-4 h-4" />
            </button>
            <button onClick={() => onDelete(group)}
              className="p-1.5 rounded-lg transition-colors"
              style={{ color: 'rgb(var(--tx-muted))' }}
              onMouseEnter={e => e.currentTarget.style.color = '#ef4444'}
              onMouseLeave={e => e.currentTarget.style.color = 'rgb(var(--tx-muted))'}
              title="Delete group" aria-label="Delete group">
              <TrashIcon className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>

      {/* Body */}
      <div className="px-5 py-4 space-y-4">
        {/* Protocols + priority */}
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-1.5 flex-wrap">
            {(group.allowedProtocols || []).map(p => (
              <span key={p}
                className="text-[10px] font-semibold px-2 py-0.5 rounded-full"
                style={{ background: 'rgb(var(--hover))', color: 'rgb(var(--tx-secondary))' }}>
                {p}
              </span>
            ))}
          </div>
          <span className="text-[10px]" style={{ color: 'rgb(var(--tx-muted))' }}>
            Priority {group.routingPriority} · max {(group.maxConnectionsPerInstance || 1000).toLocaleString()} conns/instance
          </span>
          {group.trustedCidrs && (
            <span className="text-[10px] font-mono" style={{ color: 'rgb(var(--tx-muted))' }}>
              CIDRs: {group.trustedCidrs}
            </span>
          )}
        </div>

        {/* Live instances */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <p className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'rgb(var(--tx-muted))' }}>
              Live Instances ({instances.length})
            </p>
            {instances.length === 0 && (
              <span className="text-[10px]" style={{ color: '#f59e0b' }}>No instances running</span>
            )}
          </div>
          {instances.length > 0 ? (
            <div className="space-y-1.5">
              {instances.map((inst, i) => <InstanceBadge key={i} inst={inst} />)}
            </div>
          ) : (
            <div
              className="flex items-center gap-2 px-3 py-2.5 rounded-lg text-xs"
              style={{ background: 'rgb(var(--hover))', border: '1px dashed rgb(var(--border))' }}
            >
              <SignalIcon className="w-4 h-4 flex-shrink-0" style={{ color: 'rgb(var(--tx-muted))' }} />
              <p style={{ color: 'rgb(var(--tx-muted))' }}>
                Start a proxy with <code className="px-1 rounded" style={{ background: 'rgb(var(--hover))', fontSize: '10px' }}>PROXY_GROUP_NAME={group.name}</code> to register an instance.
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ── Create / Edit modal ────────────────────────────────────────────────────────

function GroupModal({ initial, onClose, onSave, saving }) {
  const [form, setForm] = useState(initial || emptyForm)

  const toggleProtocol = (p) => setForm(f => ({
    ...f,
    allowedProtocols: f.allowedProtocols.includes(p)
      ? f.allowedProtocols.filter(x => x !== p)
      : [...f.allowedProtocols, p],
  }))

  return (
    <Modal title={initial?.id ? `Edit: ${initial.name}` : 'Create Proxy Group'} size="lg" onClose={onClose}>
      <form onSubmit={e => { e.preventDefault(); onSave(form) }} className="space-y-4">

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label>Group Name <span className="text-red-400">*</span></label>
            <input
              value={form.name}
              onChange={e => setForm(f => ({ ...f, name: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '') }))}
              required disabled={!!initial?.id}
              placeholder="e.g. internal, external, partner-acme"
            />
            <p className="text-[10px] mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
              Lowercase letters, numbers, hyphens only. Must match the proxy's PROXY_GROUP_NAME env var.
            </p>
          </div>
          <div>
            <label>Type <span className="text-red-400">*</span></label>
            <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))}>
              {GROUP_TYPES.map(t => <option key={t} value={t}>{t} — {TYPE_CFG[t]?.label}</option>)}
            </select>
          </div>
        </div>

        <div>
          <label>Description</label>
          <input value={form.description || ''} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
            placeholder="Corporate network proxy for internal partner integrations" />
        </div>

        <div>
          <label>Allowed Protocols</label>
          <div className="mt-1.5 flex flex-wrap gap-2">
            {ALL_PROTOCOLS.map(p => (
              <label key={p} className="flex items-center gap-1.5 cursor-pointer text-sm mb-0">
                <input type="checkbox" className="w-auto"
                  checked={form.allowedProtocols.includes(p)}
                  onChange={() => toggleProtocol(p)} />
                {p}
              </label>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label>Routing Priority</label>
            <input type="number" min={1} max={1000} value={form.routingPriority}
              onChange={e => setForm(f => ({ ...f, routingPriority: Number(e.target.value) }))} />
            <p className="text-[10px] mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>Lower = preferred when multiple groups match</p>
          </div>
          <div>
            <label>Max Connections/Instance</label>
            <input type="number" min={1} value={form.maxConnectionsPerInstance}
              onChange={e => setForm(f => ({ ...f, maxConnectionsPerInstance: Number(e.target.value) }))} />
          </div>
        </div>

        <div>
          <label>Trusted CIDRs <span className="text-xs font-normal opacity-60">(optional — comma-separated, empty = any source)</span></label>
          <input value={form.trustedCidrs || ''} onChange={e => setForm(f => ({ ...f, trustedCidrs: e.target.value }))}
            placeholder="10.0.0.0/8,192.168.0.0/16" />
        </div>

        <div className="flex items-center gap-6">
          <label className="flex items-center gap-2 cursor-pointer mb-0 text-sm">
            <input type="checkbox" className="w-auto" checked={form.tlsRequired}
              onChange={e => setForm(f => ({ ...f, tlsRequired: e.target.checked }))} />
            TLS Required (enforced for all connections through this group)
          </label>
          <label className="flex items-center gap-2 cursor-pointer mb-0 text-sm">
            <input type="checkbox" className="w-auto" checked={form.active}
              onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />
            Active
          </label>
        </div>

        <div>
          <label>Notes <span className="text-xs font-normal opacity-60">(optional)</span></label>
          <textarea value={form.notes || ''} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))}
            rows={2} placeholder="Operational notes..." />
        </div>

        <div className="flex gap-3 justify-end">
          <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={saving}>
            {saving ? 'Saving…' : initial?.id ? 'Save Changes' : 'Create Group'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// ── Main page ──────────────────────────────────────────────────────────────────

export default function ProxyGroups() {
  const qc = useQueryClient()
  const [showModal, setShowModal] = useState(false)
  const [editing, setEditing]     = useState(null)
  const [deleteTarget, setDeleteTarget] = useState(null)

  const { data: groups = [], isLoading, refetch, isFetching } = useQuery({
    queryKey: ['proxy-groups'],
    queryFn: getProxyGroups,
    refetchInterval: 30_000,
    staleTime: 25_000,
  })

  const saveMut = useMutation({
    mutationFn: (form) => editing
      ? updateProxyGroup(editing.id, form)
      : createProxyGroup(form),
    onSuccess: () => {
      qc.invalidateQueries(['proxy-groups'])
      setShowModal(false)
      setEditing(null)
      toast.success(editing ? 'Proxy group updated' : 'Proxy group created')
    },
    onError: err => toast.error(err.response?.data?.message || err.response?.data?.error || 'Save failed'),
  })

  const deleteMut = useMutation({
    mutationFn: (id) => deleteProxyGroup(id),
    onSuccess: () => { qc.invalidateQueries(['proxy-groups']); toast.success('Proxy group deactivated') },
    onError: () => toast.error('Delete failed'),
  })

  const openCreate = () => { setEditing(null); setShowModal(true) }
  const openEdit   = (g) => { setEditing(g);   setShowModal(true) }

  // Summary counts
  const totalGroups    = groups.filter(g => !g.orphaned).length
  const totalInstances = groups.reduce((s, g) => s + (g.instanceCount || 0), 0)
  const openCount      = groups.filter(g => (g.instanceCount || 0) > 0).length
  const orphans        = groups.filter(g => g.orphaned).length

  return (
    <div className="space-y-6">

      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <ShieldCheckIcon className="w-5 h-5" style={{ color: 'rgb(var(--accent))' }} />
            <h1 className="text-lg font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
              Proxy Groups
            </h1>
          </div>
          <p className="text-sm" style={{ color: 'rgb(var(--tx-secondary))' }}>
            Named groups of DMZ proxy instances — route traffic by network zone · updates every 30 s
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => refetch()}
            disabled={isFetching}
            className="p-2 rounded-lg transition-colors"
            style={{ color: 'rgb(var(--tx-muted))', background: 'rgb(var(--hover))' }}
          >
            <ArrowPathIcon className={`w-4 h-4 ${isFetching ? 'animate-spin' : ''}`} />
          </button>
          <button onClick={openCreate} className="btn-primary">
            <PlusIcon className="w-4 h-4" /> Add Group
          </button>
        </div>
      </div>

      {/* Summary pills */}
      <div className="flex items-center gap-4 flex-wrap">
        {[
          { label: `${totalGroups} group${totalGroups !== 1 ? 's' : ''}`,         color: 'rgb(var(--accent))' },
          { label: `${totalInstances} live instance${totalInstances !== 1 ? 's' : ''}`, color: '#22c55e' },
          { label: `${openCount} group${openCount !== 1 ? 's' : ''} online`,       color: '#22d3ee' },
          ...(orphans > 0 ? [{ label: `${orphans} orphaned`, color: '#f87171' }] : []),
        ].map(({ label, color }) => (
          <div key={label}
            className="px-3 py-1 rounded-full text-xs font-semibold"
            style={{ background: `${color}18`, color }}>
            {label}
          </div>
        ))}
      </div>

      {/* How to start a proxy with a specific group — compact callout */}
      <div
        className="flex items-start gap-3 px-4 py-3 rounded-xl text-xs"
        style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}
      >
        <SignalIcon className="w-4 h-4 flex-shrink-0 mt-0.5" style={{ color: 'rgb(var(--accent))' }} />
        <div>
          <p className="font-semibold mb-0.5" style={{ color: 'rgb(var(--tx-primary))' }}>
            Assigning a proxy instance to a group
          </p>
          <p style={{ color: 'rgb(var(--tx-secondary))' }}>
            Set <code className="px-1 py-0.5 rounded" style={{ background: 'rgb(var(--hover))' }}>PROXY_GROUP_NAME=&lt;groupName&gt;</code> and{' '}
            <code className="px-1 py-0.5 rounded" style={{ background: 'rgb(var(--hover))' }}>PROXY_GROUP_TYPE=&lt;TYPE&gt;</code> on the DMZ proxy container.
            The instance registers in Redis on startup and appears below within seconds.
            Scale with: <code className="px-1 py-0.5 rounded" style={{ background: 'rgb(var(--hover))' }}>docker compose --profile external-proxy up -d</code>
          </p>
        </div>
      </div>

      {/* Group cards */}
      {isLoading ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {[1, 2].map(i => (
            <div key={i} className="h-48 rounded-xl animate-pulse" style={{ background: 'rgb(var(--surface))' }} />
          ))}
        </div>
      ) : groups.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <ShieldCheckIcon className="w-12 h-12 mb-3" style={{ color: 'rgb(var(--tx-muted))' }} />
          <p className="font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>No proxy groups yet</p>
          <p className="text-sm mt-1" style={{ color: 'rgb(var(--tx-secondary))' }}>
            Create a group and start a DMZ proxy with PROXY_GROUP_NAME set to that name.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {groups.map((g, i) => (
            <GroupCard key={g.id || g.name || i} group={g}
              onEdit={openEdit}
              onDelete={(g) => setDeleteTarget(g)}
            />
          ))}
        </div>
      )}

      {/* Create / Edit modal */}
      {showModal && (
        <GroupModal
          initial={editing}
          onClose={() => { setShowModal(false); setEditing(null) }}
          onSave={(form) => saveMut.mutate(form)}
          saving={saveMut.isPending}
        />
      )}

      <ConfirmDialog
        open={!!deleteTarget}
        variant="danger"
        title="Delete proxy group?"
        message={deleteTarget ? `Delete proxy group "${deleteTarget.name}"?` : ''}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        loading={deleteMut.isPending}
        onConfirm={() => { deleteMut.mutate(deleteTarget.id); setDeleteTarget(null) }}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
