import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getExternalDestinations, createExternalDestination, deleteExternalDestination } from '../api/config'
import { testEndpointConnection } from '../api/forwarder'
import { keystoreApi } from '../api/client'
import { useServices } from '../context/ServiceContext'
import SecurityTierSelector from '../components/SecurityTierSelector'
import ProtocolSecurityConfig from '../components/ProtocolSecurityConfig'
import LoadingSpinner from '../components/LoadingSpinner'
import EmptyState from '../components/EmptyState'
import Modal from '../components/Modal'
import { friendlyError } from '../components/FormField'
import toast from 'react-hot-toast'
import { PlusIcon, TrashIcon, SignalIcon } from '@heroicons/react/24/outline'
import { useState } from 'react'

export default function ExternalDestinations() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [form, setForm] = useState({
    name: '', type: 'SFTP', host: '', port: 22, username: '', encryptedPassword: '', remotePath: '/incoming',
    url: '', authType: 'NONE', sshKeyAlias: '', certAlias: '', passiveMode: false, bearerToken: '',
    proxyEnabled: false, proxyType: 'DMZ', proxyHost: 'dmz-proxy', proxyPort: 8088,
    securityTier: 'RULES', securityPolicy: {},
    protocolCredentials: {}
  })
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const { services } = useServices() || { services: {} }
  const dmzDetected = services?.dmz !== false

  // Keystore queries for protocol-specific dropdowns
  const { data: sshKeys = [] } = useQuery({
    queryKey: ['keystore-ssh-keys'],
    queryFn: () => keystoreApi.get('/api/v1/keys?type=SSH_USER').then(r => r.data).catch(() => [])
  })
  const { data: tlsCerts = [] } = useQuery({
    queryKey: ['keystore-tls-certs'],
    queryFn: () => keystoreApi.get('/api/v1/keys?type=TLS').then(r => r.data).catch(() => [])
  })

  // Reset protocol-specific fields when type changes
  const handleTypeChange = (newType) => {
    const portMap = { SFTP: 22, FTP: 21, FTPS: 990, HTTP: 80, HTTPS: 443, API: 443 }
    setForm(f => ({
      ...f,
      type: newType,
      port: portMap[newType] || 22,
      // Reset protocol-specific fields
      host: ['HTTPS', 'API', 'HTTP'].includes(newType) ? '' : f.host,
      url: '',
      authType: 'NONE',
      sshKeyAlias: '',
      certAlias: '',
      passiveMode: false,
      username: ['HTTPS', 'API'].includes(newType) ? '' : f.username,
      encryptedPassword: ['HTTPS', 'API'].includes(newType) ? '' : f.encryptedPassword,
      bearerToken: '',
      protocolCredentials: {},
    }))
    setCreateErrors({})
  }

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
  const [createErrors, setCreateErrors] = useState({})
  const createMut = useMutation({ mutationFn: createExternalDestination,
    onSuccess: () => { qc.invalidateQueries(['ext-dests']); setShowCreate(false); setCreateErrors({}); toast.success('Destination created') },
    onError: err => toast.error(friendlyError(err)) })
  const deleteMut = useMutation({ mutationFn: deleteExternalDestination,
    onSuccess: () => { qc.invalidateQueries(['ext-dests']); toast.success('Deleted') } })

  if (isLoading) return <LoadingSpinner />
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div><h1 className="text-2xl font-bold text-primary">External Destinations</h1>
          <p className="text-secondary text-sm">External endpoints (SFTP, FTP, FTPS, HTTP, HTTPS, API) for file forwarding</p></div>
        <button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" /> Add Destination</button>
      </div>
      {dests.length === 0 ? (
        <div className="card"><EmptyState title="No external destinations" description="Add partner SFTP/FTP servers to forward files externally." action={<button className="btn-primary" onClick={() => setShowCreate(true)}><PlusIcon className="w-4 h-4" />Add Destination</button>} /></div>
      ) : (
        <div className="space-y-3">
          {dests.map(d => (
            <div key={d.id} className="card flex items-center gap-4">
              <SignalIcon className="w-5 h-5 text-accent flex-shrink-0" />
              <div className="flex-1">
                <h3 className="font-semibold text-primary">{d.name}</h3>
                <p className="text-xs text-secondary font-mono">{d.type}://{d.username}@{d.host}:{d.port}{d.remotePath}</p>
              </div>
              <span className={`badge ${d.active ? 'badge-green' : 'badge-red'}`}>{d.active ? 'Active' : 'Disabled'}</span>
              <span className="badge badge-blue">{d.type}</span>
              {d.proxyEnabled && <span className="badge badge-purple">Via {d.proxyType || 'Proxy'}</span>}
              {['SFTP', 'FTPS', 'HTTPS'].includes(d.type) && (
                <span className="badge badge-green">{d.type === 'SFTP' ? 'SSH' : 'TLS'}</span>
              )}
              {['FTP', 'HTTP'].includes(d.type) && (
                <span className="badge badge-red">No TLS</span>
              )}
              <button onClick={() => { if(confirm('Delete?')) deleteMut.mutate(d.id) }} title="Delete destination" className="p-1.5 rounded hover:bg-[rgb(60,20,20)] text-[rgb(240,120,120)]"><TrashIcon className="w-4 h-4" /></button>
            </div>
          ))}
        </div>
      )}
      {showCreate && (
        <Modal title="Add External Destination" onClose={() => setShowCreate(false)} size="lg">
          <form onSubmit={e => {
            e.preventDefault()
            const errs = {}
            if (!form.name.trim()) errs.name = 'Destination name is required'
            if (['SFTP', 'FTP', 'FTPS'].includes(form.type)) {
              if (!form.host.trim()) errs.host = 'Host is required'
              if (!form.username.trim()) errs.username = 'Username is required'
              if (!form.encryptedPassword.trim() && !form.sshKeyAlias) errs.password = 'Password or SSH key is required'
            }
            if (['HTTPS', 'HTTP', 'API'].includes(form.type)) {
              if (!form.url.trim()) errs.url = 'URL is required'
            }
            setCreateErrors(errs)
            if (Object.keys(errs).length > 0) return
            createMut.mutate(form)
          }} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label>Name <span className="text-[rgb(240,120,120)]">*</span></label>
                <input value={form.name} onChange={e => { setForm(f => ({...f, name: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.name; return n }) }}
                  placeholder="partner-acme-sftp" className={createErrors.name ? 'border-red-300 focus:ring-red-500' : ''} />
                {createErrors.name ? <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.name}</p> : <p className="mt-1 text-xs text-muted">Unique identifier for this destination.</p>}
              </div>
              <div>
                <label>Type</label>
                <select value={form.type} onChange={e => handleTypeChange(e.target.value)}>
                  <option value="SFTP">SFTP</option>
                  <option value="FTP">FTP</option>
                  <option value="FTPS">FTPS</option>
                  <option value="HTTP">HTTP</option>
                  <option value="HTTPS">HTTPS</option>
                  <option value="API">API</option>
                </select>
                <p className="mt-1 text-xs text-muted">Protocol used to connect to the external system.</p>
              </div>
            </div>

            {/* ── SFTP-specific fields ── */}
            {form.type === 'SFTP' && (
              <>
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label>Host <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input value={form.host} onChange={e => { setForm(f => ({...f, host: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.host; return n }) }}
                      placeholder="sftp.partner.com" className={createErrors.host ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.host && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.host}</p>}
                  </div>
                  <div><label>Port</label><input type="number" value={form.port} onChange={e => setForm(f => ({...f, port: parseInt(e.target.value) || 22}))} /></div>
                  <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/incoming" /></div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label>Username <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input value={form.username} onChange={e => { setForm(f => ({...f, username: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.username; return n }) }}
                      className={createErrors.username ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.username && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.username}</p>}
                  </div>
                  <div>
                    <label>Password {!form.sshKeyAlias && <span className="text-[rgb(240,120,120)]">*</span>}</label>
                    <input type="password" value={form.encryptedPassword} onChange={e => { setForm(f => ({...f, encryptedPassword: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.password; return n }) }}
                      className={createErrors.password ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.password && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.password}</p>}
                  </div>
                </div>
                <div>
                  <label>SSH Key <span className="text-xs font-normal opacity-60">(from keystore — optional if password provided)</span></label>
                  <select value={form.sshKeyAlias} onChange={e => { setForm(f => ({...f, sshKeyAlias: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.password; return n }) }}>
                    <option value="">-- None (password auth) --</option>
                    {sshKeys.map(k => <option key={k.alias || k.id} value={k.alias}>{k.alias}{k.algorithm ? ` (${k.algorithm})` : ''}</option>)}
                  </select>
                  <p className="mt-1 text-xs text-muted">Select an SSH private key from the Keystore Manager for public-key authentication.</p>
                </div>
              </>
            )}

            {/* ── HTTPS-specific fields ── */}
            {form.type === 'HTTPS' && (
              <>
                <div>
                  <label>URL <span className="text-[rgb(240,120,120)]">*</span></label>
                  <input value={form.url} onChange={e => { setForm(f => ({...f, url: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.url; return n }) }}
                    placeholder="https://api.partner.com/upload" className={createErrors.url ? 'border-red-300 focus:ring-red-500' : ''} />
                  {createErrors.url && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.url}</p>}
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label>Auth Type</label>
                    <select value={form.authType} onChange={e => setForm(f => ({...f, authType: e.target.value, bearerToken: '', username: '', encryptedPassword: '', certAlias: ''}))}>
                      <option value="NONE">None</option>
                      <option value="BASIC">Basic Auth</option>
                      <option value="BEARER">Bearer Token</option>
                      <option value="CLIENT_CERT">Client Certificate (mTLS)</option>
                    </select>
                  </div>
                  {form.authType === 'BASIC' && (
                    <>
                      <div>
                        <label>Username</label>
                        <input value={form.username} onChange={e => setForm(f => ({...f, username: e.target.value}))} placeholder="api-user" />
                      </div>
                    </>
                  )}
                  {form.authType === 'BEARER' && (
                    <div>
                      <label>Bearer Token</label>
                      <input type="password" value={form.bearerToken} onChange={e => setForm(f => ({...f, bearerToken: e.target.value}))} placeholder="eyJhbGci..." />
                    </div>
                  )}
                  {form.authType === 'CLIENT_CERT' && (
                    <div>
                      <label>Client Certificate <span className="text-xs font-normal opacity-60">(from keystore)</span></label>
                      <select value={form.certAlias} onChange={e => setForm(f => ({...f, certAlias: e.target.value}))}>
                        <option value="">-- Select certificate --</option>
                        {tlsCerts.map(k => <option key={k.alias || k.id} value={k.alias}>{k.alias}{k.subjectDn ? ` (${k.subjectDn})` : ''}</option>)}
                      </select>
                    </div>
                  )}
                </div>
                {form.authType === 'BASIC' && (
                  <div>
                    <label>Password</label>
                    <input type="password" value={form.encryptedPassword} onChange={e => setForm(f => ({...f, encryptedPassword: e.target.value}))} />
                  </div>
                )}
                <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/upload" /></div>
              </>
            )}

            {/* ── FTP-specific fields ── */}
            {form.type === 'FTP' && (
              <>
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label>Host <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input value={form.host} onChange={e => { setForm(f => ({...f, host: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.host; return n }) }}
                      placeholder="ftp.partner.com" className={createErrors.host ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.host && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.host}</p>}
                  </div>
                  <div><label>Port</label><input type="number" value={form.port} onChange={e => setForm(f => ({...f, port: parseInt(e.target.value) || 21}))} /></div>
                  <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/incoming" /></div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label>Username <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input value={form.username} onChange={e => { setForm(f => ({...f, username: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.username; return n }) }}
                      className={createErrors.username ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.username && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.username}</p>}
                  </div>
                  <div>
                    <label>Password <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input type="password" value={form.encryptedPassword} onChange={e => { setForm(f => ({...f, encryptedPassword: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.password; return n }) }}
                      className={createErrors.password ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.password && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.password}</p>}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <input type="checkbox" id="passiveMode" checked={form.passiveMode}
                    onChange={e => setForm(f => ({...f, passiveMode: e.target.checked}))}
                    className="rounded border-border text-accent focus:ring-accent" />
                  <label htmlFor="passiveMode" className="text-sm font-medium text-primary">Passive Mode</label>
                  <span className="text-xs text-muted ml-2">Recommended when the server is behind a firewall or NAT</span>
                </div>
              </>
            )}

            {/* ── FTPS-specific fields (host/port/creds like FTP, TLS handled by ProtocolSecurityConfig) ── */}
            {form.type === 'FTPS' && (
              <>
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label>Host <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input value={form.host} onChange={e => { setForm(f => ({...f, host: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.host; return n }) }}
                      placeholder="ftps.partner.com" className={createErrors.host ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.host && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.host}</p>}
                  </div>
                  <div><label>Port</label><input type="number" value={form.port} onChange={e => setForm(f => ({...f, port: parseInt(e.target.value) || 990}))} /></div>
                  <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/incoming" /></div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label>Username <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input value={form.username} onChange={e => { setForm(f => ({...f, username: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.username; return n }) }}
                      className={createErrors.username ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.username && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.username}</p>}
                  </div>
                  <div>
                    <label>Password <span className="text-[rgb(240,120,120)]">*</span></label>
                    <input type="password" value={form.encryptedPassword} onChange={e => { setForm(f => ({...f, encryptedPassword: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.password; return n }) }}
                      className={createErrors.password ? 'border-red-300 focus:ring-red-500' : ''} />
                    {createErrors.password && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.password}</p>}
                  </div>
                </div>
              </>
            )}

            {/* ── HTTP-specific fields ── */}
            {form.type === 'HTTP' && (
              <>
                <div>
                  <label>URL <span className="text-[rgb(240,120,120)]">*</span></label>
                  <input value={form.url} onChange={e => { setForm(f => ({...f, url: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.url; return n }) }}
                    placeholder="http://partner.com/upload" className={createErrors.url ? 'border-red-300 focus:ring-red-500' : ''} />
                  {createErrors.url && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.url}</p>}
                </div>
                <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/upload" /></div>
              </>
            )}

            {/* ── API-specific fields ── */}
            {form.type === 'API' && (
              <>
                <div>
                  <label>URL <span className="text-[rgb(240,120,120)]">*</span></label>
                  <input value={form.url} onChange={e => { setForm(f => ({...f, url: e.target.value})); setCreateErrors(prev => { const n = {...prev}; delete n.url; return n }) }}
                    placeholder="https://api.partner.com/v1/files" className={createErrors.url ? 'border-red-300 focus:ring-red-500' : ''} />
                  {createErrors.url && <p className="mt-1 text-xs text-[rgb(240,120,120)]">{createErrors.url}</p>}
                </div>
                <div><label>Remote Path</label><input value={form.remotePath} onChange={e => setForm(f => ({...f, remotePath: e.target.value}))} placeholder="/v1/upload" /></div>
              </>
            )}

            {/* Protocol Security — TLS/SSH certificates & keys */}
            <div className="border-t pt-4 mt-4">
              <ProtocolSecurityConfig
                protocol={form.type}
                credentials={form.protocolCredentials}
                onCredentialsChange={creds => setForm(f => ({...f, protocolCredentials: creds}))}
                context="destination"
              />
            </div>

            {/* Proxy Configuration */}
            <div className="border-t pt-4 mt-4">
              <div className="flex items-center gap-2 mb-3">
                <input type="checkbox" id="proxyEnabled" checked={form.proxyEnabled}
                  onChange={e => setForm(f => ({...f, proxyEnabled: e.target.checked}))}
                  className="rounded border-border text-accent focus:ring-accent" />
                <label htmlFor="proxyEnabled" className="text-sm font-medium text-primary">
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
                          <option value="DMZ" disabled className="text-muted">DMZ Proxy (Offline)</option>
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
                    <p className="text-xs text-[rgb(120,220,160)] flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full bg-green-400 inline-block" />
                      DMZ Proxy detected and running on port 8088
                    </p>
                  )}
                  {form.proxyType === 'DMZ' && !dmzDetected && (
                    <div className="bg-[rgb(60,50,20)] border border-[rgb(80,65,25)] rounded-lg p-3">
                      <p className="text-xs text-[rgb(240,200,100)] flex items-center gap-1">
                        <span className="w-2 h-2 rounded-full bg-amber-400 inline-block" />
                        DMZ Proxy is offline — start the DMZ service or select a different proxy type
                      </p>
                    </div>
                  )}

                  {form.proxyType === 'DMZ' && (
                    <p className="text-xs text-secondary">
                      Traffic will be routed through the platform's DMZ Proxy for network isolation.
                    </p>
                  )}

                </div>
              )}
              {!form.proxyEnabled && dmzDetected && (
                <p className="text-xs text-accent mt-1">
                  DMZ Proxy is running — consider enabling proxy routing for network isolation
                </p>
              )}

              {/* Security Profile — always visible for all external destinations */}
              <div className="pt-3">
                <SecurityTierSelector
                  tier={form.securityTier}
                  onTierChange={tier => setForm(f => ({...f, securityTier: tier}))}
                  showAiTiers={form.proxyEnabled && form.proxyType === 'DMZ'}
                  policy={form.securityPolicy}
                  onPolicyChange={policy => setForm(f => ({...f, securityPolicy: policy}))}
                  llmEnabled={form.proxyEnabled && form.proxyType === 'DMZ'}
                />
              </div>
            </div>

            {/* Connection Test & Actions */}
            <div className="border-t pt-4 mt-4 space-y-3">
              <div className="flex items-center gap-3">
                <button type="button" className="btn-secondary" onClick={testConnection}
                  disabled={testing || (!form.host && !form.url)}>
                  {testing ? 'Testing...' : 'Test Connection'}
                </button>
                {testResult && (
                  <span className={`text-sm ${testResult.success ? 'text-[rgb(120,220,160)]' : 'text-[rgb(240,120,120)]'}`}>
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
