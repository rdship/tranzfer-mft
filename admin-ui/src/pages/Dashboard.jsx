import { useQuery } from '@tanstack/react-query'
import { getDashboard, getPredictions } from '../api/analytics'
import StatCard from '../components/StatCard'
import LoadingSpinner from '../components/LoadingSpinner'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts'
import { ChartBarIcon, ArrowUpTrayIcon, CheckCircleIcon, ServerIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'
import { format } from 'date-fns'

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6']

export default function Dashboard() {
  const { data: dashboard, isLoading } = useQuery({ queryKey: ['dashboard'], queryFn: getDashboard, refetchInterval: 30000 })
  const { data: predictions } = useQuery({ queryKey: ['predictions'], queryFn: getPredictions })

  if (isLoading) return <LoadingSpinner text="Loading dashboard..." />

  const protocolData = Object.entries(dashboard?.transfersByProtocol || {}).map(([name, value]) => ({ name, value }))

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-500 text-sm mt-1">Platform overview — last updated {format(new Date(), 'HH:mm:ss')}</p>
      </div>

      {/* Alerts */}
      {dashboard?.alerts?.length > 0 && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-4">
          <h3 className="font-semibold text-red-800 flex items-center gap-2">
            <ExclamationTriangleIcon className="w-4 h-4" /> Active Alerts ({dashboard.alerts.length})
          </h3>
          {dashboard.alerts.map((alert, i) => (
            <div key={i} className="mt-2 text-sm text-red-700">
              {alert.ruleName} — {alert.serviceType}: {alert.metric} = {alert.currentValue.toFixed(3)} (threshold: {alert.threshold})
            </div>
          ))}
        </div>
      )}

      {/* Stat Cards */}
      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
        <StatCard title="Transfers Today" value={dashboard?.totalTransfersToday?.toLocaleString() || 0} icon={ArrowUpTrayIcon} color="blue" />
        <StatCard title="Success Rate" value={`${((dashboard?.successRateToday || 1) * 100).toFixed(1)}%`} icon={CheckCircleIcon} color="green" />
        <StatCard title="Last Hour" value={dashboard?.totalTransfersLastHour?.toLocaleString() || 0} icon={ChartBarIcon} color="amber" />
        <StatCard title="Data Transferred" value={`${(dashboard?.totalGbToday || 0).toFixed(2)} GB`} icon={ServerIcon} color="purple" />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-3 gap-6">
        <div className="card col-span-2">
          <h3 className="font-semibold text-gray-900 mb-4">Transfers per Hour (last 24h)</h3>
          {dashboard?.transfersPerHour?.length > 0 ? (
            <ResponsiveContainer width="100%" height={220}>
              <LineChart data={dashboard.transfersPerHour}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="hour" tick={{ fontSize: 11 }} />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Line type="monotone" dataKey="transfers" stroke="#3b82f6" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <div className="h-56 flex items-center justify-center text-gray-400 text-sm">No data yet — transfers will appear here after first hour</div>
          )}
        </div>

        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-4">By Protocol</h3>
          {protocolData.length > 0 ? (
            <ResponsiveContainer width="100%" height={180}>
              <PieChart>
                <Pie data={protocolData} cx="50%" cy="50%" innerRadius={50} outerRadius={80} dataKey="value" label={({ name }) => name}>
                  {protocolData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <div className="h-44 flex items-center justify-center text-gray-400 text-sm">No protocol data</div>
          )}
        </div>
      </div>

      {/* Scaling Recommendations */}
      {predictions?.some(p => p.recommendedReplicas > 1) && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-4">Scaling Recommendations</h3>
          <div className="grid grid-cols-2 lg:grid-cols-3 gap-4">
            {predictions.filter(p => p.recommendedReplicas > 1).map(rec => (
              <div key={rec.serviceType} className="bg-amber-50 border border-amber-100 rounded-lg p-4">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-medium text-gray-900">{rec.serviceType}</span>
                  <span className={`badge ${rec.trend === 'INCREASING' ? 'badge-red' : 'badge-yellow'}`}>{rec.trend}</span>
                </div>
                <p className="text-sm text-gray-600">Predicted 24h: <strong>{Math.round(rec.predictedLoad24h)}</strong> transfers</p>
                <p className="text-sm font-semibold text-amber-700 mt-1">↑ Scale to {rec.recommendedReplicas} replicas</p>
                <p className="text-xs text-gray-500 mt-1 leading-tight">{rec.reason}</p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
