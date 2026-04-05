import { useQuery } from '@tanstack/react-query'
import { getDashboard, getPredictions } from '../api/analytics'
import { useAuth } from '../context/AuthContext'
import StatCard from '../components/StatCard'
import LoadingSpinner from '../components/LoadingSpinner'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell, AreaChart, Area } from 'recharts'
import {
  ChartBarIcon, ArrowUpTrayIcon, CheckCircleIcon, ServerIcon,
  ExclamationTriangleIcon, ArrowTrendingUpIcon, ClockIcon,
  ShieldCheckIcon, BoltIcon, ArrowsRightLeftIcon
} from '@heroicons/react/24/outline'
import { format } from 'date-fns'
import { NavLink } from 'react-router-dom'

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6']

function getGreeting() {
  const hour = new Date().getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

const quickActions = [
  { label: 'Transfer Accounts', to: '/accounts', icon: ArrowsRightLeftIcon, color: 'blue' },
  { label: 'Processing Flows', to: '/flows', icon: BoltIcon, color: 'purple' },
  { label: 'AS2/AS4 Partners', to: '/as2-partnerships', icon: ArrowsRightLeftIcon, color: 'green' },
  { label: 'Transfer Journey', to: '/journey', icon: ArrowTrendingUpIcon, color: 'amber' },
  { label: 'Security Profiles', to: '/security-profiles', icon: ShieldCheckIcon, color: 'red' },
  { label: 'Audit Logs', to: '/logs', icon: ClockIcon, color: 'gray' },
]

export default function Dashboard() {
  const { user } = useAuth()
  const { data: dashboard, isLoading } = useQuery({ queryKey: ['dashboard'], queryFn: getDashboard, refetchInterval: 30000 })
  const { data: predictions } = useQuery({ queryKey: ['predictions'], queryFn: getPredictions })

  if (isLoading) return <LoadingSpinner text="Loading dashboard..." />

  const protocolData = Object.entries(dashboard?.transfersByProtocol || {}).map(([name, value]) => ({ name, value }))
  const successRate = ((dashboard?.successRateToday || 1) * 100).toFixed(1)

  return (
    <div className="space-y-6">
      {/* Welcome Banner */}
      <div className="bg-gradient-to-r from-blue-600 to-blue-700 rounded-2xl p-6 text-white">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">{getGreeting()}, {user?.name || user?.email?.split('@')[0] || 'Admin'}</h1>
            <p className="text-blue-100 text-sm mt-1">
              Platform overview as of {format(new Date(), 'EEEE, MMMM d, yyyy')} at {format(new Date(), 'HH:mm')}
            </p>
          </div>
          <div className="hidden lg:flex items-center gap-6 text-sm">
            <div className="text-center">
              <p className="text-3xl font-bold">{dashboard?.totalTransfersToday?.toLocaleString() || 0}</p>
              <p className="text-blue-200 text-xs mt-0.5">Transfers Today</p>
            </div>
            <div className="w-px h-10 bg-blue-400/30" />
            <div className="text-center">
              <p className="text-3xl font-bold">{successRate}%</p>
              <p className="text-blue-200 text-xs mt-0.5">Success Rate</p>
            </div>
            <div className="w-px h-10 bg-blue-400/30" />
            <div className="text-center">
              <p className="text-3xl font-bold">{(dashboard?.totalGbToday || 0).toFixed(1)}<span className="text-lg ml-0.5">GB</span></p>
              <p className="text-blue-200 text-xs mt-0.5">Data Moved</p>
            </div>
          </div>
        </div>
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

      {/* Stat Cards — mobile visible */}
      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4 lg:hidden">
        <StatCard title="Transfers Today" value={dashboard?.totalTransfersToday?.toLocaleString() || 0} icon={ArrowUpTrayIcon} color="blue" />
        <StatCard title="Success Rate" value={`${successRate}%`} icon={CheckCircleIcon} color="green" />
        <StatCard title="Last Hour" value={dashboard?.totalTransfersLastHour?.toLocaleString() || 0} icon={ChartBarIcon} color="amber" />
        <StatCard title="Data Transferred" value={`${(dashboard?.totalGbToday || 0).toFixed(2)} GB`} icon={ServerIcon} color="purple" />
      </div>

      {/* Quick Actions */}
      <div>
        <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3">Quick Actions</h2>
        <div className="grid grid-cols-3 lg:grid-cols-6 gap-3">
          {quickActions.map(action => (
            <NavLink key={action.to} to={action.to}
              className="card !p-4 flex flex-col items-center gap-2 text-center hover:shadow-md transition-shadow cursor-pointer group">
              <div className={`p-2.5 rounded-xl bg-${action.color}-50 text-${action.color}-600 group-hover:bg-${action.color}-100 transition-colors`}>
                <action.icon className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium text-gray-700">{action.label}</span>
            </NavLink>
          ))}
        </div>
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="card lg:col-span-2">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-gray-900">Transfer Volume (24h)</h3>
            <span className="text-xs text-gray-400">{dashboard?.transfersPerHour?.length || 0} data points</span>
          </div>
          {dashboard?.transfersPerHour?.length > 0 ? (
            <ResponsiveContainer width="100%" height={240}>
              <AreaChart data={dashboard.transfersPerHour}>
                <defs>
                  <linearGradient id="fillBlue" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.15} />
                    <stop offset="100%" stopColor="#3b82f6" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="hour" tick={{ fontSize: 11 }} />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip contentStyle={{ borderRadius: '8px', border: '1px solid #e5e7eb', fontSize: '12px' }} />
                <Area type="monotone" dataKey="transfers" stroke="#3b82f6" strokeWidth={2} fill="url(#fillBlue)" />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <div className="h-60 flex flex-col items-center justify-center text-gray-400">
              <ChartBarIcon className="w-8 h-8 mb-2 text-gray-300" />
              <p className="text-sm">No data yet</p>
              <p className="text-xs mt-1">Transfers will appear here after the first hour</p>
            </div>
          )}
        </div>

        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-4">By Protocol</h3>
          {protocolData.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={180}>
                <PieChart>
                  <Pie data={protocolData} cx="50%" cy="50%" innerRadius={50} outerRadius={80} dataKey="value" label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}>
                    {protocolData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                  </Pie>
                  <Tooltip />
                </PieChart>
              </ResponsiveContainer>
              <div className="grid grid-cols-2 gap-2 mt-2">
                {protocolData.map((p, i) => (
                  <div key={p.name} className="flex items-center gap-2 text-xs">
                    <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                    <span className="text-gray-600">{p.name}</span>
                    <span className="font-semibold text-gray-900 ml-auto">{p.value}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="h-44 flex flex-col items-center justify-center text-gray-400">
              <ArrowsRightLeftIcon className="w-8 h-8 mb-2 text-gray-300" />
              <p className="text-sm">No protocol data</p>
            </div>
          )}
        </div>
      </div>

      {/* Scaling Recommendations */}
      {predictions?.some(p => p.recommendedReplicas > 1) && (
        <div className="card">
          <h3 className="font-semibold text-gray-900 mb-4 flex items-center gap-2">
            <ArrowTrendingUpIcon className="w-5 h-5 text-amber-500" /> Scaling Recommendations
          </h3>
          <div className="grid grid-cols-2 lg:grid-cols-3 gap-4">
            {predictions.filter(p => p.recommendedReplicas > 1).map(rec => (
              <div key={rec.serviceType} className="bg-amber-50 border border-amber-100 rounded-lg p-4">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-medium text-gray-900">{rec.serviceType}</span>
                  <span className={`badge ${rec.trend === 'INCREASING' ? 'badge-red' : 'badge-yellow'}`}>{rec.trend}</span>
                </div>
                <p className="text-sm text-gray-600">Predicted 24h: <strong>{Math.round(rec.predictedLoad24h)}</strong> transfers</p>
                <p className="text-sm font-semibold text-amber-700 mt-1">Scale to {rec.recommendedReplicas} replicas</p>
                <p className="text-xs text-gray-500 mt-1 leading-tight">{rec.reason}</p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
