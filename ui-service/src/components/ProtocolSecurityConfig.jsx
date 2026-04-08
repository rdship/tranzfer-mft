import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getKeys, generateSshHost, generateSshUser, generateTls, importKey } from '../api/keystore'
import { useServices } from '../context/ServiceContext'
import toast from 'react-hot-toast'

/**
 * Protocol-aware security configuration for server instances and external destinations.
 *
 * Shows the right fields based on protocol:
 *   SFTP     → SSH host key, user auth method (password/key/both), known hosts
 *   FTP      → Warning (plaintext), optional FTPS upgrade
 *   FTPS     → TLS certificate, client auth, TLS version, implicit/explicit mode
 *   FTP_WEB  → TLS certificate, TLS version, cipher config
 *   HTTPS    → TLS certificate, mTLS client cert, TLS version
 *   API      → Auth method (API key/OAuth2/mTLS), TLS certificate
 *   HTTP     → Warning (plaintext), suggest HTTPS
 *
 * Integrates with Keystore Manager (port 8093) for key/cert selection and generation.
 */

const TLS_VERSIONS = ['TLS 1.2', 'TLS 1.3', 'TLS 1.2+']
const SSH_AUTH_METHODS = ['PASSWORD', 'PUBLIC_KEY', 'BOTH']
const API_AUTH_METHODS = ['API_KEY', 'OAUTH2', 'MTLS', 'BASIC']
const FTPS_MODES = ['EXPLICIT', 'IMPLICIT']

const DEFAULT_CREDENTIALS = {
  // SSH
  sshHostKeyAlias: '',
  sshAuthMethod: 'PASSWORD',
  sshUserKeyAlias: '',
  sshKnownHostsVerification: true,
  // TLS
  tlsCertAlias: '',
  tlsVersion: 'TLS 1.2+',
  tlsClientCertAlias: '',
  tlsMutualAuth: false,
  ftpsMode: 'EXPLICIT',
  // API
  apiAuthMethod: 'API_KEY',
  apiKeyHeader: 'X-API-Key',
  apiKeyValue: '',
  oauth2TokenUrl: '',
  oauth2ClientId: '',
  oauth2ClientSecret: '',
  oauth2Scope: '',
}

