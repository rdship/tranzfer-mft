import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { format } from 'date-fns'
import Modal from '../components/Modal'
import * as api from '../api/ediTraining'
import {
  AcademicCapIcon, BeakerIcon, DocumentDuplicateIcon,
  PlusIcon, TrashIcon, ArrowPathIcon, CheckCircleIcon,
  XMarkIcon, ChevronDownIcon, ChevronUpIcon,
  ClipboardDocumentCheckIcon, ExclamationTriangleIcon,
  SparklesIcon, CpuChipIcon, MapIcon
} from '@heroicons/react/24/outline'

// ── Constants ──────────────────────────────────────────────────────────────

const TABS = [
  { key: 'sessions', label: 'Training Sessions', icon: AcademicCapIcon },
  { key: 'samples', label: 'Samples & Maps', icon: DocumentDuplicateIcon },
  { key: 'corrections', label: 'Corrections', icon: ClipboardDocumentCheckIcon },
]

const STATUS_BADGE = {
  PENDING: 'badge badge-yellow', TRAINING: 'badge badge-blue',
  COMPLETED: 'badge badge-green', FAILED: 'badge badge-red',
}

const CORRECTION_BADGE = {
  PENDING: 'badge badge-yellow', APPROVED: 'badge badge-green', REJECTED: 'badge badge-red',
}

const FORMAT_OPTIONS = ['X12', 'EDIFACT', 'HL7', 'SWIFT', 'JSON', 'XML', 'CSV', 'PEPPOL']

// ── Helpers ────────────────────────────────────────────────────────────────

function Spinner() {
  return <div className="flex justify-center py-12"><div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" /></div>
}

function EmptyState({ icon: Icon, message }) {
  return (
    <div className="text-center py-12 text-secondary">
      <Icon className="w-10 h-10 mx-auto mb-3 text-muted" />
      <p>{message}</p>
    </div>
  )
}

function StatCard({ label, value, color = 'text-white', icon: Icon }) {
  return (
    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
      <div className="flex items-center gap-2 mb-1">
        {Icon && <Icon className="w-4 h-4 text-muted" />}
        <span className="text-muted text-xs">{label}</span>
      </div>
      <div className={`text-2xl font-bold ${color}`}>{value ?? '—'}</div>
    </div>
  )
}

