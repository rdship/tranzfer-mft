import { useState } from 'react'
import { partnerLogin } from '../api/client'
import toast from 'react-hot-toast'

export default function Login({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const submit = async (e) => {
    e.preventDefault(); setLoading(true)
    try { const u = await partnerLogin(username, password); onLogin(u) }
    catch (err) { toast.error(err.response?.data?.error || 'Login failed') }
    finally { setLoading(false) }
  }
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-900 to-slate-900 flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="w-14 h-14 bg-blue-600 rounded-2xl flex items-center justify-center mx-auto mb-4 text-white font-bold text-xl">T</div>
          <h1 className="text-2xl font-bold text-white">Partner Portal</h1>
          <p className="text-blue-300 text-sm mt-1">Track your file transfers in real time</p>
        </div>
        <div className="bg-white rounded-2xl shadow-2xl p-8">
          <form onSubmit={submit} className="space-y-4">
            <div><label className="block text-sm font-medium text-gray-700 mb-1">Transfer Account Username</label>
              <input value={username} onChange={e => setUsername(e.target.value)} required autoFocus placeholder="your_account_name"
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent" /></div>
            <div><label className="block text-sm font-medium text-gray-700 mb-1">Password</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} required placeholder="••••••••"
                className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent" /></div>
            <button type="submit" disabled={loading}
              className="w-full py-2.5 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50">
              {loading ? 'Signing in...' : 'Sign In'}
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
