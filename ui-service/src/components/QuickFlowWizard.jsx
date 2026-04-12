import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { configApi, onboardingApi } from '../api/client'
import Modal from './Modal'
import toast from 'react-hot-toast'
import {
  BoltIcon, ArrowRightIcon, ShieldCheckIcon, LockClosedIcon,
  ArchiveBoxIcon, DocumentTextIcon, CheckCircleIcon,
} from '@heroicons/react/24/outline'

const STEP_TYPES = [
  { id: 'SCREEN', label: 'Screen (DLP/Sanctions)', icon: ShieldCheckIcon, category: 'security' },
  { id: 'CHECKSUM_VERIFY', label: 'Verify Checksum', icon: CheckCircleIcon, category: 'security' },
  { id: 'ENCRYPT_PGP', label: 'Encrypt (PGP)', icon: LockClosedIcon, category: 'security' },
  { id: 'ENCRYPT_AES', label: 'Encrypt (AES)', icon: LockClosedIcon, category: 'security' },
  { id: 'DECRYPT_PGP', label: 'Decrypt (PGP)', icon: LockClosedIcon, category: 'security' },
  { id: 'DECRYPT_AES', label: 'Decrypt (AES)', icon: LockClosedIcon, category: 'security' },
  { id: 'COMPRESS_GZIP', label: 'Compress (GZIP)', icon: ArchiveBoxIcon, category: 'transform' },
  { id: 'COMPRESS_ZIP', label: 'Compress (ZIP)', icon: ArchiveBoxIcon, category: 'transform' },
  { id: 'DECOMPRESS_GZIP', label: 'Decompress (GZIP)', icon: ArchiveBoxIcon, category: 'transform' },
  { id: 'CONVERT_EDI', label: 'Convert EDI', icon: DocumentTextIcon, category: 'transform' },
  { id: 'RENAME', label: 'Rename File', icon: DocumentTextIcon, category: 'transform' },
]

