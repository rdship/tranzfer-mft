import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { changePassword, rotateKey, testConnection } from '../api/partnerPortal'
import {
  LockClosedIcon,
  KeyIcon,
  SignalIcon,
  CheckCircleIcon,
  XCircleIcon,
  EyeIcon,
  EyeSlashIcon,
} from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

/* Reusable section header */
function SectionHeader({ icon: Icon, title, subtitle, color }) {
  return (
    <div className="flex items-center gap-3 mb-5">
      <div className="p-2 rounded-lg" style={{ background: `${color}18` }}>
        <Icon className="w-5 h-5" style={{ color }} />
      </div>
      <div>
        <h3 className="text-sm font-semibold" style={{ color: 'rgb(var(--tx-primary))' }}>{title}</h3>
        <p className="text-xs" style={{ color: 'rgb(var(--tx-muted))' }}>{subtitle}</p>
      </div>
    </div>
  )
}

export default function PartnerPortalSettings() {
  /* ── Change Password state ── */
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword]         = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showCurrentPw, setShowCurrentPw]     = useState(false)
  const [showNewPw, setShowNewPw]             = useState(false)

  /* ── SSH Key state ── */
  const [publicKey, setPublicKey] = useState('')

  /* ── Connection test state ── */
  const [testResult, setTestResult] = useState(null) // { success: bool, message: string }

  /* ── Mutations ── */
  const passwordMutation = useMutation({
    mutationFn: () => changePassword(currentPassword, newPassword),
    onSuccess: () => {
      toast.success('Password changed successfully')
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
    },
    onError: (err) => {
      toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to change password')
    },
  })

  const keyMutation = useMutation({
    mutationFn: () => rotateKey(publicKey),
    onSuccess: () => {
      toast.success('SSH key rotated successfully')
      setPublicKey('')
    },
    onError: (err) => {
      toast.error(err.response?.data?.error || err.response?.data?.message || 'Failed to rotate SSH key')
    },
  })

  const connectionMutation = useMutation({
    mutationFn: testConnection,
    onSuccess: (data) => {
      setTestResult({ success: true, message: data?.message || 'Connection successful — your endpoint is reachable' })
      toast.success('Connection test passed')
    },
    onError: (err) => {
      const msg = err.response?.data?.error || err.message || 'Connection test failed'
      setTestResult({ success: false, message: msg })
      toast.error(msg)
    },
  })

  /* ── Validation ── */
  const passwordsMatch = newPassword === confirmPassword
  const passwordValid  = currentPassword.length > 0 && newPassword.length >= 8 && passwordsMatch
  const keyValid       = publicKey.trim().length > 0

  const handlePasswordSubmit = (e) => {
    e.preventDefault()
    if (!passwordValid) {
      if (!passwordsMatch) toast.error('Passwords do not match')
      else if (newPassword.length < 8) toast.error('New password must be at least 8 characters')
      return
    }
    passwordMutation.mutate()
  }

  const handleKeySubmit = (e) => {
    e.preventDefault()
    if (!keyValid) {
      toast.error('Please paste your public key')
      return
    }
    keyMutation.mutate()
  }

  return (
    <div className="space-y-5 animate-page">

      {/* Header */}
      <div>
        <h1 className="text-xl font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>Settings</h1>
        <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-muted))' }}>
          Manage your account security and connectivity
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

        {/* ── Change Password Card ── */}
        <div className="card">
          <SectionHeader
            icon={LockClosedIcon}
            title="Change Password"
            subtitle="Update your transfer account password"
            color="#8b5cf6"
          />

          <form onSubmit={handlePasswordSubmit} className="space-y-4">
            {/* Current password */}
            <div>
              <label className="block text-xs font-medium mb-1.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
                Current Password
              </label>
              <div className="relative">
                <input
                  type={showCurrentPw ? 'text' : 'password'}
                  value={currentPassword}
                  onChange={e => setCurrentPassword(e.target.value)}
                  placeholder="Enter current password"
                  required
                  autoComplete="current-password"
                />
                <button
                  type="button"
                  className="absolute right-3 top-1/2 -translate-y-1/2"
                  style={{ color: 'rgb(var(--tx-muted))' }}
                  onClick={() => setShowCurrentPw(v => !v)}
                  tabIndex={-1}
                >
                  {showCurrentPw
                    ? <EyeSlashIcon className="w-4 h-4" />
                    : <EyeIcon className="w-4 h-4" />
                  }
                </button>
              </div>
            </div>

            {/* New password */}
            <div>
              <label className="block text-xs font-medium mb-1.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
                New Password
              </label>
              <div className="relative">
                <input
                  type={showNewPw ? 'text' : 'password'}
                  value={newPassword}
                  onChange={e => setNewPassword(e.target.value)}
                  placeholder="Minimum 8 characters"
                  required
                  minLength={8}
                  autoComplete="new-password"
                />
                <button
                  type="button"
                  className="absolute right-3 top-1/2 -translate-y-1/2"
                  style={{ color: 'rgb(var(--tx-muted))' }}
                  onClick={() => setShowNewPw(v => !v)}
                  tabIndex={-1}
                >
                  {showNewPw
                    ? <EyeSlashIcon className="w-4 h-4" />
                    : <EyeIcon className="w-4 h-4" />
                  }
                </button>
              </div>
            </div>

            {/* Confirm password */}
            <div>
              <label className="block text-xs font-medium mb-1.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
                Confirm New Password
              </label>
              <input
                type="password"
                value={confirmPassword}
                onChange={e => setConfirmPassword(e.target.value)}
                placeholder="Re-enter new password"
                required
                autoComplete="new-password"
              />
              {confirmPassword && !passwordsMatch && (
                <p className="text-xs mt-1" style={{ color: '#f87171' }}>Passwords do not match</p>
              )}
            </div>

            <button
              type="submit"
              className="btn-primary w-full justify-center"
              disabled={!passwordValid || passwordMutation.isPending}
            >
              {passwordMutation.isPending ? (
                <span className="flex items-center gap-2">
                  <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Changing...
                </span>
              ) : (
                <>
                  <LockClosedIcon className="w-4 h-4" />
                  Change Password
                </>
              )}
            </button>
          </form>
        </div>

        {/* ── SSH Key Rotation Card ── */}
        <div className="card">
          <SectionHeader
            icon={KeyIcon}
            title="SSH Key Rotation"
            subtitle="Replace your SSH public key for SFTP access"
            color="#22d3ee"
          />

          <form onSubmit={handleKeySubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-medium mb-1.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
                Public Key
              </label>
              <textarea
                value={publicKey}
                onChange={e => setPublicKey(e.target.value)}
                placeholder="Paste your SSH public key here (ssh-rsa AAAA... or ssh-ed25519 AAAA...)"
                rows={6}
                className="font-mono text-xs"
                style={{ resize: 'vertical' }}
              />
              <p className="text-[10px] mt-1" style={{ color: 'rgb(var(--tx-muted))' }}>
                Supported formats: ssh-rsa, ssh-ed25519, ecdsa-sha2-nistp256
              </p>
            </div>

            <button
              type="submit"
              className="btn-primary w-full justify-center"
              disabled={!keyValid || keyMutation.isPending}
            >
              {keyMutation.isPending ? (
                <span className="flex items-center gap-2">
                  <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Rotating...
                </span>
              ) : (
                <>
                  <KeyIcon className="w-4 h-4" />
                  Rotate SSH Key
                </>
              )}
            </button>
          </form>
        </div>

        {/* ── Connection Test Card ── */}
        <div className="card lg:col-span-2">
          <SectionHeader
            icon={SignalIcon}
            title="Connection Test"
            subtitle="Verify SFTP/FTP connectivity to your configured endpoint"
            color="#fbbf24"
          />

          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4">
            <button
              className="btn-primary"
              onClick={() => {
                setTestResult(null)
                connectionMutation.mutate()
              }}
              disabled={connectionMutation.isPending}
            >
              {connectionMutation.isPending ? (
                <span className="flex items-center gap-2">
                  <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Testing Connection...
                </span>
              ) : (
                <>
                  <SignalIcon className="w-4 h-4" />
                  Run Connection Test
                </>
              )}
            </button>

            {/* Result display */}
            {testResult && (
              <div
                className="flex items-center gap-2 px-4 py-2.5 rounded-xl flex-1"
                style={{
                  background: testResult.success ? 'rgb(20 83 45 / 0.25)' : 'rgb(127 29 29 / 0.25)',
                  border: `1px solid ${testResult.success ? 'rgb(20 83 45 / 0.5)' : 'rgb(127 29 29 / 0.5)'}`,
                }}
              >
                {testResult.success ? (
                  <CheckCircleIcon className="w-5 h-5 flex-shrink-0" style={{ color: '#4ade80' }} />
                ) : (
                  <XCircleIcon className="w-5 h-5 flex-shrink-0" style={{ color: '#f87171' }} />
                )}
                <div>
                  <p
                    className="text-sm font-medium"
                    style={{ color: testResult.success ? '#4ade80' : '#f87171' }}
                  >
                    {testResult.success ? 'Connection Successful' : 'Connection Failed'}
                  </p>
                  <p className="text-xs mt-0.5" style={{ color: 'rgb(var(--tx-secondary))' }}>
                    {testResult.message}
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