export default function ProtocolSecurityConfig({ protocol, credentials = {}, onCredentialsChange, context = 'server' }) {
  const [generating, setGenerating] = useState(false)
  const [importMode, setImportMode] = useState(null) // 'ssh'|'tls'|null
  const [importForm, setImportForm] = useState({ alias: '', keyMaterial: '', description: '' })
  const { services } = useServices() || { services: {} }
  const keystoreAvailable = services?.keystore !== false

  const creds = { ...DEFAULT_CREDENTIALS, ...credentials }
  const update = (key, val) => onCredentialsChange({ ...creds, [key]: val })

  // Fetch keys from keystore
  const { data: sshHostKeys = [] } = useQuery({
    queryKey: ['keys', 'SSH_HOST_KEY'],
    queryFn: () => getKeys('SSH_HOST_KEY'),
    enabled: keystoreAvailable && (protocol === 'SFTP'),
    staleTime: 30000,
  })
  const { data: sshUserKeys = [] } = useQuery({
    queryKey: ['keys', 'SSH_USER_KEY'],
    queryFn: () => getKeys('SSH_USER_KEY'),
    enabled: keystoreAvailable && (protocol === 'SFTP'),
    staleTime: 30000,
  })
  const { data: tlsCerts = [] } = useQuery({
    queryKey: ['keys', 'TLS_CERTIFICATE'],
    queryFn: () => getKeys('TLS_CERTIFICATE'),
    enabled: keystoreAvailable && ['FTPS', 'FTP_WEB', 'HTTPS', 'API'].includes(protocol),
    staleTime: 30000,
  })

  const handleGenerateSshHost = async () => {
    setGenerating(true)
    try {
      const alias = `ssh-host-${Date.now()}`
      const key = await generateSshHost({ alias, ownerService: 'ui-service' })
      update('sshHostKeyAlias', key.alias)
      toast.success(`SSH host key generated: ${key.alias}`)
    } catch (e) { toast.error('Failed to generate SSH host key: ' + (e.response?.data?.message || e.message)) }
    setGenerating(false)
  }

  const handleGenerateSshUser = async () => {
    setGenerating(true)
    try {
      const alias = `ssh-user-${Date.now()}`
      const key = await generateSshUser({ alias, keySize: 4096 })
      update('sshUserKeyAlias', key.alias)
      toast.success(`SSH user key generated: ${key.alias}`)
    } catch (e) { toast.error('Failed to generate SSH user key: ' + (e.response?.data?.message || e.message)) }
    setGenerating(false)
  }

  const handleGenerateTls = async () => {
    setGenerating(true)
    try {
      const alias = `tls-${protocol.toLowerCase()}-${Date.now()}`
      const cert = await generateTls({ alias, cn: 'localhost', validDays: 365 })
      update('tlsCertAlias', cert.alias)
      toast.success(`TLS certificate generated: ${cert.alias}`)
    } catch (e) { toast.error('Failed to generate TLS certificate: ' + (e.response?.data?.message || e.message)) }
    setGenerating(false)
  }

  const handleImport = async (keyType) => {
    if (!importForm.alias || !importForm.keyMaterial) { toast.error('Alias and key material are required'); return }
    setGenerating(true)
    try {
      const key = await importKey({
        alias: importForm.alias,
        keyType,
        keyMaterial: importForm.keyMaterial,
        description: importForm.description,
        ownerService: 'ui-service',
      })
      if (keyType === 'SSH_HOST_KEY') update('sshHostKeyAlias', key.alias)
      else if (keyType === 'SSH_USER_KEY') update('sshUserKeyAlias', key.alias)
      else update('tlsCertAlias', key.alias)
      toast.success(`Key imported: ${key.alias}`)
      setImportMode(null)
      setImportForm({ alias: '', keyMaterial: '', description: '' })
    } catch (e) { toast.error('Import failed: ' + (e.response?.data?.message || e.message)) }
    setGenerating(false)
  }

  const keystoreOffline = (
    <div className="bg-amber-50 border border-amber-100 rounded-lg p-3 mt-2">
      <p className="text-xs text-amber-700">Keystore Manager is offline — start it to select or generate keys/certificates. You can still enter key aliases manually.</p>
    </div>
  )

  // ─── Protocol-specific sections ───

  if (protocol === 'FTP' || protocol === 'HTTP') {
    return (
      <div className="border border-amber-200 bg-amber-50 rounded-lg p-4 space-y-2">
        <p className="text-xs uppercase tracking-wider font-semibold text-amber-800 flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" /></svg>
          {protocol === 'FTP' ? 'FTP — Unencrypted Protocol' : 'HTTP — Unencrypted Protocol'}
        </p>
        <p className="text-sm text-amber-700">
          {protocol === 'FTP'
            ? 'FTP transmits credentials and data in plaintext. Consider using SFTP or FTPS for production environments.'
            : 'HTTP transmits data without encryption. Consider using HTTPS for production environments.'}
        </p>
        <p className="text-xs text-amber-600">
          Credentials and file contents are visible to anyone on the network path.
        </p>
      </div>
    )
  }

  if (protocol === 'SFTP') {
    return (
      <div className="space-y-3">
        <p className="text-xs uppercase tracking-wider font-semibold text-gray-500 flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" /></svg>
          SSH / SFTP Security
        </p>

        {/* Host Key */}
        {context === 'server' && (
          <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
            <label className="text-sm font-medium text-gray-700">SSH Host Key</label>
            <p className="text-xs text-gray-400">Server identity key — clients verify this on first connection</p>
            <div className="flex gap-2">
              <select className="flex-1" value={creds.sshHostKeyAlias} onChange={e => update('sshHostKeyAlias', e.target.value)}>
                <option value="">— Select from Keystore —</option>
                {sshHostKeys.map(k => (
                  <option key={k.alias} value={k.alias}>{k.alias} ({k.algorithm})</option>
                ))}
              </select>
              <button type="button" className="btn-secondary text-xs" onClick={handleGenerateSshHost}
                disabled={generating || !keystoreAvailable}>Generate</button>
              <button type="button" className="text-xs text-blue-600 hover:underline"
                onClick={() => setImportMode(importMode === 'ssh-host' ? null : 'ssh-host')}>Import</button>
            </div>
            {!keystoreAvailable && keystoreOffline}
            {/* Manual alias entry if keystore offline */}
            {!keystoreAvailable && (
              <input className="w-full mt-1" placeholder="Enter host key alias manually"
                value={creds.sshHostKeyAlias} onChange={e => update('sshHostKeyAlias', e.target.value)} />
            )}
            {importMode === 'ssh-host' && (
              <ImportForm form={importForm} setForm={setImportForm}
                onImport={() => handleImport('SSH_HOST_KEY')} onCancel={() => setImportMode(null)}
                generating={generating} placeholder="Paste SSH host key (PEM format)" />
            )}
          </div>
        )}

        {/* User Authentication Method */}
        <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
          <label className="text-sm font-medium text-gray-700">User Authentication</label>
          <select value={creds.sshAuthMethod} onChange={e => update('sshAuthMethod', e.target.value)}>
            <option value="PASSWORD">Password Only</option>
            <option value="PUBLIC_KEY">Public Key Only</option>
            <option value="BOTH">Password + Public Key (MFA)</option>
          </select>
          <p className="text-xs text-gray-400">
            {creds.sshAuthMethod === 'PASSWORD' && 'Users authenticate with username and password'}
            {creds.sshAuthMethod === 'PUBLIC_KEY' && 'Users authenticate with SSH keypair — more secure, no passwords transmitted'}
            {creds.sshAuthMethod === 'BOTH' && 'Users must provide both password and valid public key — strongest security'}
          </p>

          {/* User Key Selector (for public key auth) */}
          {(creds.sshAuthMethod === 'PUBLIC_KEY' || creds.sshAuthMethod === 'BOTH') && (
            <div className="pt-2 space-y-2">
              <label className="text-xs font-medium text-gray-600">
                {context === 'server' ? 'Authorized Keys (server-side)' : 'Client Private Key'}
              </label>
              <div className="flex gap-2">
                <select className="flex-1" value={creds.sshUserKeyAlias} onChange={e => update('sshUserKeyAlias', e.target.value)}>
                  <option value="">— Select from Keystore —</option>
                  {sshUserKeys.map(k => (
                    <option key={k.alias} value={k.alias}>{k.alias} ({k.algorithm}, {k.keySizeBits}bit)</option>
                  ))}
                </select>
                <button type="button" className="btn-secondary text-xs" onClick={handleGenerateSshUser}
                  disabled={generating || !keystoreAvailable}>Generate</button>
                <button type="button" className="text-xs text-blue-600 hover:underline"
                  onClick={() => setImportMode(importMode === 'ssh-user' ? null : 'ssh-user')}>Import</button>
              </div>
              {!keystoreAvailable && (
                <input className="w-full" placeholder="Enter user key alias manually"
                  value={creds.sshUserKeyAlias} onChange={e => update('sshUserKeyAlias', e.target.value)} />
              )}
              {importMode === 'ssh-user' && (
                <ImportForm form={importForm} setForm={setImportForm}
                  onImport={() => handleImport('SSH_USER_KEY')} onCancel={() => setImportMode(null)}
                  generating={generating} placeholder="Paste SSH public key (OpenSSH or PEM format)" />
              )}
            </div>
          )}
        </div>

        {/* Known Hosts Verification (client/outbound) */}
        {context === 'destination' && (
          <div className="border rounded-lg p-3 bg-gray-50">
            <div className="flex items-center gap-2">
              <input type="checkbox" id="sshKnownHosts" checked={creds.sshKnownHostsVerification}
                onChange={e => update('sshKnownHostsVerification', e.target.checked)}
                className="w-4 h-4 text-blue-600 rounded border-gray-300" />
              <label htmlFor="sshKnownHosts" className="text-sm font-medium text-gray-700">Strict Host Key Verification</label>
            </div>
            <p className="text-xs text-gray-400 mt-1">
              {creds.sshKnownHostsVerification
                ? 'Connection will fail if server host key is unknown or changed — prevents MITM attacks'
                : 'Host key verification disabled — less secure but avoids first-connection prompts'}
            </p>
          </div>
        )}
      </div>
    )
  }

  if (protocol === 'FTPS') {
    return (
      <div className="space-y-3">
        <p className="text-xs uppercase tracking-wider font-semibold text-gray-500 flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" /></svg>
          FTPS / TLS Security
        </p>

        {/* FTPS Mode */}
        <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
          <label className="text-sm font-medium text-gray-700">FTPS Mode</label>
          <select value={creds.ftpsMode} onChange={e => update('ftpsMode', e.target.value)}>
            <option value="EXPLICIT">Explicit (STARTTLS on port 21)</option>
            <option value="IMPLICIT">Implicit (TLS from start on port 990)</option>
          </select>
          <p className="text-xs text-gray-400">
            {creds.ftpsMode === 'EXPLICIT'
              ? 'Connection starts as plain FTP, then upgrades to TLS via AUTH TLS command — more widely supported'
              : 'Connection is encrypted from the start — requires dedicated port (typically 990)'}
          </p>
        </div>

        {/* TLS Certificate */}
        <TlsCertSection
          creds={creds} update={update} tlsCerts={tlsCerts}
          keystoreAvailable={keystoreAvailable} keystoreOffline={keystoreOffline}
          generating={generating} onGenerate={handleGenerateTls}
          importMode={importMode} setImportMode={setImportMode}
          importForm={importForm} setImportForm={setImportForm}
          onImport={() => handleImport('TLS_CERTIFICATE')}
          context={context}
        />

        {/* TLS Version */}
        <TlsVersionSection creds={creds} update={update} />

        {/* Client Certificate Auth */}
        {context === 'server' && <MutualTlsSection creds={creds} update={update} tlsCerts={tlsCerts} keystoreAvailable={keystoreAvailable} />}
      </div>
    )
  }

  if (protocol === 'HTTPS' || protocol === 'FTP_WEB') {
    return (
      <div className="space-y-3">
        <p className="text-xs uppercase tracking-wider font-semibold text-gray-500 flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" /></svg>
          {protocol === 'HTTPS' ? 'HTTPS / TLS Security' : 'FTP-Web / TLS Security'}
        </p>

        {/* TLS Certificate */}
        <TlsCertSection
          creds={creds} update={update} tlsCerts={tlsCerts}
          keystoreAvailable={keystoreAvailable} keystoreOffline={keystoreOffline}
          generating={generating} onGenerate={handleGenerateTls}
          importMode={importMode} setImportMode={setImportMode}
          importForm={importForm} setImportForm={setImportForm}
          onImport={() => handleImport('TLS_CERTIFICATE')}
          context={context}
        />

        {/* TLS Version */}
        <TlsVersionSection creds={creds} update={update} />

        {/* Mutual TLS */}
        {context === 'server' && <MutualTlsSection creds={creds} update={update} tlsCerts={tlsCerts} keystoreAvailable={keystoreAvailable} />}
      </div>
    )
  }

  if (protocol === 'API') {
    return (
      <div className="space-y-3">
        <p className="text-xs uppercase tracking-wider font-semibold text-gray-500 flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" /></svg>
          API Security
        </p>

        {/* Auth Method */}
        <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
          <label className="text-sm font-medium text-gray-700">Authentication Method</label>
          <select value={creds.apiAuthMethod} onChange={e => update('apiAuthMethod', e.target.value)}>
            <option value="API_KEY">API Key (Header)</option>
            <option value="BASIC">Basic Auth (Username/Password)</option>
            <option value="OAUTH2">OAuth 2.0 Client Credentials</option>
            <option value="MTLS">Mutual TLS (mTLS Certificate)</option>
          </select>
        </div>

        {/* API Key config */}
        {creds.apiAuthMethod === 'API_KEY' && (
          <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
            <label className="text-sm font-medium text-gray-700">API Key Configuration</label>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-gray-500">Header Name</label>
                <input value={creds.apiKeyHeader} onChange={e => update('apiKeyHeader', e.target.value)}
                  placeholder="X-API-Key" />
              </div>
              <div>
                <label className="text-xs text-gray-500">API Key Value</label>
                <input type="password" value={creds.apiKeyValue} onChange={e => update('apiKeyValue', e.target.value)}
                  placeholder="Enter API key" />
              </div>
            </div>
          </div>
        )}

        {/* OAuth2 config */}
        {creds.apiAuthMethod === 'OAUTH2' && (
          <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
            <label className="text-sm font-medium text-gray-700">OAuth 2.0 Client Credentials</label>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-gray-500">Token URL</label>
                <input value={creds.oauth2TokenUrl} onChange={e => update('oauth2TokenUrl', e.target.value)}
                  placeholder="https://auth.partner.com/oauth/token" />
              </div>
              <div>
                <label className="text-xs text-gray-500">Scope</label>
                <input value={creds.oauth2Scope} onChange={e => update('oauth2Scope', e.target.value)}
                  placeholder="file:write file:read" />
              </div>
              <div>
                <label className="text-xs text-gray-500">Client ID</label>
                <input value={creds.oauth2ClientId} onChange={e => update('oauth2ClientId', e.target.value)}
                  placeholder="client-id" />
              </div>
              <div>
                <label className="text-xs text-gray-500">Client Secret</label>
                <input type="password" value={creds.oauth2ClientSecret} onChange={e => update('oauth2ClientSecret', e.target.value)}
                  placeholder="client-secret" />
              </div>
            </div>
          </div>
        )}

        {/* mTLS — reuse TLS cert section */}
        {creds.apiAuthMethod === 'MTLS' && (
          <TlsCertSection
            creds={creds} update={update} tlsCerts={tlsCerts}
            keystoreAvailable={keystoreAvailable} keystoreOffline={keystoreOffline}
            generating={generating} onGenerate={handleGenerateTls}
            importMode={importMode} setImportMode={setImportMode}
            importForm={importForm} setImportForm={setImportForm}
            onImport={() => handleImport('TLS_CERTIFICATE')}
            context={context}
          />
        )}

        {/* TLS always on for API */}
        <TlsVersionSection creds={creds} update={update} />
      </div>
    )
  }

  // Unknown protocol
  return null
}


