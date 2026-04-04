import { useState } from 'react'
import { useAuth } from '../context/AuthContext'
import { useBranding } from '../context/BrandingContext'
import { BoltIcon } from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

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
    <div className="min-h-screen bg-gradient-to-br from-slate-900 to-blue-900 flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          {branding.logoUrl ? (
            <img src={branding.logoUrl} alt={branding.companyName} className="h-12 mx-auto mb-4" />
          ) : (
            <div className="flex items-center justify-center gap-2 mb-4">
              <div className="w-10 h-10 bg-blue-600 rounded-xl flex items-center justify-center">
                <BoltIcon className="w-6 h-6 text-white" />
              </div>
            </div>
          )}
          <h1 className="text-2xl font-bold text-white">{branding.companyName}</h1>
          <p className="text-blue-300 text-sm mt-1">Managed File Transfer Platform</p>
        </div>

        <div className="bg-white rounded-2xl shadow-2xl p-8">
          <h2 className="text-xl font-semibold text-gray-900 mb-6">Admin Sign In</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label>Email address</label>
              <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoFocus placeholder="admin@company.com" />
            </div>
            <div>
              <label>Password</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} required placeholder="••••••••" />
            </div>
            <button type="submit" disabled={loading} className="btn-primary w-full justify-center py-2.5">
              {loading ? 'Signing in...' : 'Sign in'}
            </button>
          </form>
        </div>

        <p className="text-center text-blue-400 text-xs mt-6">
          TranzFer MFT Platform — Enterprise Edition
        </p>
      </div>
    </div>
  )
}
