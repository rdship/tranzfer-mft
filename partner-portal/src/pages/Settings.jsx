import { useState } from 'react'
import { testConnection, rotateKey, changePassword } from '../api/client'
import toast from 'react-hot-toast'

export default function Settings({ username }) {
  const [pubKey, setPubKey] = useState('')
  const [curPass, setCurPass] = useState('')
  const [newPass, setNewPass] = useState('')

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-900">Account Settings</h1>

      <div className="bg-white rounded-xl border p-5 space-y-4">
        <h3 className="font-semibold text-gray-900">Test Connection</h3>
        <p className="text-sm text-gray-500">Verify your SFTP/FTP connection is working.</p>
        <button onClick={async () => {
          try { const r = await testConnection(username); toast.success(`Connection info: ${r.protocol} on port ${r.serverPort}`); }
          catch { toast.error('Test failed') }
        }} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">Test My Connection</button>
      </div>

      <div className="bg-white rounded-xl border p-5 space-y-4">
        <h3 className="font-semibold text-gray-900">Rotate SSH Key</h3>
        <p className="text-sm text-gray-500">Upload a new SSH public key. The old key will be replaced immediately.</p>
        <textarea value={pubKey} onChange={e => setPubKey(e.target.value)} rows={3} placeholder="ssh-rsa AAAA... user@host"
          className="w-full rounded-lg border px-3 py-2 text-xs font-mono focus:ring-2 focus:ring-blue-500" />
        <button onClick={async () => {
          try { await rotateKey(username, pubKey); toast.success('SSH key rotated'); setPubKey('') }
          catch { toast.error('Key rotation failed') }
        }} disabled={!pubKey} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50">Rotate Key</button>
      </div>

      <div className="bg-white rounded-xl border p-5 space-y-4">
        <h3 className="font-semibold text-gray-900">Change Password</h3>
        <div className="grid grid-cols-2 gap-3">
          <div><label className="text-sm font-medium text-gray-700 mb-1 block">Current Password</label>
            <input type="password" value={curPass} onChange={e => setCurPass(e.target.value)}
              className="w-full rounded-lg border px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500" /></div>
          <div><label className="text-sm font-medium text-gray-700 mb-1 block">New Password</label>
            <input type="password" value={newPass} onChange={e => setNewPass(e.target.value)}
              className="w-full rounded-lg border px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500" /></div>
        </div>
        <button onClick={async () => {
          try { await changePassword(username, curPass, newPass); toast.success('Password changed'); setCurPass(''); setNewPass('') }
          catch (e) { toast.error(e.response?.data?.error || 'Failed') }
        }} disabled={!curPass || !newPass} className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50">Change Password</button>
      </div>
    </div>
  )
}
