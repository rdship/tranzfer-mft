import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { licenseApi } from '../api/client'
import Modal from '../components/Modal'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import toast from 'react-hot-toast'
import { KeyIcon, CheckBadgeIcon, TrashIcon, PlusIcon, CubeIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

export default function License() {
  const [licenseKey, setLicenseKey] = useState('')
  const [fingerprint] = useState(() => btoa(navigator.userAgent + window.location.hostname))

  const { data: status, isLoading } = useQuery({
    queryKey: ['license-status'],
    queryFn: () => licenseApi.post('/api/v1/licenses/validate', {
      licenseKey: localStorage.getItem('license-key') || null,
      serviceType: 'ADMIN_UI',
      hostId: window.location.hostname,
      installationFingerprint: fingerprint
    }).then(r => r.data)
  })

  const qc = useQueryClient()

  const activateMut = useMutation({
    mutationFn: (key) => licenseApi.post('/api/v1/licenses/validate', {
      licenseKey: key, serviceType: 'ADMIN_UI', hostId: window.location.hostname, installationFingerprint: fingerprint
    }).then(r => r.data),
    onSuccess: (data) => {
      if (data.valid) {
        localStorage.setItem('license-key', licenseKey)
        toast.success('License activated successfully!')
        qc.invalidateQueries({ queryKey: ['license-status'] })
      } else {
        toast.error('License invalid: ' + data.message)
      }
    },
    onError: () => toast.error('Failed to validate license')
  })

  // ── Component Catalog ──
  const { data: catalog = [], isLoading: loadingCatalog } = useQuery({
    queryKey: ['license-catalog'],
    queryFn: () => licenseApi.get('/api/v1/licenses/catalog/components').then(r => r.data).catch(() => [])
  })

  // ── All licenses (admin) ──
  const { data: allLicenses = [], isLoading: loadingLicenses } = useQuery({
    queryKey: ['all-licenses'],
    queryFn: () => licenseApi.get('/api/v1/licenses').then(r => r.data).catch(() => [])
  })

  // ── Admin state ──
  const [showIssueModal, setShowIssueModal] = useState(false)
  const [issueForm, setIssueForm] = useState({ licenseKey: '', edition: 'STANDARD', expiry: '', maxInstances: 1 })
  const [showRevokeConfirm, setShowRevokeConfirm] = useState(null) // license id

  const isAdmin = status?.role === 'ADMIN' || status?.edition === 'ENTERPRISE' || true // show for all until role check is available

  const issueMut = useMutation({
    mutationFn: (data) => licenseApi.post('/api/v1/licenses/issue', data).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['all-licenses'] })
      qc.invalidateQueries({ queryKey: ['license-status'] })
      qc.invalidateQueries({ queryKey: ['licenses'] })
      qc.invalidateQueries({ queryKey: ['license-catalog'] })
      setShowIssueModal(false)
      setIssueForm({ licenseKey: '', edition: 'STANDARD', expiry: '', maxInstances: 1 })
      toast.success('License issued successfully')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to issue license')
  })

  const revokeMut = useMutation({
    mutationFn: (id) => licenseApi.delete(`/api/v1/licenses/${id}/revoke`).then(r => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['all-licenses'] })
      qc.invalidateQueries({ queryKey: ['license-status'] })
      qc.invalidateQueries({ queryKey: ['licenses'] })
      qc.invalidateQueries({ queryKey: ['license-catalog'] })
      setShowRevokeConfirm(null)
      toast.success('License revoked')
    },
    onError: err => toast.error(err.response?.data?.message || 'Failed to revoke license')
  })

  const modeColors = { TRIAL: 'badge-yellow', LICENSED: 'badge-green' }
  const editionColors = { STANDARD: 'badge-blue', PROFESSIONAL: 'badge-yellow', ENTERPRISE: 'badge-green' }

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">License Management</h1>
        <p className="text-secondary text-sm">Manage your TranzFer MFT platform license</p></div>

      <div className="card">
        <div className="flex items-start gap-4">
          <div className={`p-3 rounded-xl ${status?.valid ? 'bg-green-50' : 'bg-yellow-50'}`}>
            {status?.valid ? <CheckBadgeIcon className="w-8 h-8 text-green-600" /> : <KeyIcon className="w-8 h-8 text-yellow-600" />}
          </div>
          <div>
            <h2 className="text-xl font-bold text-gray-900">
              {isLoading ? 'Checking license...' : (status?.valid ? 'Licensed' : 'No Active License')}
            </h2>
            {status && (
              <div className="mt-2 space-y-1 text-sm">
                <div className="flex items-center gap-2">
                  <span className="text-secondary">Mode:</span>
                  <span className={`badge ${modeColors[status.mode] || 'badge-gray'}`}>{status.mode || '—'}</span>
                </div>
                {status.edition && <div><span className="text-secondary">Edition: </span><strong>{status.edition}</strong></div>}
                {status.expiresAt && <div><span className="text-secondary">Expires: </span><strong>{format(new Date(status.expiresAt), 'MMMM d, yyyy')}</strong></div>}
                {status.trialDaysRemaining != null && (
                  <div className="text-amber-700 font-medium">{status.trialDaysRemaining} trial days remaining</div>
                )}
                {status.maxInstances > 0 && <div><span className="text-secondary">Max instances per service: </span><strong>{status.maxInstances}</strong></div>}
                <p className="text-gray-600 mt-2">{status.message}</p>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="card">
        <h3 className="font-semibold text-gray-900 mb-4">Activate License</h3>
        <div className="space-y-3">
          <div>
            <label>License Key</label>
            <textarea rows={4} value={licenseKey} onChange={e => setLicenseKey(e.target.value)}
              placeholder="Paste your license key here..." className="font-mono text-xs" />
          </div>
          <button className="btn-primary" disabled={!licenseKey || activateMut.isPending}
            onClick={() => activateMut.mutate(licenseKey)}>
            {activateMut.isPending ? 'Validating...' : 'Activate License'}
          </button>
        </div>
      </div>

      {/* ── Component Catalog ── */}
      <div className="card">
        <div className="flex items-center gap-2 mb-4">
          <CubeIcon className="w-5 h-5 text-indigo-600" />
          <h3 className="font-semibold text-gray-900">Component Catalog</h3>
          <span className="text-xs text-secondary">Licensable modules and features</span>
        </div>
        {loadingCatalog ? <LoadingSpinner /> : catalog.length === 0 ? (
          <EmptyState title="No catalog data" description="Component catalog data is not available from the license server." />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {catalog.map(comp => (
              <div key={comp.id || comp.name} className="border border-border rounded-lg p-4 hover:shadow-sm transition-shadow">
                <div className="flex items-start justify-between gap-2 mb-2">
                  <h4 className="font-semibold text-gray-900 text-sm">{comp.name}</h4>
                  {comp.licensed ? (
                    <span className="badge badge-green flex-shrink-0">Licensed</span>
                  ) : (
                    <span className="badge badge-gray flex-shrink-0">Not Licensed</span>
                  )}
                </div>
                {comp.description && <p className="text-xs text-secondary mb-2">{comp.description}</p>}
                {comp.features && comp.features.length > 0 && (
                  <div className="mt-2 pt-2 border-t border-border">
                    <p className="text-xs font-medium text-secondary mb-1">Included Features:</p>
                    <div className="flex flex-wrap gap-1">
                      {comp.features.map((f, i) => (
                        <span key={i} className="text-xs bg-hover px-1.5 py-0.5 rounded text-secondary">{f}</span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Admin License Management ── */}
      {isAdmin && (
        <div className="card">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <KeyIcon className="w-5 h-5 text-amber-600" />
              <h3 className="font-semibold text-gray-900">Admin: License Management</h3>
            </div>
            <button onClick={() => { setShowIssueModal(true); setIssueForm({ licenseKey: '', edition: 'STANDARD', expiry: '', maxInstances: 1 }) }}
              className="btn-primary flex items-center gap-1.5">
              <PlusIcon className="w-4 h-4" /> Issue License
            </button>
          </div>
          {loadingLicenses ? <LoadingSpinner /> : allLicenses.length === 0 ? (
            <EmptyState title="No licenses issued" description="Issue a license to get started." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead><tr className="border-b">
                  <th className="table-header">License ID</th>
                  <th className="table-header">Edition</th>
                  <th className="table-header">Status</th>
                  <th className="table-header">Expiry</th>
                  <th className="table-header">Activations</th>
                  <th className="table-header w-24">Actions</th>
                </tr></thead>
                <tbody>
                  {allLicenses.map(lic => (
                    <tr key={lic.id} className="table-row">
                      <td className="table-cell font-mono text-xs">{lic.id || lic.licenseId || '--'}</td>
                      <td className="table-cell">
                        <span className={`badge ${editionColors[lic.edition?.toUpperCase()] || 'badge-gray'}`}>{lic.edition || '--'}</span>
                      </td>
                      <td className="table-cell">
                        {lic.status === 'ACTIVE' || lic.active ? (
                          <span className="badge badge-green">Active</span>
                        ) : lic.status === 'REVOKED' ? (
                          <span className="badge badge-red">Revoked</span>
                        ) : (
                          <span className="badge badge-gray">{lic.status || 'Inactive'}</span>
                        )}
                      </td>
                      <td className="table-cell text-xs text-secondary">
                        {lic.expiresAt ? format(new Date(lic.expiresAt), 'MMM dd, yyyy') : '--'}
                      </td>
                      <td className="table-cell text-xs">
                        {lic.activations ?? lic.currentActivations ?? 0} / {lic.maxInstances ?? '--'}
                      </td>
                      <td className="table-cell">
                        {(lic.status === 'ACTIVE' || lic.active) && (
                          <button
                            onClick={() => setShowRevokeConfirm(lic.id || lic.licenseId)}
                            className="p-1 rounded hover:bg-red-50 text-red-500 hover:text-red-700 transition-colors"
                            title="Revoke license"
                          >
                            <TrashIcon className="w-4 h-4" />
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
      )}

      {/* Issue License Modal */}
      {showIssueModal && (
        <Modal title="Issue New License" onClose={() => setShowIssueModal(false)}>
          <div className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">License Key</label>
              <textarea rows={3} value={issueForm.licenseKey}
                onChange={e => setIssueForm(f => ({ ...f, licenseKey: e.target.value }))}
                placeholder="Enter or auto-generate a license key..."
                className="font-mono text-xs" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Edition</label>
                <select value={issueForm.edition}
                  onChange={e => setIssueForm(f => ({ ...f, edition: e.target.value }))}>
                  <option value="STANDARD">Standard</option>
                  <option value="PROFESSIONAL">Professional</option>
                  <option value="ENTERPRISE">Enterprise</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-secondary mb-1">Max Instances</label>
                <input type="number" min={1} value={issueForm.maxInstances}
                  onChange={e => setIssueForm(f => ({ ...f, maxInstances: parseInt(e.target.value) || 1 }))} />
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-secondary mb-1">Expiry Date</label>
              <input type="date" value={issueForm.expiry}
                onChange={e => setIssueForm(f => ({ ...f, expiry: e.target.value }))}
                min={new Date().toISOString().slice(0, 10)} />
            </div>
            <div className="flex justify-end gap-2 pt-3 border-t">
              <button onClick={() => setShowIssueModal(false)} className="btn-secondary">Cancel</button>
              <button
                onClick={() => issueMut.mutate({
                  licenseKey: issueForm.licenseKey,
                  edition: issueForm.edition,
                  expiresAt: issueForm.expiry ? new Date(issueForm.expiry).toISOString() : null,
                  maxInstances: issueForm.maxInstances
                })}
                disabled={issueMut.isPending}
                className="btn-primary">
                {issueMut.isPending ? 'Issuing...' : 'Issue License'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* Revoke Confirmation Modal */}
      {showRevokeConfirm && (
        <Modal title="Revoke License" onClose={() => setShowRevokeConfirm(null)}>
          <div className="space-y-4">
            <div className="flex items-start gap-3 p-3 bg-red-50 border border-red-200 rounded-lg">
              <TrashIcon className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
              <div>
                <p className="text-sm font-medium text-red-800">Revoke this license?</p>
                <p className="text-sm text-red-700 mt-1">
                  This will immediately deactivate the license. Services using this license will fall back to trial mode.
                </p>
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setShowRevokeConfirm(null)} className="btn-secondary">Cancel</button>
              <button
                onClick={() => revokeMut.mutate(showRevokeConfirm)}
                disabled={revokeMut.isPending}
                className="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-semibold rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-60 transition-colors"
              >
                {revokeMut.isPending ? 'Revoking...' : 'Revoke License'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      <div className="card bg-blue-50 border border-blue-100">
        <h3 className="font-semibold text-blue-900 mb-2">Need a License?</h3>
        <p className="text-sm text-blue-700">
          TranzFer MFT is available in Standard, Professional, and Enterprise editions.
          Contact us to get a license key for production use.
        </p>
        <div className="mt-4 grid grid-cols-3 gap-3 text-sm">
          {[{name:'Standard', price:'$999/yr', features:['5 SFTP/FTP accounts','1 replica/service','Email support']},
            {name:'Professional', price:'$4,999/yr', features:['Unlimited accounts','5 replicas/service','Priority support','Analytics']},
            {name:'Enterprise', price:'Custom', features:['Unlimited everything','HA clustering','SLA 99.99%','24/7 support']}
          ].map(tier => (
            <div key={tier.name} className="bg-white rounded-lg p-4 border border-blue-100">
              <h4 className="font-bold text-gray-900">{tier.name}</h4>
              <p className="text-blue-600 font-semibold">{tier.price}</p>
              <ul className="mt-2 space-y-1">
                {tier.features.map(f => <li key={f} className="text-xs text-gray-600">✓ {f}</li>)}
              </ul>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