// ─── Shared sub-components ───

function TlsCertSection({ creds, update, tlsCerts, keystoreAvailable, keystoreOffline, generating, onGenerate,
  importMode, setImportMode, importForm, setImportForm, onImport, context }) {
  return (
    <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
      <label className="text-sm font-medium text-gray-700">
        {context === 'server' ? 'Server TLS Certificate' : 'CA / Trust Certificate'}
      </label>
      <p className="text-xs text-gray-400">
        {context === 'server'
          ? 'X.509 certificate presented to connecting clients for server identity'
          : 'Certificate to verify the remote server identity (leave empty to use system trust store)'}
      </p>
      <div className="flex gap-2">
        <select className="flex-1" value={creds.tlsCertAlias} onChange={e => update('tlsCertAlias', e.target.value)}>
          <option value="">— {keystoreAvailable ? 'Select from Keystore' : 'Keystore offline'} —</option>
          {tlsCerts.map(k => (
            <option key={k.alias} value={k.alias}>
              {k.alias} {k.subjectDn ? `(${k.subjectDn})` : `(${k.algorithm})`}
              {k.expiresAt ? ` — expires ${new Date(k.expiresAt).toLocaleDateString()}` : ''}
            </option>
          ))}
        </select>
        <button type="button" className="btn-secondary text-xs" onClick={onGenerate}
          disabled={generating || !keystoreAvailable}>
          {context === 'server' ? 'Generate Self-Signed' : 'Generate'}
        </button>
        <button type="button" className="text-xs text-blue-600 hover:underline"
          onClick={() => setImportMode(importMode === 'tls' ? null : 'tls')}>Import</button>
      </div>
      {!keystoreAvailable && keystoreOffline}
      {!keystoreAvailable && (
        <input className="w-full mt-1" placeholder="Enter certificate alias manually"
          value={creds.tlsCertAlias} onChange={e => update('tlsCertAlias', e.target.value)} />
      )}
      {importMode === 'tls' && (
        <ImportForm form={importForm} setForm={setImportForm}
          onImport={onImport} onCancel={() => setImportMode(null)}
          generating={generating} placeholder="Paste PEM certificate (-----BEGIN CERTIFICATE-----)" />
      )}
    </div>
  )
}

