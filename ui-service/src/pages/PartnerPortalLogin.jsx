import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { partnerLogin } from '../api/partnerPortal'
import {
  BoltIcon,
  ArrowsRightLeftIcon,
  ShieldCheckIcon,
  ServerStackIcon,
  LockClosedIcon,
  UserIcon,
} from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

const FEATURES = [
  { icon: ArrowsRightLeftIcon, label: 'SFTP / FTP / AS2' },
  { icon: ShieldCheckIcon,     label: 'End-to-End Encryption' },
  { icon: ServerStackIcon,     label: 'Real-Time Tracking' },
]

/* Floating orb -- purely decorative */
function Orb({ style, delay = 0 }) {
  return (
    <div
      className="absolute rounded-full pointer-events-none"
      style={{
        animation: `drift ${12 + delay}s ease-in-out ${delay}s infinite`,
        filter: 'blur(72px)',
        opacity: 0.55,
        ...style,
      }}
    />
  )
}

export default function PartnerPortalLogin() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading,  setLoading]  = useState(false)
  const [focused,  setFocused]  = useState(null)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)
    try {
      const data = await partnerLogin(username, password)
      localStorage.setItem('partner-token', data.token)
      localStorage.setItem('partner-user', JSON.stringify(data.user || data))
      toast.success('Welcome back!')
      navigate('/portal')
    } catch (err) {
      toast.error(err.response?.data?.error || err.response?.data?.message || err.message || 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      className="min-h-screen flex items-center justify-center p-6 overflow-hidden relative"
      style={{ background: 'linear-gradient(135deg, #0f0c29 0%, #302b63 50%, #24243e 100%)' }}
    >
      {/* Animated background orbs */}
      <Orb style={{ width: 560, height: 560, top: '-15%', right: '-10%', background: 'radial-gradient(circle, #1d4ed8 0%, transparent 70%)' }} delay={0} />
      <Orb style={{ width: 480, height: 480, bottom: '-15%', left: '-8%',  background: 'radial-gradient(circle, #7c3aed 0%, transparent 70%)' }} delay={4} />
      <Orb style={{ width: 320, height: 320, top: '40%',   left: '15%',   background: 'radial-gradient(circle, #0891b2 0%, transparent 70%)' }} delay={2} />

      {/* Noise texture overlay */}
      <div
        className="absolute inset-0 pointer-events-none"
        style={{ opacity: 0.04, backgroundImage: 'url("data:image/svg+xml,%3Csvg viewBox=\'0 0 256 256\' xmlns=\'http://www.w3.org/2000/svg\'%3E%3Cfilter id=\'n\'%3E%3CfeTurbulence type=\'fractalNoise\' baseFrequency=\'0.9\' numOctaves=\'4\'/%3E%3C/filter%3E%3Crect width=\'100%25\' height=\'100%25\' filter=\'url(%23n)\'/%3E%3C/svg%3E")' }}
      />

      {/* Login card */}
      <div className="relative w-full max-w-[400px] z-10 animate-scale-in">

        {/* Brand */}
        <div className="text-center mb-8">
          <div className="flex items-center justify-center mb-5">
            <div
              className="relative w-16 h-16 rounded-2xl flex items-center justify-center"
              style={{
                background: 'linear-gradient(135deg, #1d4ed8 0%, #4f46e5 100%)',
                boxShadow: '0 0 40px rgb(29 78 216 / 0.5), 0 0 80px rgb(29 78 216 / 0.2)',
              }}
            >
              <BoltIcon className="w-8 h-8 text-white" />
              <div
                className="absolute inset-0 rounded-2xl"
                style={{ border: '1px solid rgb(255 255 255 / 0.25)' }}
              />
            </div>
          </div>

          <h1 className="text-3xl font-black text-white tracking-tight" style={{ letterSpacing: '-0.03em' }}>
            TranzFer
          </h1>
          <p className="mt-1 text-sm font-medium" style={{ color: 'rgb(100 140 255 / 0.9)' }}>
            Partner Portal
          </p>

          {/* Feature pills */}
          <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1.5 mt-5">
            {FEATURES.map(f => (
              <div key={f.label} className="flex items-center gap-1.5" style={{ color: 'rgb(167 139 250 / 0.6)' }}>
                <f.icon className="w-3.5 h-3.5 flex-shrink-0" />
                <span className="text-[11px] font-medium">{f.label}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Glass card */}
        <div
          className="rounded-2xl overflow-hidden"
          style={{
            background: 'rgb(255 255 255 / 0.07)',
            border: '1px solid rgb(255 255 255 / 0.12)',
            backdropFilter: 'blur(24px)',
            WebkitBackdropFilter: 'blur(24px)',
            boxShadow: '0 32px 64px -16px rgb(0 0 0 / 0.5), inset 0 1px 0 rgb(255 255 255 / 0.1)',
          }}
        >
          {/* Card header */}
          <div className="px-8 pt-7 pb-6">
            <h2 className="text-lg font-bold text-white">Partner Sign In</h2>
            <p className="text-sm mt-0.5" style={{ color: 'rgb(167 139 250 / 0.7)' }}>
              Access your transfer account
            </p>
          </div>

          {/* Divider */}
          <div style={{ height: '1px', background: 'rgb(255 255 255 / 0.08)' }} />

          {/* Form */}
          <form onSubmit={handleSubmit} className="px-8 py-7 space-y-5">

            {/* Username */}
            <div>
              <label
                className="block text-xs font-semibold uppercase tracking-wider mb-2"
                style={{ color: 'rgb(167 139 250 / 0.8)' }}
              >
                Username
              </label>
              <div className="relative">
                <input
                  type="text"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  onFocus={() => setFocused('username')}
                  onBlur={() => setFocused(null)}
                  required
                  autoFocus
                  autoComplete="username"
                  placeholder="Your transfer account username"
                  style={{
                    background: 'rgb(255 255 255 / 0.06)',
                    border: `1px solid ${focused === 'username' ? 'rgb(100 140 255 / 0.7)' : 'rgb(255 255 255 / 0.12)'}`,
                    borderRadius: '0.75rem',
                    color: 'white',
                    padding: '0.6875rem 0.875rem 0.6875rem 2.75rem',
                    fontSize: '0.875rem',
                    outline: 'none',
                    width: '100%',
                    transition: 'border-color 0.15s ease, box-shadow 0.15s ease',
                    boxShadow: focused === 'username' ? '0 0 0 3px rgb(100 140 255 / 0.15)' : 'none',
                  }}
                />
                <UserIcon
                  className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4"
                  style={{ color: focused === 'username' ? 'rgb(100, 140, 255)' : 'rgb(167 139 250 / 0.45)' }}
                />
              </div>
            </div>

            {/* Password */}
            <div>
              <label
                className="block text-xs font-semibold uppercase tracking-wider mb-2"
                style={{ color: 'rgb(167 139 250 / 0.8)' }}
              >
                Password
              </label>
              <div className="relative">
                <input
                  type="password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  onFocus={() => setFocused('password')}
                  onBlur={() => setFocused(null)}
                  required
                  autoComplete="current-password"
                  placeholder="Enter your password"
                  style={{
                    background: 'rgb(255 255 255 / 0.06)',
                    border: `1px solid ${focused === 'password' ? 'rgb(100 140 255 / 0.7)' : 'rgb(255 255 255 / 0.12)'}`,
                    borderRadius: '0.75rem',
                    color: 'white',
                    padding: '0.6875rem 0.875rem 0.6875rem 2.75rem',
                    fontSize: '0.875rem',
                    outline: 'none',
                    width: '100%',
                    transition: 'border-color 0.15s ease, box-shadow 0.15s ease',
                    boxShadow: focused === 'password' ? '0 0 0 3px rgb(100 140 255 / 0.15)' : 'none',
                  }}
                />
                <LockClosedIcon
                  className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4"
                  style={{ color: focused === 'password' ? 'rgb(100, 140, 255)' : 'rgb(167 139 250 / 0.45)' }}
                />
              </div>
            </div>

            {/* Submit */}
            <button
              type="submit"
              disabled={loading}
              className="w-full py-3 rounded-xl text-sm font-semibold text-white transition-all duration-200"
              style={{
                background: loading
                  ? 'rgb(100 140 255 / 0.5)'
                  : 'linear-gradient(135deg, #1d4ed8 0%, #4f46e5 100%)',
                boxShadow: loading ? 'none' : '0 4px 24px rgb(29 78 216 / 0.45)',
                cursor: loading ? 'not-allowed' : 'pointer',
                border: '1px solid rgb(100 140 255 / 0.3)',
              }}
              onMouseEnter={e => {
                if (!loading) {
                  e.currentTarget.style.boxShadow = '0 8px 32px rgb(29 78 216 / 0.60)'
                  e.currentTarget.style.transform = 'translateY(-1px)'
                }
              }}
              onMouseLeave={e => {
                e.currentTarget.style.boxShadow = loading ? 'none' : '0 4px 24px rgb(29 78 216 / 0.45)'
                e.currentTarget.style.transform = 'translateY(0)'
              }}
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24" fill="none">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Signing in...
                </span>
              ) : (
                'Sign in to Portal'
              )}
            </button>
          </form>
        </div>

        {/* Footer */}
        <p className="text-center text-[11px] mt-6" style={{ color: 'rgb(100 140 255 / 0.4)' }}>
          TranzFer MFT Platform — Partner Portal
        </p>
      </div>
    </div>
  )
}
