import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { onboardingApi } from '../api/client'
import { getAccounts } from '../api/accounts'
import LoadingSpinner from '../components/LoadingSpinner'
import toast from 'react-hot-toast'
import { ShieldCheckIcon, KeyIcon } from '@heroicons/react/24/outline'

export default function TwoFactor() {
  const qc = useQueryClient()
  const [selected, setSelected] = useState(null)
  const { data: accounts = [], isLoading } = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })

  const enableMut = useMutation({
    mutationFn: (username) => onboardingApi.post('/api/2fa/enable', { username, method: 'TOTP_APP' }).then(r => r.data),
    onSuccess: (data) => { setSelected(data); toast.success('2FA enabled — share QR with partner'); qc.invalidateQueries({ queryKey: ['2fa-status'] }) },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to enable 2FA — please try again')
  })
  const disableMut = useMutation({
    mutationFn: (username) => onboardingApi.post('/api/2fa/disable', { username }).then(r => r.data),
    onSuccess: () => { setSelected(null); toast.success('2FA disabled'); qc.invalidateQueries({ queryKey: ['2fa-status'] }) },
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to disable 2FA — please try again')
  })
  const checkMut = useMutation({
    mutationFn: (username) => onboardingApi.get(`/api/2fa/status/${username}`).then(r => r.data),
    onError: (err) => toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to check 2FA status — please try again')
  })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-primary">Two-Factor Authentication</h1>
        <p className="text-secondary text-sm">Enable TOTP 2FA per account — partners use Google Authenticator, Authy, or Microsoft Authenticator</p></div>

      <div className="card">
        <table className="w-full"><thead><tr className="border-b">
          <th className="table-header">Account</th><th className="table-header">Protocol</th><th className="table-header">2FA</th><th className="table-header">Actions</th>
        </tr></thead><tbody>
          {accounts.length === 0 ? (
            <tr><td colSpan={4} className="text-center py-8 text-secondary text-sm">No transfer accounts configured. Create accounts first to manage 2FA.</td></tr>
          ) : accounts.map(a => (
            <tr key={a.id} className="table-row">
              <td className="table-cell font-medium">{a.username}</td>
              <td className="table-cell"><span className="badge badge-blue">{a.protocol}</span></td>
              <td className="table-cell">
                <button onClick={() => checkMut.mutate(a.username)} className="text-xs text-blue-600 hover:underline">Check status</button>
              </td>
              <td className="table-cell flex gap-2">
                <button onClick={() => enableMut.mutate(a.username)} className="text-xs px-2 py-1 bg-green-100 text-green-700 rounded hover:bg-green-200">Enable 2FA</button>
                <button onClick={() => disableMut.mutate(a.username)} className="text-xs px-2 py-1 bg-red-100 text-red-700 rounded hover:bg-red-200">Disable</button>
              </td>
            </tr>
          ))}
        </tbody></table>
      </div>

      {checkMut.data && (
        <div className="card"><h3 className="font-semibold mb-2">2FA Status: {checkMut.data.username}</h3>
          <div className="text-sm space-y-1">
            <p>Enabled: <strong>{checkMut.data.enabled ? 'Yes' : 'No'}</strong></p>
            <p>Enrolled: <strong>{checkMut.data.enrolled ? 'Yes' : 'No'}</strong></p>
            <p>Method: <strong>{checkMut.data.method}</strong></p>
            <p>Last used: <strong>{checkMut.data.lastUsed}</strong></p>
          </div>
        </div>
      )}

      {selected && (
        <div className="card border-green-200 bg-green-50">
          <h3 className="font-semibold text-green-900 mb-3 flex items-center gap-2"><ShieldCheckIcon className="w-5 h-5" /> 2FA Enabled — Share With Partner</h3>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-sm font-medium text-secondary mb-1">Secret (Base32):</p>
              <code className="text-xs bg-surface p-2 rounded block font-mono break-all">{selected.secret}</code>
            </div>
            <div>
              <p className="text-sm font-medium text-secondary mb-1">QR Provisioning URI:</p>
              <code className="text-xs bg-surface p-2 rounded block font-mono break-all">{selected.provisioningUri}</code>
            </div>
          </div>
          <div className="mt-3">
            <p className="text-sm font-medium text-secondary mb-1">Backup Codes (one-time use):</p>
            <div className="flex flex-wrap gap-1">{(selected.backupCodes || []).map((c, i) =>
              <code key={i} className="text-xs bg-surface px-2 py-0.5 rounded font-mono">{c}</code>
            )}</div>
          </div>
          <p className="text-xs text-green-700 mt-3">{selected.instructions}</p>
        </div>
      )}
    </div>
  )
}