function TlsVersionSection({ creds, update }) {
  return (
    <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
      <label className="text-sm font-medium text-gray-700">Minimum TLS Version</label>
      <select value={creds.tlsVersion} onChange={e => update('tlsVersion', e.target.value)}>
        {TLS_VERSIONS.map(v => <option key={v} value={v}>{v}</option>)}
      </select>
      <p className="text-xs text-gray-400">
        {creds.tlsVersion === 'TLS 1.3' && 'Highest security — only TLS 1.3. Some older clients may not support this.'}
        {creds.tlsVersion === 'TLS 1.2' && 'Allows only TLS 1.2 — widely supported and secure.'}
        {creds.tlsVersion === 'TLS 1.2+' && 'Allows TLS 1.2 and above — best balance of compatibility and security.'}
      </p>
    </div>
  )
}

function MutualTlsSection({ creds, update, tlsCerts, keystoreAvailable }) {
  return (
    <div className="border rounded-lg p-3 space-y-2 bg-gray-50">
      <div className="flex items-center gap-2">
        <input type="checkbox" id="mtls" checked={creds.tlsMutualAuth}
          onChange={e => update('tlsMutualAuth', e.target.checked)}
          className="w-4 h-4 text-blue-600 rounded border-gray-300" />
        <label htmlFor="mtls" className="text-sm font-medium text-gray-700">Require Client Certificate (mTLS)</label>
      </div>
      <p className="text-xs text-gray-400">
        {creds.tlsMutualAuth
          ? 'Clients must present a valid certificate signed by a trusted CA — strongest authentication'
          : 'Standard TLS — server presents certificate, client is not required to'}
      </p>
      {creds.tlsMutualAuth && (
        <div className="pt-1">
          <label className="text-xs text-gray-500">Client CA Certificate (for validation)</label>
          <select className="w-full" value={creds.tlsClientCertAlias} onChange={e => update('tlsClientCertAlias', e.target.value)}>
            <option value="">— Select CA cert from Keystore —</option>
            {tlsCerts.map(k => (
              <option key={k.alias} value={k.alias}>{k.alias} {k.subjectDn ? `(${k.subjectDn})` : ''}</option>
            ))}
          </select>
          {!keystoreAvailable && (
            <input className="w-full mt-1" placeholder="Enter CA cert alias manually"
              value={creds.tlsClientCertAlias} onChange={e => update('tlsClientCertAlias', e.target.value)} />
          )}
        </div>
      )}
    </div>
  )
}

function ImportForm({ form, setForm, onImport, onCancel, generating, placeholder }) {
  return (
    <div className="border border-blue-200 bg-blue-50 rounded-lg p-3 space-y-2 mt-2">
      <p className="text-xs font-semibold text-blue-800">Import Key / Certificate</p>
      <input placeholder="Alias (e.g., partner-acme-cert)"
        value={form.alias} onChange={e => setForm(f => ({ ...f, alias: e.target.value }))} />
      <textarea rows={4} className="w-full font-mono text-xs" placeholder={placeholder}
        value={form.keyMaterial} onChange={e => setForm(f => ({ ...f, keyMaterial: e.target.value }))} />
      <input placeholder="Description (optional)"
        value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
      <div className="flex gap-2 justify-end">
        <button type="button" className="text-xs text-gray-500 hover:underline" onClick={onCancel}>Cancel</button>
        <button type="button" className="btn-primary text-xs" onClick={onImport} disabled={generating}>
          {generating ? 'Importing...' : 'Import'}
        </button>
      </div>
    </div>
  )
}
