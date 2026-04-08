import { useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { useBranding } from '../context/BrandingContext'
import { BoltIcon, ShieldCheckIcon, ArrowsRightLeftIcon, ServerStackIcon } from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

const features = [
  { icon: ArrowsRightLeftIcon, label: 'AS2/AS4, SFTP, FTP' },
  { icon: ShieldCheckIcon, label: 'End-to-End Encryption' },
  { icon: ServerStackIcon, label: 'HA Clustering' },
]

export default function Login() {
  const { login } = useAuth()
  const { branding } = useBranding()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)
    try {
      await login(email, password)
    } catch (err) {
      toast.error(err.response?.data?.error || 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-blue-950 to-slate-900 flex items-center justify-center p-4">
      {/* Background decoration */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-blue-600/10 rounded-full blur-3xl" />
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-blue-400/10 rounded-full blur-3xl" />
      </div>

      <div className="relative w-full max-w-sm">
        <div className="text-center mb-8">
          {branding.logoUrl ? (
            <img src={branding.logoUrl} alt={branding.companyName} className="h-12 mx-auto mb-4" />
          ) : (
            <div className="flex items-center justify-center gap-3 mb-4">
              <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-blue-700 rounded-2xl flex items-center justify-center shadow-lg shadow-blue-500/25">
                <BoltIcon className="w-7 h-7 text-white" />
              </div>
            </div>
          )}
          <h1 className="text-2xl font-bold text-white">{branding.companyName}</h1>
          <p className="text-blue-300/80 text-sm mt-1">AI-First Managed File Transfer</p>

          {/* Feature pills */}
          <div className="flex items-center justify-center gap-3 mt-4">
            {features.map(f => (
              <div key={f.label} className="flex items-center gap-1.5 text-blue-300/60 text-xs">
                <f.icon className="w-3.5 h-3.5" />
                <span>{f.label}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="bg-white/95 backdrop-blur-sm rounded-2xl shadow-2xl shadow-black/20 p-8">
          <h2 className="text-lg font-semibold text-gray-900 mb-1">Welcome back</h2>
          <p className="text-sm text-gray-500 mb-6">Sign in to your admin console</p>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label>Email address</label>
              <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoFocus placeholder="admin@company.com" />
            </div>
            <div>
              <label>Password</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} required placeholder="Enter your password" />
            </div>
            <button type="submit" disabled={loading}
              className="w-full py-2.5 bg-gradient-to-r from-blue-600 to-blue-700 text-white text-sm font-medium rounded-lg hover:from-blue-700 hover:to-blue-800 transition-all disabled:opacity-50 shadow-lg shadow-blue-500/25">
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" /></svg>
                  Signing in...
                </span>
              ) : 'Sign in'}
            </button>
          </form>
        </div>

        <p className="text-center text-blue-400/50 text-xs mt-6">
          TranzFer MFT Platform v3.0 — Enterprise Edition
        </p>
      </div>
    </div>
  )
}
