import { useState } from 'react'
import { ChevronDownIcon, ChevronUpIcon, ShieldCheckIcon } from '@heroicons/react/24/outline'

const TIER_INFO = {
  MANUAL: {
    label: 'Manual Rules',
    badge: 'badge-yellow',
    badgeLabel: 'Manual',
    overhead: '<1ms',
    description: 'IP whitelist/blacklist, rate limiting, geo-blocking. Zero network calls.',
  },
  AI: {
    label: 'AI Screening',
    badge: 'badge-blue',
    badgeLabel: 'AI',
    overhead: '~5ms avg',
    description: 'Internal AI engine screening (IP reputation, anomaly detection, protocol threats). 90%+ cache hit rate.',
  },
  AI_LLM: {
    label: 'AI + LLM',
    badge: 'badge-purple',
    badgeLabel: 'AI+LLM',
    overhead: '~50ms avg',
    description: 'AI + Claude LLM for uncertain connections. LLM only triggers for ~5-10% of connections.',
  },
}

const DEFAULT_POLICY = {
  ipWhitelist: '',
  ipBlacklist: '',
  geoAllowed: '',
  geoBlocked: '',
  rateLimitPerMin: 60,
  maxConcurrent: 20,
  maxBandwidthMbPerMin: 500,
  maxAuthAttempts: 5,
  idleTimeoutSeconds: 300,
  requireEncryption: false,
  connectionLogging: true,
  allowedFileExtensions: '',
  blockedFileExtensions: '',
  maxFileSizeMb: 0,
}

