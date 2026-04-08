import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { licenseApi } from '../api/client'
import toast from 'react-hot-toast'
import { KeyIcon, CheckBadgeIcon } from '@heroicons/react/24/outline'
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

  const activateMut = useMutation({
    mutationFn: (key) => licenseApi.post('/api/v1/licenses/validate', {
      licenseKey: key, serviceType: 'ADMIN_UI', hostId: window.location.hostname, installationFingerprint: fingerprint
    }).then(r => r.data),
    onSuccess: (data) => {
      if (data.valid) {
        localStorage.setItem('license-key', licenseKey)
        toast.success('License activated successfully!')
      } else {
        toast.error('License invalid: ' + data.message)
      }
    },
    onError: () => toast.error('Failed to validate license')
  })

  const modeColors = { TRIAL: 'badge-yellow', LICENSED: 'badge-green' }

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">License Management</h1>
        <p className="text-gray-500 text-sm">Manage your TranzFer MFT platform license</p></div>

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
                  <span className="text-gray-500">Mode:</span>
                  <span className={`badge ${modeColors[status.mode] || 'badge-gray'}`}>{status.mode || '—'}</span>
                </div>
                {status.edition && <div><span className="text-gray-500">Edition: </span><strong>{status.edition}</strong></div>}
                {status.expiresAt && <div><span className="text-gray-500">Expires: </span><strong>{format(new Date(status.expiresAt), 'MMMM d, yyyy')}</strong></div>}
                {status.trialDaysRemaining != null && (
                  <div className="text-amber-700 font-medium">{status.trialDaysRemaining} trial days remaining</div>
                )}
                {status.maxInstances > 0 && <div><span className="text-gray-500">Max instances per service: </span><strong>{status.maxInstances}</strong></div>}
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