function AccuracyBar({ value }) {
  if (value == null) return <span className="text-muted text-xs">N/A</span>
  const pct = Math.round(value * 100)
  const color = pct >= 90 ? 'bg-green-500' : pct >= 70 ? 'bg-yellow-500' : 'bg-red-500'
  return (
    <div className="flex items-center gap-2">
      <div className="w-20 bg-gray-700 rounded-full h-1.5">
        <div className={`${color} h-1.5 rounded-full`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs text-gray-300">{pct}%</span>
    </div>
  )
}

function LossChart({ metrics }) {
  if (!metrics?.lossHistory?.length) return null
  const losses = metrics.lossHistory
  const max = Math.max(...losses, 0.01)
  return (
    <div className="mt-3">
      <div className="text-xs text-muted mb-1">Loss Curve</div>
      <div className="flex items-end gap-px h-16 bg-gray-900 rounded p-1">
        {losses.map((loss, i) => (
          <div key={i} className="flex-1 bg-blue-500/60 rounded-t transition-all"
               style={{ height: `${(loss / max) * 100}%` }}
               title={`Epoch ${i + 1}: ${loss.toFixed(4)}`} />
        ))}
      </div>
    </div>
  )
}

// ── Tab 1: Training Sessions ───────────────────────────────────────────────

function SessionsTab() {
  const queryClient = useQueryClient()
  const [expandedId, setExpandedId] = useState(null)
  const [showNewModal, setShowNewModal] = useState(false)
  const [showQuickTrain, setShowQuickTrain] = useState(false)
  const [trainConfig, setTrainConfig] = useState({ sampleCount: 100, epochs: 10, learningRate: 0.001 })
  const [quickData, setQuickData] = useState({ sourceEdi: '', targetJson: '', formatType: 'X12' })

  const { data: sessions, isLoading } = useQuery({
    queryKey: ['edi-training-sessions'],
    queryFn: api.getTrainingSessions,
    meta: { silent: true }, refetchInterval: 10000,
  })

  const { data: health } = useQuery({
    queryKey: ['edi-training-health'],
    queryFn: api.getTrainingHealth,
    meta: { silent: true }, refetchInterval: 30000,
  })

  const train = useMutation({
    mutationFn: api.trainModel,
    onSuccess: () => {
      toast.success('Training started')
      setShowNewModal(false)
      queryClient.invalidateQueries({ queryKey: ['edi-training-sessions'] })
    },
    onError: (e) => toast.error('Training failed: ' + (e.response?.data?.message || e.message)),
  })

  const quick = useMutation({
    mutationFn: api.quickTrain,
    onSuccess: (data) => {
      toast.success(`Quick train complete — accuracy: ${Math.round((data.accuracy || 0) * 100)}%`)
      setShowQuickTrain(false)
      setQuickData({ sourceEdi: '', targetJson: '', formatType: 'X12' })
      queryClient.invalidateQueries({ queryKey: ['edi-training-sessions'] })
    },
    onError: (e) => toast.error('Quick train failed: ' + (e.response?.data?.message || e.message)),
  })

  const sessionList = Array.isArray(sessions) ? sessions : sessions?.content || []

  const toggleRow = (id) => setExpandedId(expandedId === id ? null : id)

  if (isLoading) return <Spinner />

  return (
    <div className="space-y-6">
      {/* Health stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Total Sessions" value={health?.totalSessions ?? sessionList.length} icon={AcademicCapIcon} />
        <StatCard label="Active Training" value={health?.activeTraining ?? 0} color="text-blue-400" icon={CpuChipIcon} />
        <StatCard label="Avg Accuracy" value={health?.avgAccuracy != null ? `${Math.round(health.avgAccuracy * 100)}%` : '—'} color="text-green-400" icon={SparklesIcon} />
        <StatCard label="Total Samples" value={health?.totalSamples ?? '—'} icon={DocumentDuplicateIcon} />
      </div>

      {/* Actions */}
      <div className="flex gap-3">
        <button onClick={() => setShowNewModal(true)} className="btn-primary flex items-center gap-1.5">
          <PlusIcon className="w-4 h-4" /> New Training
        </button>
        <button onClick={() => setShowQuickTrain(true)} className="btn-secondary flex items-center gap-1.5">
          <SparklesIcon className="w-4 h-4" /> Quick Train
        </button>
      </div>

      {/* Sessions table */}
      {sessionList.length === 0 ? (
        <EmptyState icon={AcademicCapIcon} message="No training sessions yet. Start one to teach the AI." />
      ) : (
        <div className="bg-gray-800 rounded-lg border border-gray-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-700 text-muted text-xs">
                <th className="px-4 py-2 text-left w-8" />
                <th className="px-4 py-2 text-left">Session ID</th>
                <th className="px-4 py-2 text-left">Status</th>
                <th className="px-4 py-2 text-left">Samples</th>
                <th className="px-4 py-2 text-left">Accuracy</th>
                <th className="px-4 py-2 text-left">Started</th>
                <th className="px-4 py-2 text-left">Duration</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-700">
              {sessionList.map((s) => (
                <SessionRow key={s.id} session={s} expanded={expandedId === s.id} onToggle={() => toggleRow(s.id)} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* New Training Modal */}
      {showNewModal && (
        <Modal title="New Training Session" onClose={() => setShowNewModal(false)}>
          <div className="space-y-4">
            <div>
              <label className="block text-sm text-gray-300 mb-1">Sample Count</label>
              <input type="number" value={trainConfig.sampleCount}
                     onChange={e => setTrainConfig(c => ({ ...c, sampleCount: +e.target.value }))}
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Epochs</label>
              <input type="number" value={trainConfig.epochs}
                     onChange={e => setTrainConfig(c => ({ ...c, epochs: +e.target.value }))}
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Learning Rate</label>
              <input type="number" step="0.0001" value={trainConfig.learningRate}
                     onChange={e => setTrainConfig(c => ({ ...c, learningRate: +e.target.value }))}
                     className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm" />
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <button onClick={() => setShowNewModal(false)} className="btn-secondary">Cancel</button>
              <button onClick={() => train.mutate(trainConfig)} disabled={train.isPending}
                      className="btn-primary flex items-center gap-1.5">
                {train.isPending && <ArrowPathIcon className="w-4 h-4 animate-spin" />}
                Start Training
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* Quick Train Modal */}
      {showQuickTrain && (
        <Modal title="Quick Train — Upload Sample Pair" onClose={() => setShowQuickTrain(false)} size="lg">
          <div className="space-y-4">
            <div>
              <label className="block text-sm text-gray-300 mb-1">Format Type</label>
              <select value={quickData.formatType}
                      onChange={e => setQuickData(d => ({ ...d, formatType: e.target.value }))}
                      className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm">
                {FORMAT_OPTIONS.map(f => <option key={f} value={f}>{f}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Source EDI</label>
              <textarea value={quickData.sourceEdi}
                        onChange={e => setQuickData(d => ({ ...d, sourceEdi: e.target.value }))}
                        rows={5} placeholder="Paste source EDI content..."
                        className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-xs font-mono" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Target JSON</label>
              <textarea value={quickData.targetJson}
                        onChange={e => setQuickData(d => ({ ...d, targetJson: e.target.value }))}
                        rows={5} placeholder="Paste expected JSON output..."
                        className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-xs font-mono" />
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <button onClick={() => setShowQuickTrain(false)} className="btn-secondary">Cancel</button>
              <button onClick={() => quick.mutate(quickData)}
                      disabled={quick.isPending || !quickData.sourceEdi || !quickData.targetJson}
                      className="btn-primary flex items-center gap-1.5">
                {quick.isPending && <ArrowPathIcon className="w-4 h-4 animate-spin" />}
                Train on Pair
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

function SessionRow({ session: s, expanded, onToggle }) {
  const { data: detail } = useQuery({
    queryKey: ['edi-training-session', s.id],
    queryFn: () => api.getTrainingSession(s.id),
    enabled: expanded,
  })

  return (
    <>
      <tr className="hover:bg-gray-750 cursor-pointer" onClick={onToggle}>
        <td className="px-4 py-2">
          {expanded ? <ChevronUpIcon className="w-4 h-4 text-muted" /> : <ChevronDownIcon className="w-4 h-4 text-muted" />}
        </td>
        <td className="px-4 py-2 text-gray-300 font-mono text-xs">{s.id?.substring(0, 12) || '—'}</td>
        <td className="px-4 py-2"><span className={STATUS_BADGE[s.status] || 'badge badge-gray'}>{s.status || 'UNKNOWN'}</span></td>
        <td className="px-4 py-2 text-gray-300">{s.sampleCount ?? s.samplesCount ?? '—'}</td>
        <td className="px-4 py-2"><AccuracyBar value={s.accuracy} /></td>
        <td className="px-4 py-2 text-secondary text-xs">
          {s.startedAt ? format(new Date(s.startedAt), 'MMM d, HH:mm') : '—'}
        </td>
        <td className="px-4 py-2 text-secondary text-xs">{s.duration ? `${s.duration}s` : '—'}</td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={7} className="px-6 py-4 bg-gray-850 border-t border-gray-700">
            {detail ? (
              <div className="space-y-3">
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                  <div><span className="text-muted">Epochs:</span> <span className="text-gray-300">{detail.epochs ?? '—'}</span></div>
                  <div><span className="text-muted">Learning Rate:</span> <span className="text-gray-300">{detail.learningRate ?? '—'}</span></div>
                  <div><span className="text-muted">Final Loss:</span> <span className="text-gray-300">{detail.finalLoss?.toFixed(4) ?? '—'}</span></div>
                  <div><span className="text-muted">Format Pairs:</span> <span className="text-gray-300">{detail.formatPairs?.join(', ') || '—'}</span></div>
                </div>
                <LossChart metrics={detail.metrics || detail} />
                {detail.error && (
                  <div className="bg-red-900/20 border border-red-800 rounded p-3 text-xs text-red-300">
                    <ExclamationTriangleIcon className="w-4 h-4 inline mr-1" />
                    {detail.error}
                  </div>
                )}
              </div>
            ) : (
              <div className="text-center text-muted text-sm py-4">Loading details...</div>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

// ── Tab 2: Samples & Maps ──────────────────────────────────────────────────

function SamplesTab() {
  const queryClient = useQueryClient()
  const [showAddSample, setShowAddSample] = useState(false)
  const [sampleForm, setSampleForm] = useState({ sourceContent: '', targetContent: '', sourceFormat: 'X12', targetFormat: 'JSON' })
  const [sampleFilter, setSampleFilter] = useState('')

  const { data: samples, isLoading: samplesLoading } = useQuery({
    queryKey: ['edi-training-samples', sampleFilter],
    queryFn: () => api.getSamples(sampleFilter ? { format: sampleFilter } : {}),
    meta: { silent: true }, refetchInterval: 15000,
  })

  const { data: maps, isLoading: mapsLoading } = useQuery({
    queryKey: ['edi-training-maps'],
    queryFn: api.getMaps,
    meta: { silent: true }, refetchInterval: 30000,
  })

  const addSample = useMutation({
    mutationFn: api.addSample,
    onSuccess: () => {
      toast.success('Sample added')
      setShowAddSample(false)
      setSampleForm({ sourceContent: '', targetContent: '', sourceFormat: 'X12', targetFormat: 'JSON' })
      queryClient.invalidateQueries({ queryKey: ['edi-training-samples'] })
    },
    onError: (e) => toast.error('Failed to add sample: ' + (e.response?.data?.message || e.message)),
  })

  const removeSample = useMutation({
    mutationFn: api.deleteSample,
    onSuccess: () => {
      toast.success('Sample deleted')
      queryClient.invalidateQueries({ queryKey: ['edi-training-samples'] })
    },
    onError: (e) => toast.error('Delete failed: ' + (e.response?.data?.message || e.message)),
  })

  const [confirmDelete, setConfirmDelete] = useState(null)
  const sampleList = Array.isArray(samples) ? samples : samples?.content || []
  const mapList = Array.isArray(maps) ? maps : maps?.content || []

  return (
    <div className="space-y-8">
      {/* ── Samples Section ── */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-white flex items-center gap-2">
            <DocumentDuplicateIcon className="w-5 h-5 text-blue-400" /> Training Samples
          </h3>
          <div className="flex gap-3">
            <select value={sampleFilter} onChange={e => setSampleFilter(e.target.value)}
                    className="bg-gray-800 text-gray-300 text-sm border border-gray-600 rounded px-3 py-1.5">
              <option value="">All Formats</option>
              {FORMAT_OPTIONS.map(f => <option key={f} value={f}>{f}</option>)}
            </select>
            <button onClick={() => setShowAddSample(true)} className="btn-primary flex items-center gap-1.5 text-sm">
              <PlusIcon className="w-4 h-4" /> Add Sample
            </button>
          </div>
        </div>

        {samplesLoading ? <Spinner /> : sampleList.length === 0 ? (
          <EmptyState icon={DocumentDuplicateIcon} message="No training samples. Add source/target pairs to begin." />
        ) : (
          <div className="bg-gray-800 rounded-lg border border-gray-700 overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-700 text-muted text-xs">
                  <th className="px-4 py-2 text-left">ID</th>
                  <th className="px-4 py-2 text-left">Source Format</th>
                  <th className="px-4 py-2 text-left">Target Format</th>
                  <th className="px-4 py-2 text-left">Preview</th>
                  <th className="px-4 py-2 text-left">Created</th>
                  <th className="px-4 py-2 text-left">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-700">
                {sampleList.map(s => (
                  <tr key={s.id} className="hover:bg-gray-750">
                    <td className="px-4 py-2 text-gray-300 font-mono text-xs">{s.id?.toString().substring(0, 10) || '—'}</td>
                    <td className="px-4 py-2"><span className="badge badge-blue">{s.sourceFormat || '—'}</span></td>
                    <td className="px-4 py-2"><span className="badge badge-teal">{s.targetFormat || '—'}</span></td>
                    <td className="px-4 py-2 text-gray-400 text-xs max-w-xs truncate font-mono">
                      {(s.sourceContent || s.preview || '').substring(0, 80)}...
                    </td>
                    <td className="px-4 py-2 text-secondary text-xs">
                      {s.createdAt ? format(new Date(s.createdAt), 'MMM d, HH:mm') : '—'}
                    </td>
                    <td className="px-4 py-2">
                      {confirmDelete === s.id ? (
                        <span className="flex items-center gap-1">
                          <button onClick={() => { removeSample.mutate(s.id); setConfirmDelete(null) }}
                                  className="text-xs px-2 py-0.5 bg-red-600/20 text-red-400 rounded hover:bg-red-600/30">Confirm</button>
                          <button onClick={() => setConfirmDelete(null)} className="text-xs text-muted hover:text-gray-300">
                            <XMarkIcon className="w-3.5 h-3.5" />
                          </button>
                        </span>
                      ) : (
                        <button onClick={() => setConfirmDelete(s.id)}
                                className="text-xs px-2 py-0.5 bg-red-600/10 text-red-400 rounded hover:bg-red-600/20">
                          <TrashIcon className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Maps Section ── */}
      <div>
        <h3 className="text-lg font-semibold text-white flex items-center gap-2 mb-4">
          <MapIcon className="w-5 h-5 text-green-400" /> Trained Mapping Models
        </h3>

        {mapsLoading ? <Spinner /> : mapList.length === 0 ? (
          <EmptyState icon={MapIcon} message="No trained maps yet. Complete a training session first." />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {mapList.map(m => (
              <div key={m.id} className="bg-gray-800 rounded-lg border border-gray-700 p-4">
                <div className="flex items-center justify-between mb-3">
                  <span className="text-gray-200 font-medium text-sm truncate">{m.name || m.id?.substring(0, 16)}</span>
                  <AccuracyBar value={m.accuracy} />
                </div>
                <div className="space-y-1.5 text-xs">
                  <div className="flex gap-1.5 flex-wrap">
                    {(m.formatPairs || []).map((pair, i) => (
                      <span key={i} className="badge badge-purple">{pair}</span>
                    ))}
                    {(!m.formatPairs?.length && m.sourceFormat) && (
                      <span className="badge badge-purple">{m.sourceFormat} → {m.targetFormat}</span>
                    )}
                  </div>
                  <div className="text-muted">
                    Samples: {m.sampleCount ?? '—'} | Version: {m.version ?? '1'}
                  </div>
                  <div className="text-secondary">
                    Last used: {m.lastUsedAt ? format(new Date(m.lastUsedAt), 'MMM d, HH:mm') : 'Never'}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Add Sample Modal */}
      {showAddSample && (
        <Modal title="Add Training Sample" onClose={() => setShowAddSample(false)} size="lg">
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm text-gray-300 mb-1">Source Format</label>
                <select value={sampleForm.sourceFormat}
                        onChange={e => setSampleForm(f => ({ ...f, sourceFormat: e.target.value }))}
                        className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm">
                  {FORMAT_OPTIONS.map(f => <option key={f} value={f}>{f}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-sm text-gray-300 mb-1">Target Format</label>
                <select value={sampleForm.targetFormat}
                        onChange={e => setSampleForm(f => ({ ...f, targetFormat: e.target.value }))}
                        className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-sm">
                  {FORMAT_OPTIONS.map(f => <option key={f} value={f}>{f}</option>)}
                </select>
              </div>
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Source EDI Content</label>
              <textarea value={sampleForm.sourceContent}
                        onChange={e => setSampleForm(f => ({ ...f, sourceContent: e.target.value }))}
                        rows={6} placeholder="Paste source EDI document..."
                        className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-xs font-mono" />
            </div>
            <div>
              <label className="block text-sm text-gray-300 mb-1">Target Content</label>
              <textarea value={sampleForm.targetContent}
                        onChange={e => setSampleForm(f => ({ ...f, targetContent: e.target.value }))}
                        rows={6} placeholder="Paste expected output..."
                        className="w-full bg-gray-800 text-gray-200 border border-gray-600 rounded px-3 py-2 text-xs font-mono" />
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <button onClick={() => setShowAddSample(false)} className="btn-secondary">Cancel</button>
              <button onClick={() => addSample.mutate(sampleForm)}
                      disabled={addSample.isPending || !sampleForm.sourceContent || !sampleForm.targetContent}
                      className="btn-primary flex items-center gap-1.5">
                {addSample.isPending && <ArrowPathIcon className="w-4 h-4 animate-spin" />}
                Add Sample
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

// ── Tab 3: Corrections ─────────────────────────────────────────────────────

function CorrectionsTab() {
  const queryClient = useQueryClient()
  const [expandedId, setExpandedId] = useState(null)

  const { data: corrections, isLoading } = useQuery({
    queryKey: ['edi-correction-sessions'],
    queryFn: api.getCorrectionSessions,
    meta: { silent: true }, refetchInterval: 15000,
  })

  const approve = useMutation({
    mutationFn: api.approveCorrection,
    onSuccess: () => {
      toast.success('Correction approved — model will retrain')
      queryClient.invalidateQueries({ queryKey: ['edi-correction-sessions'] })
    },
    onError: (e) => toast.error('Approve failed: ' + (e.response?.data?.message || e.message)),
  })

  const reject = useMutation({
    mutationFn: (sessionId) => api.submitCorrection(sessionId, { action: 'REJECT' }),
    onSuccess: () => {
      toast.success('Correction rejected')
      queryClient.invalidateQueries({ queryKey: ['edi-correction-sessions'] })
    },
    onError: (e) => toast.error('Reject failed: ' + (e.response?.data?.message || e.message)),
  })

  const correctionList = Array.isArray(corrections) ? corrections : corrections?.content || []

  if (isLoading) return <Spinner />

  return (
    <div className="space-y-6">
      {/* Summary cards */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Pending Reviews" icon={ExclamationTriangleIcon}
                  value={correctionList.filter(c => c.status === 'PENDING').length} color="text-yellow-400" />
        <StatCard label="Approved" icon={CheckCircleIcon}
                  value={correctionList.filter(c => c.status === 'APPROVED').length} color="text-green-400" />
        <StatCard label="Total Corrections" icon={ClipboardDocumentCheckIcon}
                  value={correctionList.length} />
      </div>

      {correctionList.length === 0 ? (
        <EmptyState icon={ClipboardDocumentCheckIcon} message="No correction sessions. Corrections appear when AI mappings are manually fixed." />
      ) : (
        <div className="bg-gray-800 rounded-lg border border-gray-700 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-700 text-muted text-xs">
                <th className="px-4 py-2 text-left w-8" />
                <th className="px-4 py-2 text-left">Session</th>
                <th className="px-4 py-2 text-left">Status</th>
                <th className="px-4 py-2 text-left">Format Pair</th>
                <th className="px-4 py-2 text-left">Improvement</th>
                <th className="px-4 py-2 text-left">Created</th>
                <th className="px-4 py-2 text-left">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-700">
              {correctionList.map(c => (
                <CorrectionRow key={c.id || c.sessionId} correction={c}
                               expanded={expandedId === (c.id || c.sessionId)}
                               onToggle={() => setExpandedId(expandedId === (c.id || c.sessionId) ? null : (c.id || c.sessionId))}
                               onApprove={() => approve.mutate(c.sessionId || c.id)}
                               onReject={() => reject.mutate(c.sessionId || c.id)}
                               approving={approve.isPending} rejecting={reject.isPending} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function CorrectionRow({ correction: c, expanded, onToggle, onApprove, onReject, approving, rejecting }) {
  const sessionKey = c.id || c.sessionId
  return (
    <>
      <tr className="hover:bg-gray-750 cursor-pointer" onClick={onToggle}>
        <td className="px-4 py-2">
          {expanded ? <ChevronUpIcon className="w-4 h-4 text-muted" /> : <ChevronDownIcon className="w-4 h-4 text-muted" />}
        </td>
        <td className="px-4 py-2 text-gray-300 font-mono text-xs">{sessionKey?.toString().substring(0, 12) || '—'}</td>
        <td className="px-4 py-2">
          <span className={CORRECTION_BADGE[c.status] || 'badge badge-gray'}>{c.status || 'UNKNOWN'}</span>
        </td>
        <td className="px-4 py-2 text-gray-300 text-xs">
          {c.sourceFormat && c.targetFormat ? `${c.sourceFormat} → ${c.targetFormat}` : c.formatPair || '—'}
        </td>
        <td className="px-4 py-2">
          {c.improvementPct != null ? (
            <span className={c.improvementPct > 0 ? 'text-green-400 text-xs' : 'text-red-400 text-xs'}>
              {c.improvementPct > 0 ? '+' : ''}{Math.round(c.improvementPct * 100)}%
            </span>
          ) : <span className="text-muted text-xs">—</span>}
        </td>
        <td className="px-4 py-2 text-secondary text-xs">
          {c.createdAt ? format(new Date(c.createdAt), 'MMM d, HH:mm') : '—'}
        </td>
        <td className="px-4 py-2">
          {c.status === 'PENDING' && (
            <div className="flex gap-1" onClick={e => e.stopPropagation()}>
              <button onClick={onApprove} disabled={approving}
                      className="text-xs px-2 py-0.5 bg-green-600/20 text-green-400 rounded hover:bg-green-600/30 disabled:opacity-50">
                {approving ? '...' : 'Approve'}
              </button>
              <button onClick={onReject} disabled={rejecting}
                      className="text-xs px-2 py-0.5 bg-red-600/20 text-red-400 rounded hover:bg-red-600/30 disabled:opacity-50">
                {rejecting ? '...' : 'Reject'}
              </button>
            </div>
          )}
        </td>
      </tr>
      {expanded && (
        <tr>
          <td colSpan={7} className="px-6 py-4 bg-gray-850 border-t border-gray-700">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <div className="text-xs text-muted mb-1">Original AI Mapping</div>
                <pre className="bg-gray-900 rounded p-3 text-xs font-mono text-gray-400 overflow-auto max-h-40 border border-gray-700">
                  {c.originalMapping || c.original || 'No data available'}
                </pre>
              </div>
              <div>
                <div className="text-xs text-muted mb-1">Corrected Mapping</div>
                <pre className="bg-gray-900 rounded p-3 text-xs font-mono text-green-300 overflow-auto max-h-40 border border-gray-700">
                  {c.correctedMapping || c.corrected || 'No data available'}
                </pre>
              </div>
            </div>
            {c.notes && (
              <div className="mt-3 text-xs text-secondary">
                <span className="text-muted">Notes:</span> {c.notes}
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

// ── Main Page ──────────────────────────────────────────────────────────────

export default function EdiTraining() {
  const [activeTab, setActiveTab] = useState('sessions')

  const { data: health } = useQuery({
    queryKey: ['edi-training-health'],
    queryFn: api.getTrainingHealth,
    meta: { silent: true }, refetchInterval: 30000,
    retry: 1,
  })

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <AcademicCapIcon className="w-6 h-6 text-blue-400" />
          <div>
            <h1 className="text-xl font-bold text-white">EDI AI Training</h1>
            <p className="text-muted text-sm">Train, correct, and refine AI-powered EDI mapping models</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className={`badge ${health?.status === 'UP' ? 'badge-green' : health?.status === 'DEGRADED' ? 'badge-yellow' : 'badge-red'}`}>
            {health?.status || 'OFFLINE'}
          </span>
          {health && (
            <span className="text-secondary text-xs">
              {health.totalSessions ?? 0} sessions · {health.totalSamples ?? 0} samples
            </span>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-700">
        {TABS.map(tab => (
          <button key={tab.key} onClick={() => setActiveTab(tab.key)}
                  className={`flex items-center gap-1.5 px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-blue-500 text-blue-400'
                      : 'border-transparent text-muted hover:text-gray-300'
                  }`}>
            <tab.icon className="w-4 h-4" />
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {activeTab === 'sessions' && <SessionsTab />}
      {activeTab === 'samples' && <SamplesTab />}
      {activeTab === 'corrections' && <CorrectionsTab />}
    </div>
  )
}