export default function QuickFlowWizard({ open, onClose }) {
  const qc = useQueryClient()
  const [form, setForm] = useState({
    source: '', filenamePattern: '', protocol: '', direction: 'INBOUND',
    name: '', actions: [], encryptionKeyAlias: '', ediTargetFormat: 'JSON',
    deliverTo: '', deliveryPath: '/outbox',
    onError: 'RETRY', retryCount: 3, notifyOnFailure: true, priority: 50,
  })

  const { data: partners = [] } = useQuery({
    queryKey: ['partners-quick'], enabled: open,
    queryFn: () => onboardingApi.get('/api/partners').then(r => r.data),
  })
  const { data: accounts = [] } = useQuery({
    queryKey: ['accounts-quick'], enabled: open,
    queryFn: () => onboardingApi.get('/api/accounts?size=500').then(r => r.data),
  })

  const createMut = useMutation({
    mutationFn: (data) => configApi.post('/api/flows/quick', data).then(r => r.data),
    onSuccess: (flow) => {
      toast.success(`Flow "${flow.name}" created with ${flow.steps.length} steps`)
      qc.invalidateQueries(['flows'])
      onClose()
    },
    onError: (err) => toast.error(err.response?.data?.message || err.response?.data?.error || 'Failed to create flow'),
  })

  const toggleAction = (id) => {
    setForm(f => ({
      ...f,
      actions: f.actions.includes(id) ? f.actions.filter(a => a !== id) : [...f.actions, id],
    }))
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    if (!form.filenamePattern && !form.source) {
      toast.error('Specify at least a source or filename pattern')
      return
    }
    createMut.mutate({
      ...form,
      source: form.source || null,
      filenamePattern: form.filenamePattern || null,
      protocol: form.protocol || null,
    })
  }

  if (!open) return null

  return (
    <Modal title="Quick Flow — 30 seconds to production" size="lg" onClose={onClose}>
      <form onSubmit={handleSubmit} className="space-y-6">

        {/* ── WHEN ── */}
        <div>
          <h3 className="text-sm font-bold text-primary mb-2 flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-blue-600 text-white flex items-center justify-center text-xs font-bold">1</span>
            WHEN — What triggers this flow?
          </h3>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-medium text-secondary">Source (partner or account)</label>
              <select value={form.source} onChange={e => setForm(f => ({ ...f, source: e.target.value }))} className="mt-1">
                <option value="">Any source</option>
                <optgroup label="Partners">
                  {partners.map(p => <option key={p.id} value={p.slug || p.name}>{p.name}</option>)}
                </optgroup>
                <optgroup label="Accounts">
                  {accounts.slice(0, 50).map(a => <option key={a.id} value={a.username}>{a.username} ({a.protocol})</option>)}
                </optgroup>
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Filename pattern (regex)</label>
              <input value={form.filenamePattern} onChange={e => setForm(f => ({ ...f, filenamePattern: e.target.value }))}
                placeholder=".*\.csv" className="mt-1 font-mono" />
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Protocol</label>
              <select value={form.protocol} onChange={e => setForm(f => ({ ...f, protocol: e.target.value }))} className="mt-1">
                <option value="">Any</option>
                <option>SFTP</option><option>FTP</option><option>FTP_WEB</option><option>AS2</option>
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Priority (lower = matched first)</label>
              <input type="number" value={form.priority} onChange={e => setForm(f => ({ ...f, priority: +e.target.value }))}
                min={1} max={999} className="mt-1" />
            </div>
          </div>
        </div>

        {/* ── DO ── */}
        <div>
          <h3 className="text-sm font-bold text-primary mb-2 flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-green-600 text-white flex items-center justify-center text-xs font-bold">2</span>
            DO — What processing steps?
          </h3>
          <div className="grid grid-cols-3 gap-2">
            {STEP_TYPES.map(step => {
              const Icon = step.icon
              const active = form.actions.includes(step.id)
              return (
                <button key={step.id} type="button" onClick={() => toggleAction(step.id)}
                  className={`flex items-center gap-2 px-3 py-2 rounded-lg border text-xs font-medium transition-all ${
                    active ? 'bg-blue-50 border-blue-300 text-blue-700' : 'border-border text-secondary hover:border-blue-200 hover:bg-blue-50/50'
                  }`}>
                  <Icon className="w-4 h-4 flex-shrink-0" />
                  {step.label}
                  {active && <span className="ml-auto text-blue-500 font-bold">{form.actions.indexOf(step.id) + 1}</span>}
                </button>
              )
            })}
          </div>
          {form.actions.includes('CONVERT_EDI') && (
            <div className="mt-2">
              <label className="text-xs font-medium text-secondary">EDI output format</label>
              <select value={form.ediTargetFormat} onChange={e => setForm(f => ({ ...f, ediTargetFormat: e.target.value }))} className="mt-1 w-32">
                <option>JSON</option><option>XML</option><option>CSV</option><option>YAML</option>
              </select>
            </div>
          )}
          {(form.actions.includes('ENCRYPT_PGP') || form.actions.includes('ENCRYPT_AES')) && (
            <div className="mt-2">
              <label className="text-xs font-medium text-secondary">Encryption key alias (from keystore)</label>
              <input value={form.encryptionKeyAlias} onChange={e => setForm(f => ({ ...f, encryptionKeyAlias: e.target.value }))}
                placeholder="partner-acme-pgp" className="mt-1 font-mono w-64" />
            </div>
          )}
          {form.actions.length > 0 && (
            <p className="text-xs text-muted mt-2">
              Pipeline: {form.actions.map((a, i) => <span key={a}>{i > 0 && <ArrowRightIcon className="w-3 h-3 inline mx-1" />}{a}</span>)}
            </p>
          )}
        </div>

        {/* ── DELIVER TO ── */}
        <div>
          <h3 className="text-sm font-bold text-primary mb-2 flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-purple-600 text-white flex items-center justify-center text-xs font-bold">3</span>
            DELIVER TO — Where does the file go?
          </h3>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-medium text-secondary">Destination (account or partner)</label>
              <select value={form.deliverTo} onChange={e => setForm(f => ({ ...f, deliverTo: e.target.value }))} className="mt-1">
                <option value="">No delivery (processing only)</option>
                <optgroup label="Accounts">
                  {accounts.slice(0, 50).map(a => <option key={a.id} value={a.username}>{a.username} ({a.protocol})</option>)}
                </optgroup>
                <optgroup label="Partners">
                  {partners.map(p => <option key={p.id} value={p.slug || p.name}>{p.name}</option>)}
                </optgroup>
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Destination path</label>
              <input value={form.deliveryPath} onChange={e => setForm(f => ({ ...f, deliveryPath: e.target.value }))}
                placeholder="/outbox" className="mt-1 font-mono" />
            </div>
          </div>
        </div>

        {/* ── ERROR HANDLING ── */}
        <div>
          <h3 className="text-sm font-bold text-primary mb-2 flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-red-600 text-white flex items-center justify-center text-xs font-bold">4</span>
            IF ERROR — What happens on failure?
          </h3>
          <div className="flex items-center gap-4">
            <select value={form.onError} onChange={e => setForm(f => ({ ...f, onError: e.target.value }))} className="w-40">
              <option value="RETRY">Retry</option>
              <option value="QUARANTINE">Quarantine</option>
              <option value="NOTIFY">Notify only</option>
              <option value="FAIL">Fail immediately</option>
            </select>
            {form.onError === 'RETRY' && (
              <label className="flex items-center gap-1 text-xs">
                Retries: <input type="number" value={form.retryCount} onChange={e => setForm(f => ({ ...f, retryCount: +e.target.value }))}
                  min={1} max={10} className="w-16" />
              </label>
            )}
            <label className="flex items-center gap-1.5 text-xs cursor-pointer">
              <input type="checkbox" checked={form.notifyOnFailure} onChange={e => setForm(f => ({ ...f, notifyOnFailure: e.target.checked }))} />
              Notify on failure
            </label>
          </div>
        </div>

        {/* ── Name override ── */}
        <div>
          <label className="text-xs font-medium text-secondary">Flow name (auto-generated if blank)</label>
          <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
            placeholder="Auto-generated from source + pattern" className="mt-1" />
        </div>

        {/* ── Submit ── */}
        <div className="flex justify-end gap-3 pt-2 border-t border-border">
          <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary flex items-center gap-2" disabled={createMut.isPending}>
            <BoltIcon className="w-4 h-4" />
            {createMut.isPending ? 'Creating...' : 'Create Flow'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
