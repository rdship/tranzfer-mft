import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getExternalDestinations, createExternalDestination, deleteExternalDestination } from '../api/config'
import { testEndpointConnection } from '../api/forwarder'
import { useServices } from '../context/ServiceContext'
import SecurityTierSelector from '../components/SecurityTierSelector'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, SignalIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'

export default function ExternalDestinations() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({
    name: '', type: 'SFTP', host: '', port: 22, username: '', encryptedPassword: '', remotePath: '/incoming',
    proxyEnabled: false, proxyType: 'DMZ', proxyHost: 'dmz-proxy', proxyPort: 8088,
    securityTier: 'RULES', securityPolicy: {}
  })
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const { services } = useServices() || { services: {} }
  const dmzDetected = services?.dmz !== false

  const testConnection = async () => {
    setTesting(true)
    setTestResult(null)
    try {
      const data = await testEndpointConnection({
        host: form.host, port: form.port, protocol: form.type,
        username: form.username, password: form.encryptedPassword,
        proxyEnabled: form.proxyEnabled, proxyType: form.proxyType,
        proxyHost: form.proxyHost, proxyPort: form.proxyPort
      })
      setTestResult(data)
    } catch (err) {
      setTestResult({ success: false, message: 'Connection test failed: ' + (err.response?.data?.message || err.message) })
    }
    setTesting(false)
  }

  const { data: dests = [], isLoading } = useQuery({ queryKey: ['ext-dests'], queryFn: getExternalDestinations })
  const createMut = useMutation({ mutationFn: createExternalDestination,
    onSuccess: () => { qc.invalidateQueries(['ext-dests']); setShowCreate(false); toast.success('Destination created') },
    onError: err => toast.error(err.response?.data?.error || 'Failed') })
  const deleteMut = useMutation({ mutationFn: deleteExternalDestination,
    onSuccess: () => { qc.invalidateQueries(['ext-dests']); toast.success('Deleted') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-gray-900">External Destinations</h1>
          <p className="text-gray-500 text-sm">External endpoints (SFTP, FTP, FTPS, HTTP, HTTPS, API) for file forwarding</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> Add Destination</button>
      </div>
      {dests.length === 0 ? (
        <div className="card"><EmptyState title="No external destinations" description="Add partner SFTP/FTP servers to forward files externally." action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />Add Destination</button>} /></div>
      ) : (
        <div className="space-y-3">
          {dests.map(d => (
            <div key={d.id} className="card flex items-center gap-4">
              <SignalIcon className="w-5 h-5 text-blue-500 flex-shrink-0" />
              <div className="flex-1">
                <h3 className="font-semibold text-gray-900">{d.name}</h3>
                <p className="text-xs text-gray-500 font-mono">{d.type}://{d.username}@{d.host}:{d.port}{d.remotePath}</p>
              </div>
              <span className={`badge ${d.active ? 'badge-green' : 'badge-red'}`}>{d.active ? 'Active' : 'Disabled'}</span>
              <span className="badge badge-blue">{d.type}</span>
              {d.proxyEnabled && <span className="badge badge-purple">Via {d.proxyType || 'Proxy'}</span>}
              {d.proxyEnabled && (
                <span className="badge badge-yellow">Rules</span>
              )}
              {!d.proxyEnabled && (
                <span className="text-gray-400 text-xs">Direct</span>
              )}
              <button onClick={() => { if(confirm('Delete?')) deleteMut.mutate(d.id) }} className="p-1.5 rounded hover:bg-red-50 text-red-500"><TrashIcon className="w-4 h-4" /></button>
            </div>
          ))}
        </div>
      )}
      {showCreate && (
        <Modal title="Add External Destination" onClose={() => setShowCreate(false)} size="lg">
          <form onSubmit={e => { e.preventDefault(); createMut.mutate(form) }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div><label>Name</label><input value={form.name} onChange={e => setForm(f => ({...f, name: e.target.value}))} required placeholder="partner-acme-sftp" /></div>
              <div><label>Type</label><select value={form.type} onChange={e => setForm(f => ({...f, type: e.target.value, port: {SFTP:22,FTP:21,FTPS:990,HTTP:80,HTTPS:443,API:443}[e.target.value]||22}))}><option>SFTP</option><option>FTP</option><option>FTPS</option><option>HTTP</option><option>HTTPS</option><option>API</option></select></div>
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div><label>Host</label><input value={form.host} onChange={e => setForm(f => ({...f, host: e.target.value}))} required placeholder="sftp.partner.com" /></div>
              <div><label>Port</label><input type="number" value={form.port} onChange={e => setForm(f => ({...f, port: parseInt(e.target.value)}))} /></div>
              <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/incoming" /></div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div><label>Username</label><input value={form.username} onChange={e => setForm(f => ({...f, username: e.target.value}))} required /></div>
              <div><label>Password</label><input type="password" value={form.encryptedPassword} onChange={e => setForm(f => ({...f, encryptedPassword: e.target.value}))} required /></div>
            </div>

            {/* Proxy Configuration */}
            <div className="border-t pt-4 mt-4">
              <div className="flex items-center gap-2 mb-3">
                <input type="checkbox" id="proxyEnabled" checked={form.proxyEnabled}
                  onChange={e => setForm(f => ({...f, proxyEnabled: e.target.checked}))}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
                <label htmlFor="proxyEnabled" className="text-sm font-medium text-gray-700">
                  Route through proxy
                </label>
              </div>
              {form.proxyEnabled && (
                <div className="space-y-3">
                  <div className="grid grid-cols-3 gap-3">
                    <div>
                      <label>Proxy Type</label>
                      <select value={form.proxyType} onChange={e => setForm(f => ({...f, proxyType: e.target.value}))}>
                        {dmzDetected ? (
                          <option value="DMZ">DMZ Proxy (Platform)</option>
                        ) : (
                          <option value="DMZ" disabled className="text-gray-400">DMZ Proxy (Offline)</option>
                        )}
                        <option value="HTTP">HTTP Proxy</option>
                        <option value="SOCKS5">SOCKS5 Proxy</option>
                      </select>
                    </div>
                    <div>
                      <label>Proxy Host</label>
                      <input value={form.proxyHost}
                        onChange={e => setForm(f => ({...f, proxyHost: e.target.value}))}
                        placeholder={form.proxyType === 'DMZ' ? 'dmz-proxy' : 'proxy.company.com'} />
                    </div>
                    <div>
                      <label>Proxy Port</label>
                      <input type="number" value={form.proxyPort}
                        onChange={e => setForm(f => ({...f, proxyPort: parseInt(e.target.value) || 0}))} />
                    </div>
                  </div>

                  {/* DMZ detection status */}
                  {form.proxyType === 'DMZ' && dmzDetected && (
                    <p className="text-xs text-green-600 flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full bg-green-400 inline-block" />
                      DMZ Proxy detected and running on port 8088
                    </p>
                  )}
                  {form.proxyType === 'DMZ' && !dmzDetected && (
                    <div className="bg-amber-50 border border-amber-100 rounded-lg p-3">
                      <p className="text-xs text-amber-700 flex items-center gap-1">
                        <span className="w-2 h-2 rounded-full bg-amber-400 inline-block" />
                        DMZ Proxy is offline — start the DMZ service or select a different proxy type
                      </p>
                    </div>
                  )}

                  {form.proxyType === 'DMZ' && (
                    <p className="text-xs text-gray-500">
                      Traffic will be routed through the platform's DMZ Proxy for network isolation.
                    </p>
                  )}

                  {/* Outbound security — MANUAL only, no AI tiers */}
                  <div className="pt-2">
                    <SecurityTierSelector
                      tier="RULES"
                      onTierChange={() => {}} // locked to RULES for outbound
                      showAiTiers={false}
                      policy={form.securityPolicy}
                      onPolicyChange={policy => setForm(f => ({...f, securityPolicy: policy}))}
                      llmEnabled={false}
                    />
                  </div>
                </div>
              )}
              {!form.proxyEnabled && dmzDetected && (
                <p className="text-xs text-blue-600 mt-1">
                  DMZ Proxy is running — consider enabling proxy routing for network isolation
                </p>
              )}
            </div>

            {/* Connection Test & Actions */}
            <div className="border-t pt-4 mt-4 space-y-3">
              <div className="flex items-center gap-3">
                <button type="button" className="btn-secondary" onClick={testConnection}
                  disabled={testing || !form.host}>
                  {testing ? 'Testing...' : 'Test Connection'}
                </button>
                {testResult && (
                  <span className={`text-sm ${testResult.success ? 'text-green-600' : 'text-red-600'}`}>
                    {testResult.success ? '\u2713' : '\u2717'} {testResult.message}
                    {testResult.latencyMs != null && ` (${testResult.latencyMs}ms)`}
                  </span>
                )}
              </div>
            </div>

            <div className="flex gap-3 justify-end pt-2">
              <button type="button" className="btn-secondary" onClick={() => setShowCreate(false)}>Cancel</button>
              <button type="submit" className="btn-primary" disabled={createMut.isPending}>Create</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
