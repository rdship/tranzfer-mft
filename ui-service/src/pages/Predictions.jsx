import { useQuery } from '@tanstack/react-query'
import { getPredictions } from '../api/analytics'
import LoadingSpinner from '../components/LoadingSpinner'

const trendColor = { INCREASING: 'text-red-600', DECREASING: 'text-green-600', STABLE: 'text-gray-600', SPIKE_DETECTED: 'text-orange-600' }
const trendIcon = { INCREASING: '↑', DECREASING: '↓', STABLE: '→', SPIKE_DETECTED: '⚡' }

export default function Predictions() {
  const { data: predictions = [], isLoading } = useQuery({ queryKey: ['predictions'], queryFn: getPredictions, refetchInterval: 300000 })
  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Scaling Predictions</h1>
        <p className="text-gray-500 text-sm">AI-driven capacity planning based on traffic trends</p></div>
      {predictions.length === 0 ? (
        <div className="card text-center py-12">
          <p className="text-gray-500 text-sm">No predictions available yet. Predictions are generated as transfer traffic builds up.</p>
        </div>
      ) : (
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-4">
        {predictions.map(p => (
          <div key={p.serviceType} className="card">
            <div className="flex items-center justify-between mb-3">
              <h3 className="font-semibold text-gray-900">{p.serviceType}</h3>
              <span className={`font-bold text-lg ${trendColor[p.trend] || 'text-gray-600'}`}>
                {trendIcon[p.trend]} {p.trend}
              </span>
            </div>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between"><span className="text-gray-500">Current Load</span><strong>{Math.round(p.currentLoad)}/hr</strong></div>
              <div className="flex justify-between"><span className="text-gray-500">Predicted 24h</span><strong>{Math.round(p.predictedLoad24h)}/hr</strong></div>
              <div className="flex justify-between"><span className="text-gray-500">Confidence</span><strong>{Math.round(p.confidence * 100)}%</strong></div>
              <div className="flex justify-between items-center">
                <span className="text-gray-500">Recommended Replicas</span>
                <span className={`badge ${p.recommendedReplicas > 1 ? 'badge-yellow' : 'badge-green'}`}>
                  {p.recommendedReplicas} replica{p.recommendedReplicas !== 1 ? 's' : ''}
                </span>
              </div>
            </div>
            {/* Load bar */}
            <div className="mt-3">
              <div className="flex justify-between text-xs text-gray-500 mb-1"><span>Current</span><span>Predicted</span></div>
              <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                <div className="h-full bg-blue-500 rounded-full transition-all" style={{ width: `${Math.min(100, (p.currentLoad / Math.max(p.predictedLoad24h, 1)) * 100)}%` }} />
              </div>
            </div>
            <p className="text-xs text-gray-500 mt-3 leading-relaxed">{p.reason}</p>
          </div>
        ))}
      </div>
      )}
    </div>
  )
}