export default function SecurityTierSelector({
  tier = 'MANUAL',
  onTierChange,
  showAiTiers = true,
  policy = {},
  onPolicyChange,
  llmEnabled = false,
  compact = false,
}) {
  const [rulesExpanded, setRulesExpanded] = useState(false)

  const mergedPolicy = { ...DEFAULT_POLICY, ...policy }

  const updatePolicy = (key, value) => {
    onPolicyChange?.({ ...mergedPolicy, [key]: value })
  }

  const tiers = [
    'MANUAL',
    ...(showAiTiers ? ['AI'] : []),
    ...(showAiTiers ? ['AI_LLM'] : []),
  ]

  const currentInfo = TIER_INFO[tier] || TIER_INFO.MANUAL

  // Compact mode — just a badge
  if (compact) {
    return (
      <span className={`badge ${currentInfo.badge}`}>
        {currentInfo.badgeLabel}
      </span>
    )
  }

  return (
    <div className="space-y-3">
      {/* Tier selector header */}
      <div className="flex items-center gap-2">
        <ShieldCheckIcon className="w-4 h-4 text-gray-500" />
        <span className="text-xs text-gray-500 uppercase tracking-wider font-semibold">Security Tier</span>
      </div>

      {/* Tier options */}
      <div className="space-y-2">
        {tiers.map(t => {
          const info = TIER_INFO[t]
          const isDisabled = t === 'AI_LLM' && !llmEnabled
          const isSelected = tier === t

          return (
            <button
              key={t}
              type="button"
              disabled={isDisabled}
              onClick={() => !isDisabled && onTierChange?.(t)}
              className={`w-full text-left p-3 rounded-lg border transition-all ${
                isSelected
                  ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-200'
                  : isDisabled
                    ? 'border-gray-200 bg-gray-50 opacity-50 cursor-not-allowed'
                    : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50 cursor-pointer'
              }`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className={`w-3 h-3 rounded-full border-2 flex items-center justify-center ${
                    isSelected ? 'border-blue-500' : 'border-gray-300'
                  }`}>
                    {isSelected && <div className="w-1.5 h-1.5 rounded-full bg-blue-500" />}
                  </div>
                  <span className={`text-sm font-medium ${isSelected ? 'text-blue-900' : 'text-gray-700'}`}>
                    {info.label}
                  </span>
                </div>
                <span className={`text-xs font-mono px-2 py-0.5 rounded ${
                  isSelected ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-500'
                }`}>
                  {info.overhead}
                </span>
              </div>
              <p className={`text-xs mt-1 ml-5 ${isSelected ? 'text-blue-700' : 'text-gray-400'}`}>
                {info.description}
              </p>
              {isDisabled && (
                <p className="text-xs mt-1 ml-5 text-amber-600">
                  Enable in Settings &gt; AI to use LLM verification
                </p>
              )}
            </button>
          )
        })}
      </div>

      {/* Collapsible Security Rules */}
      <div className="border rounded-lg overflow-hidden">
        <button
          type="button"
          onClick={() => setRulesExpanded(!rulesExpanded)}
          className="w-full flex items-center justify-between p-3 bg-gray-50 hover:bg-gray-100 transition-colors"
        >
          <span className="text-sm font-medium text-gray-700">Security Rules</span>
          {rulesExpanded
            ? <ChevronUpIcon className="w-4 h-4 text-gray-400" />
            : <ChevronDownIcon className="w-4 h-4 text-gray-400" />}
        </button>

        {rulesExpanded && (
          <div className="p-4 space-y-4 border-t">
            {/* IP Rules */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">IP Whitelist</label>
                <textarea
                  value={mergedPolicy.ipWhitelist}
                  onChange={e => updatePolicy('ipWhitelist', e.target.value)}
                  placeholder="192.168.1.0/24&#10;10.0.0.0/8"
                  rows={3}
                  className="w-full text-sm font-mono rounded-lg border-gray-300 focus:border-blue-500 focus:ring-blue-500"
                />
                <p className="text-xs text-gray-400 mt-1">One IP or CIDR per line</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">IP Blacklist</label>
                <textarea
                  value={mergedPolicy.ipBlacklist}
                  onChange={e => updatePolicy('ipBlacklist', e.target.value)}
                  placeholder="203.0.113.0/24&#10;198.51.100.5"
                  rows={3}
                  className="w-full text-sm font-mono rounded-lg border-gray-300 focus:border-blue-500 focus:ring-blue-500"
                />
                <p className="text-xs text-gray-400 mt-1">One IP or CIDR per line</p>
              </div>
            </div>

            {/* Geo Rules */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Geo-Allowed Countries</label>
                <input
                  value={mergedPolicy.geoAllowed}
                  onChange={e => updatePolicy('geoAllowed', e.target.value)}
                  placeholder="US,CA,GB"
                  className="w-full text-sm"
                />
                <p className="text-xs text-gray-400 mt-1">Comma-separated ISO codes (empty = allow all)</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Geo-Blocked Countries</label>
                <input
                  value={mergedPolicy.geoBlocked}
                  onChange={e => updatePolicy('geoBlocked', e.target.value)}
                  placeholder="KP,IR,CU"
                  className="w-full text-sm"
                />
                <p className="text-xs text-gray-400 mt-1">Comma-separated ISO codes</p>
              </div>
            </div>

            {/* Rate & Connection Limits */}
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Rate Limit / min</label>
                <input
                  type="number"
                  value={mergedPolicy.rateLimitPerMin}
                  onChange={e => updatePolicy('rateLimitPerMin', parseInt(e.target.value) || 0)}
                  min={0}
                  className="w-full text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Max Concurrent</label>
                <input
                  type="number"
                  value={mergedPolicy.maxConcurrent}
                  onChange={e => updatePolicy('maxConcurrent', parseInt(e.target.value) || 0)}
                  min={0}
                  className="w-full text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Max Bandwidth MB/min</label>
                <input
                  type="number"
                  value={mergedPolicy.maxBandwidthMbPerMin}
                  onChange={e => updatePolicy('maxBandwidthMbPerMin', parseInt(e.target.value) || 0)}
                  min={0}
                  className="w-full text-sm"
                />
              </div>
            </div>

            {/* Auth & Timeout */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Max Auth Attempts</label>
                <input
                  type="number"
                  value={mergedPolicy.maxAuthAttempts}
                  onChange={e => updatePolicy('maxAuthAttempts', parseInt(e.target.value) || 0)}
                  min={0}
                  className="w-full text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Idle Timeout (seconds)</label>
                <input
                  type="number"
                  value={mergedPolicy.idleTimeoutSeconds}
                  onChange={e => updatePolicy('idleTimeoutSeconds', parseInt(e.target.value) || 0)}
                  min={0}
                  className="w-full text-sm"
                />
              </div>
            </div>

            {/* Checkboxes */}
            <div className="flex items-center gap-6">
              <label className="flex items-center gap-2 text-sm text-gray-700">
                <input
                  type="checkbox"
                  checked={mergedPolicy.requireEncryption}
                  onChange={e => updatePolicy('requireEncryption', e.target.checked)}
                  className="w-4 h-4 text-blue-600 rounded border-gray-300"
                />
                Require Encryption
              </label>
              <label className="flex items-center gap-2 text-sm text-gray-700">
                <input
                  type="checkbox"
                  checked={mergedPolicy.connectionLogging}
                  onChange={e => updatePolicy('connectionLogging', e.target.checked)}
                  className="w-4 h-4 text-blue-600 rounded border-gray-300"
                />
                Connection Logging
              </label>
            </div>

            {/* File Rules */}
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Allowed File Extensions</label>
                <input
                  value={mergedPolicy.allowedFileExtensions}
                  onChange={e => updatePolicy('allowedFileExtensions', e.target.value)}
                  placeholder=".csv,.xml,.edi"
                  className="w-full text-sm"
                />
                <p className="text-xs text-gray-400 mt-1">Comma-separated (empty = allow all)</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Blocked File Extensions</label>
                <input
                  value={mergedPolicy.blockedFileExtensions}
                  onChange={e => updatePolicy('blockedFileExtensions', e.target.value)}
                  placeholder=".exe,.bat,.sh"
                  className="w-full text-sm"
                />
                <p className="text-xs text-gray-400 mt-1">Comma-separated</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Max File Size (MB)</label>
                <input
                  type="number"
                  value={mergedPolicy.maxFileSizeMb}
                  onChange={e => updatePolicy('maxFileSizeMb', parseInt(e.target.value) || 0)}
                  min={0}
                  className="w-full text-sm"
                />
                <p className="text-xs text-gray-400 mt-1">0 = unlimited</p>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
