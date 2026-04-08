import { useState } from 'react'
import { PlusIcon, TrashIcon, ShieldExclamationIcon, ShieldCheckIcon } from '@heroicons/react/24/outline'

export default function IpRulePanel({ title, type, ips = [], onAdd, onRemove, loading }) {
  const [newIp, setNewIp] = useState('')
  const isBlacklist = type === 'blacklist'

  const handleAdd = () => {
    const trimmed = newIp.trim()
    if (!trimmed) return
    // Basic IP/CIDR validation
    if (!/^[\d.:a-fA-F]+(\/([\d]+))?$/.test(trimmed)) {
      return
    }
    onAdd(trimmed)
    setNewIp('')
  }

  const Icon = isBlacklist ? ShieldExclamationIcon : ShieldCheckIcon
  const colors = isBlacklist
    ? { border: 'border-red-200', bg: 'bg-red-50', icon: 'text-red-600', badge: 'bg-red-100 text-red-700', input: 'focus:ring-red-400' }
    : { border: 'border-green-200', bg: 'bg-green-50', icon: 'text-green-600', badge: 'bg-green-100 text-green-700', input: 'focus:ring-green-400' }

  return (
    <div className={`rounded-xl border ${colors.border} overflow-hidden`}>
      <div className={`${colors.bg} px-4 py-3 flex items-center gap-2 border-b ${colors.border}`}>
        <Icon className={`w-5 h-5 ${colors.icon}`} />
        <h3 className="text-sm font-semibold text-gray-900">{title}</h3>
        <span className={`ml-auto px-2 py-0.5 rounded-full text-xs font-medium ${colors.badge}`}>
          {ips.length}
        </span>
      </div>

      {/* Add form */}
      <div className="px-4 py-3 border-b border-gray-100 flex gap-2">
        <input
          type="text"
          value={newIp}
          onChange={(e) => setNewIp(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
          placeholder="IP or CIDR (e.g. 10.0.0.1 or 10.0.0.0/24)"
          className={`flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 ${colors.input}`}
          disabled={loading}
        />
        <button
          onClick={handleAdd}
          disabled={loading || !newIp.trim()}
          className={`px-3 py-2 rounded-lg text-sm font-medium text-white transition-colors disabled:opacity-50 ${
            isBlacklist ? 'bg-red-600 hover:bg-red-700' : 'bg-green-600 hover:bg-green-700'
          }`}
        >
          <PlusIcon className="w-4 h-4" />
        </button>
      </div>

      {/* IP list */}
      <div className="max-h-64 overflow-y-auto">
        {ips.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-gray-400">
            No {isBlacklist ? 'blacklisted' : 'whitelisted'} IPs
          </p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {ips.map((ip) => (
              <li key={ip} className="px-4 py-2.5 flex items-center justify-between hover:bg-gray-50">
                <span className="text-sm font-mono text-gray-700">{ip}</span>
                <button
                  onClick={() => onRemove(ip)}
                  disabled={loading}
                  className="p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 transition-colors disabled:opacity-50"
                >
                  <TrashIcon className="w-4 h-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
