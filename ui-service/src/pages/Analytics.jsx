import { useQuery } from '@tanstack/react-query'
import { getTimeSeries } from '../api/analytics'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, BarChart, Bar } from 'recharts'
import LoadingSpinner from '../components/LoadingSpinner'
import { useState } from 'react'

const SERVICES = ['ALL', 'SFTP', 'FTP', 'FTP_WEB', 'GATEWAY']

export default function Analytics() {
  const [service, setService] = useState('ALL')
  const [hours, setHours] = useState(24)
  const { data: series = [], isLoading } = useQuery({
    queryKey: ['timeseries', service, hours],
    queryFn: () => getTimeSeries(service, hours)
  })

  const chartData = series.map(s => ({
    time: new Date(s.snapshotTime).toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit' }),
    transfers: s.totalTransfers,
    success: s.successfulTransfers,
    failed: s.failedTransfers,
    latency: Math.round(s.avgLatencyMs),
    bytes: Math.round(s.totalBytesTransferred / (1024 * 1024))
  }))

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">Analytics</h1>
          <p className="text-gray-500 text-sm">Transfer metrics and trend analysis</p></div>
        <div className="flex gap-2">
          <select value={service} onChange={e => setService(e.target.value)} className="w-auto text-sm">
            {SERVICES.map(s => <option key={s}>{s}</option>)}
          </select>
          <select value={hours} onChange={e => setHours(Number(e.target.value))} className="w-auto text-sm">
            <option value={6}>Last 6h</option>
            <option value={24}>Last 24h</option>
            <option value={48}>Last 48h</option>
            <option value={168}>Last 7d</option>
          </select>
        </div>
      </div>

      {isLoading ? <LoadingSpinner /> : (
        <div className="space-y-6">
          <div className="card">
            <h3 className="font-semibold text-gray-900 mb-4">Transfer Volume</h3>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Bar dataKey="success" stackId="a" fill="#10b981" name="Success" />
                <Bar dataKey="failed" stackId="a" fill="#ef4444" name="Failed" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="card">
            <h3 className="font-semibold text-gray-900 mb-4">Avg Latency (ms)</h3>
            <ResponsiveContainer width="100%" height={180}>
              <LineChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Line type="monotone" dataKey="latency" stroke="#8b5cf6" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
          <div className="card">
            <h3 className="font-semibold text-gray-900 mb-4">Data Transferred (MB)</h3>
            <ResponsiveContainer width="100%" height={180}>
              <LineChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval="preserveStartEnd" />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Line type="monotone" dataKey="bytes" stroke="#3b82f6" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}
    </div>
  )
}
