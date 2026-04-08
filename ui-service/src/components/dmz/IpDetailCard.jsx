export default function IpDetailCard({ data }) {
  if (!data) return null

  const tracked = data.status !== 'not_tracked'

  return (
    <div className="rounded-xl border border-gray-200 bg-white overflow-hidden">
      <div className="px-4 py-3 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-900">IP Intelligence</h3>
        <span className="font-mono text-sm text-blue-700 font-bold">{data.ip}</span>
      </div>

      {!tracked ? (
        <p className="px-4 py-6 text-center text-sm text-gray-400">IP not currently tracked by connection tracker</p>
      ) : (
        <div className="p-4 grid grid-cols-2 gap-3 text-sm">
          {data.country && (
            <div>
              <span className="text-gray-500 text-xs">Country</span>
              <p className="font-medium text-gray-900">{data.country}</p>
            </div>
          )}
          {data.protocol && (
            <div>
              <span className="text-gray-500 text-xs">Protocol</span>
              <p className="font-medium text-gray-900">{data.protocol}</p>
            </div>
          )}
          {data.activeConnections !== undefined && (
            <div>
              <span className="text-gray-500 text-xs">Active Connections</span>
              <p className="font-bold text-gray-900">{data.activeConnections}</p>
            </div>
          )}
          {data.totalConnections !== undefined && (
            <div>
              <span className="text-gray-500 text-xs">Total Connections</span>
              <p className="font-bold text-gray-900">{data.totalConnections}</p>
            </div>
          )}
          {data.totalBytes !== undefined && (
            <div>
              <span className="text-gray-500 text-xs">Bytes Transferred</span>
              <p className="font-mono text-gray-900">{(data.totalBytes / 1048576).toFixed(2)} MB</p>
            </div>
          )}
          {data.lastSeen && (
            <div>
              <span className="text-gray-500 text-xs">Last Seen</span>
              <p className="text-gray-900">{new Date(data.lastSeen).toLocaleString()}</p>
            </div>
          )}
          {data.riskScore !== undefined && (
            <div>
              <span className="text-gray-500 text-xs">Risk Score</span>
              <p className={`font-bold ${data.riskScore > 70 ? 'text-red-600' : data.riskScore > 30 ? 'text-amber-600' : 'text-green-600'}`}>
                {data.riskScore}
              </p>
            </div>
          )}
          {data.verdict && (
            <div>
              <span className="text-gray-500 text-xs">AI Verdict</span>
              <p className={`font-medium ${
                data.verdict === 'ALLOW' ? 'text-green-600'
                  : data.verdict === 'BLOCK' ? 'text-red-600'
                  : 'text-amber-600'
              }`}>
                {data.verdict}
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
