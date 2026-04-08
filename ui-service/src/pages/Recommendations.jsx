import { useQuery } from '@tanstack/react-query'
import { aiApi } from '../api/client'
import LoadingSpinner from '../components/LoadingSpinner'
import StatCard from '../components/StatCard'
import { LightBulbIcon, ExclamationTriangleIcon, ShieldCheckIcon, CpuChipIcon, BanknotesIcon, ChartBarIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const categoryConfig = {
  PERFORMANCE: { icon: ChartBarIcon, color: 'purple', bg: 'bg-purple-50', border: 'border-purple-200', text: 'text-purple-800' },
  SCALING: { icon: CpuChipIcon, color: 'blue', bg: 'bg-blue-50', border: 'border-blue-200', text: 'text-blue-800' },
  RELIABILITY: { icon: ExclamationTriangleIcon, color: 'amber', bg: 'bg-amber-50', border: 'border-amber-200', text: 'text-amber-800' },
  SECURITY: { icon: ShieldCheckIcon, color: 'red', bg: 'bg-red-50', border: 'border-red-200', text: 'text-red-800' },
  COST: { icon: BanknotesIcon, color: 'green', bg: 'bg-green-50', border: 'border-green-200', text: 'text-green-800' },
  COMPLIANCE: { icon: ShieldCheckIcon, color: 'blue', bg: 'bg-blue-50', border: 'border-blue-200', text: 'text-blue-800' },
}

const severityBadge = {
  CRITICAL: 'bg-red-100 text-red-800 border border-red-200',
  WARNING: 'bg-amber-100 text-amber-800 border border-amber-200',
  INFO: 'bg-blue-100 text-blue-800 border border-blue-200',
}

export default function Recommendations() {
  const { data: recs = [], isLoading } = useQuery({
    queryKey: ['recommendations'],
    queryFn: () => aiApi.get('/api/v1/ai/recommendations').then(r => r.data).catch(() => []),
    refetchInterval: 60000
  })
  const { data: summary = {} } = useQuery({
    queryKey: ['rec-summary'],
    queryFn: () => aiApi.get('/api/v1/ai/recommendations/summary').then(r => r.data).catch(() => ({})),
    refetchInterval: 60000
  })

  if (isLoading) return <LoadingSpinner />

  const critical = recs.filter(r => r.severity === 'CRITICAL').length
  const warnings = recs.filter(r => r.severity === 'WARNING').length
  const byCategory = {}
  recs.forEach(r => { byCategory[r.category] = (byCategory[r.category] || 0) + 1 })

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
            <LightBulbIcon className="w-7 h-7 text-amber-500" /> AI Recommendations
          </h1>
          <p className="text-gray-500 text-sm">AI-powered analysis of platform health, performance, and security</p>
        </div>
        <div className="text-right text-xs text-gray-400">
          <p>Last analysis: {summary.lastAnalysis ? format(new Date(summary.lastAnalysis), 'HH:mm:ss') : 'pending'}</p>
          <p>Auto-refreshes every 5 min</p>
        </div>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard title="Recommendations" value={recs.length} icon={LightBulbIcon} color="amber" />
        <StatCard title="Critical" value={critical} icon={ExclamationTriangleIcon} color={critical > 0 ? 'red' : 'green'} />
        <StatCard title="Transfers/Hour" value={summary.transfersLastHour || 0} icon={ChartBarIcon} color="blue" />
        <StatCard title="Error Rate" value={(summary.errorRateLastHour || 0) + '%'} icon={ShieldCheckIcon} color={summary.errorRateLastHour > 5 ? 'red' : 'green'} />
      </div>

      {/* Platform health summary */}
      {Object.keys(summary).length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-3">Platform Health Snapshot</h3>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 text-sm">
            {[
              ['Transfers/Day', summary.transfersLastDay || 0],
              ['Peak/Min', summary.peakTransfersPerMin || 0],
              ['Stuck Transfers', summary.stuckTransfers || 0],
              ['Login Failures/hr', summary.loginFailuresLastHour || 0],
              ['Avg Latency', (summary.avgTransferLatencyMs || 0) + 'ms'],
              ['Services', summary.registeredServices || 0],
              ['Total Records', summary.totalTransferRecords || 0],
            ].map(([label, value]) => (
              <div key={label} className="p-3 bg-gray-50 rounded-lg">
                <p className="text-xs text-gray-500">{label}</p>
                <p className="font-bold text-gray-900">{typeof value === 'number' ? value.toLocaleString() : value}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Recommendations by category */}
      {recs.length === 0 ? (
        <div className="card text-center py-12">
          <ShieldCheckIcon className="w-12 h-12 text-green-400 mx-auto mb-3" />
          <h3 className="text-lg font-semibold text-gray-900">All Clear</h3>
          <p className="text-sm text-gray-500 mt-1">No recommendations at this time. Platform is healthy.</p>
        </div>
      ) : (
        <div className="space-y-4">
          {recs.sort((a, b) => {
            const sev = { CRITICAL: 0, WARNING: 1, INFO: 2 }
            return (sev[a.severity] || 3) - (sev[b.severity] || 3)
          }).map(rec => {
            const cfg = categoryConfig[rec.category] || categoryConfig.RELIABILITY
            const Icon = cfg.icon
            return (
              <div key={rec.id} className={`card border ${cfg.border} ${cfg.bg}`}>
                <div className="flex items-start gap-3">
                  <Icon className={`w-5 h-5 ${cfg.text} flex-shrink-0 mt-0.5`} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className={`badge ${severityBadge[rec.severity]}`}>{rec.severity}</span>
                      <span className="badge badge-gray">{rec.category}</span>
                      <span className="text-xs text-gray-400 ml-auto">{rec.generatedAt ? format(new Date(rec.generatedAt), 'HH:mm') : ''}</span>
                    </div>
                    <p className={`font-medium ${cfg.text}`}>{rec.finding}</p>
                    <div className="mt-2 p-3 bg-white bg-opacity-60 rounded-lg">
                      <p className="text-xs font-semibold text-gray-700 mb-1">Recommended Action:</p>
                      <p className="text-sm text-gray-700 whitespace-pre-line">{rec.recommendedAction}</p>
                    </div>
                    {rec.data && Object.keys(rec.data).length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-2">
                        {Object.entries(rec.data).map(([k, v]) => (
                          <span key={k} className="text-xs bg-white bg-opacity-60 px-2 py-0.5 rounded font-mono">
                            {k}: {typeof v === 'number' ? v.toLocaleString() : String(v)}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
