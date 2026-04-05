import { useState } from 'react'
import { useBranding } from '../context/BrandingContext'
import { useServices } from '../context/ServiceContext'
import toast from 'react-hot-toast'

const SERVICE_LABELS = {
  core: 'Core Platform (Accounts, Users, Logs)',
  config: 'Config Service (Flows, Servers, Security)',
  sftp: 'SFTP Service',
  ftp: 'FTP Service',
  ftpWeb: 'FTP-Web Service (HTTP uploads)',
  gateway: 'Gateway & DMZ',
  encryption: 'Encryption Service (AES/PGP)',
  forwarder: 'External Forwarder',
  dmz: 'DMZ Proxy',
  license: 'License Service',
  analytics: 'Analytics & Predictions',
  aiEngine: 'AI Engine (Classification, NLP, Anomaly)',
  screening: 'OFAC/AML Screening',
  keystore: 'Keystore Manager',
}

export default function Settings() {
  const { branding, updateBranding } = useBranding()
  const { services, overrides, toggleOverride, clearOverrides, serviceList } = useServices()
  const [form, setForm] = useState({ ...branding })
  const [tab, setTab] = useState('branding')

  const save = () => { updateBranding(form); toast.success('Settings saved') }

  return (
    <div className="space-y-6">
      <div><h1 className="text-2xl font-bold text-gray-900">Settings</h1>
        <p className="text-gray-500 text-sm">Platform configuration and white-labeling</p></div>
      <div className="flex border-b border-gray-200 gap-4">
        {['branding', 'ai', 'services', 'security', 'notifications'].map(t => (
          <button key={t} onClick={() => setTab(t)}
            className={`pb-3 text-sm font-medium capitalize transition-colors ${tab === t ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500 hover:text-gray-700'}`}>
            {t}
          </button>
        ))}
      </div>

      {tab === 'branding' && (
        <div className="card max-w-2xl space-y-4">
          <h3 className="font-semibold text-gray-900">Brand Identity</h3>
          <p className="text-sm text-gray-500">Customize how TranzFer MFT appears to your users</p>
          <div><label>Company / Platform Name</label>
            <input value={form.companyName} onChange={e => setForm(f => ({ ...f, companyName: e.target.value }))} placeholder="TranzFer MFT" /></div>
          <div><label>Logo URL</label>
            <input value={form.logoUrl} onChange={e => setForm(f => ({ ...f, logoUrl: e.target.value }))} placeholder="https://your-domain.com/logo.png" />
            {form.logoUrl && <img src={form.logoUrl} alt="logo preview" className="h-10 mt-2 object-contain" onError={e => { e.target.style.display='none' }} />}</div>
          <div className="grid grid-cols-2 gap-4">
            <div><label>Primary Color</label>
              <div className="flex gap-2">
                <input type="color" value={form.primaryColor} onChange={e => setForm(f => ({ ...f, primaryColor: e.target.value }))} className="w-12 h-10 rounded cursor-pointer p-0.5" />
                <input value={form.primaryColor} onChange={e => setForm(f => ({ ...f, primaryColor: e.target.value }))} placeholder="#3b82f6" />
              </div></div>
            <div><label>Accent Color</label>
              <div className="flex gap-2">
                <input type="color" value={form.accentColor} onChange={e => setForm(f => ({ ...f, accentColor: e.target.value }))} className="w-12 h-10 rounded cursor-pointer p-0.5" />
                <input value={form.accentColor} onChange={e => setForm(f => ({ ...f, accentColor: e.target.value }))} placeholder="#2563eb" />
              </div></div>
          </div>
          <div className="pt-2">
            <button className="btn-primary" onClick={save}>Save Branding</button>
          </div>
          <div className="border-t pt-4">
            <h4 className="font-medium text-gray-900 mb-2">Preview</h4>
            <div className="p-4 rounded-xl text-white text-sm font-medium" style={{ backgroundColor: form.primaryColor }}>
              {form.companyName || 'Your Company Name'} — Managed File Transfer Platform
            </div>
          </div>
        </div>
      )}

      {tab === 'ai' && (
        <div className="card max-w-2xl space-y-4">
          <h3 className="font-semibold text-gray-900">AI & LLM Configuration</h3>
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-4 text-sm text-blue-800">
            All AI features work out of the box — no LLM needed. Connecting an LLM
            just makes the admin terminal smarter at understanding natural language.
          </div>
          <div className="space-y-3">
            <div className="p-4 bg-gray-50 rounded-lg">
              <h4 className="font-medium text-gray-900 mb-2">Features that work WITHOUT any LLM</h4>
              <div className="grid grid-cols-2 gap-1 text-sm text-gray-600">
                {['PCI/PII Classification', 'OFAC Screening', 'Anomaly Detection', 'Smart Retry',
                  'Threat Scoring', 'Partner Profiling', 'Predictive SLA', 'Auto-Remediation',
                  'File Format Detection', 'Data Classification', 'Observability Recommendations',
                  'Autonomous Onboarding'].map(f => (
                  <div key={f} className="flex items-center gap-1"><span className="text-green-500">✓</span> {f}</div>
                ))}
              </div>
            </div>
            <div className="p-4 bg-gray-50 rounded-lg">
              <h4 className="font-medium text-gray-900 mb-2">Features ENHANCED by LLM (optional)</h4>
              <div className="text-sm text-gray-600 space-y-1">
                <div className="flex items-center gap-1"><span className="text-blue-500">↑</span> NLP Terminal — better natural language understanding</div>
                <div className="flex items-center gap-1"><span className="text-blue-500">↑</span> Flow Builder — "describe what you need" in English</div>
                <div className="flex items-center gap-1"><span className="text-blue-500">↑</span> NL Monitoring — ask complex questions about the platform</div>
              </div>
              <p className="text-xs text-gray-500 mt-2">Without LLM: these use keyword matching (works for 90% of inputs)</p>
            </div>
          </div>
          <div className="border-t pt-4">
            <h4 className="font-medium text-gray-900 mb-2">Connect LLM (optional)</h4>
            <p className="text-sm text-gray-500 mb-3">Set the CLAUDE_API_KEY environment variable and restart the AI engine.</p>
            <div className="bg-gray-900 rounded-lg p-3 font-mono text-xs text-green-400">
              <p># Option 1: In .env file</p>
              <p>CLAUDE_API_KEY=sk-ant-your-key-here</p>
              <p className="mt-2"># Option 2: Docker Compose</p>
              <p>docker compose restart ai-engine</p>
            </div>
          </div>
        </div>
      )}

      {tab === 'services' && (
        <div className="card max-w-2xl space-y-4">
          <h3 className="font-semibold text-gray-900">Service Visibility</h3>
          <p className="text-sm text-gray-500">
            The UI automatically detects which microservices are running and only shows relevant pages.
            You can manually override visibility here.
          </p>
          <div className="space-y-2">
            {serviceList.map(svc => {
              const detected = services[svc] !== false
              const overridden = overrides[svc] !== undefined
              const visible = overrides[svc] !== undefined ? overrides[svc] : detected
              return (
                <div key={svc} className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
                  <div className="flex items-center gap-3">
                    <div className={`w-2.5 h-2.5 rounded-full ${detected ? 'bg-green-400' : 'bg-red-400'}`} />
                    <div>
                      <p className="text-sm font-medium text-gray-900">{SERVICE_LABELS[svc] || svc}</p>
                      <p className="text-xs text-gray-500">
                        {detected ? 'Detected (running)' : 'Not detected'}
                        {overridden && <span className="ml-1 text-blue-600">(manually {visible ? 'shown' : 'hidden'})</span>}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <button onClick={() => toggleOverride(svc, !visible)}
                      className={`px-3 py-1 text-xs font-medium rounded-lg transition-colors ${
                        visible ? 'bg-green-100 text-green-700 hover:bg-green-200' : 'bg-gray-200 text-gray-500 hover:bg-gray-300'}`}>
                      {visible ? 'Visible' : 'Hidden'}
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
          <div className="flex gap-3 pt-2">
            <button className="btn-secondary text-xs" onClick={() => { clearOverrides(); toast.success('Reset to auto-detect') }}>
              Reset All to Auto-Detect
            </button>
          </div>
        </div>
      )}

      {tab === 'security' && (
        <div className="card max-w-2xl space-y-4">
          <h3 className="font-semibold text-gray-900">Security Settings</h3>
          <p className="text-sm text-gray-500">These settings are managed via environment variables in production.</p>
          <div className="bg-amber-50 border border-amber-100 rounded-lg p-4 text-sm text-amber-800">
            ⚠️ JWT secret, session timeout, and encryption keys must be set via environment variables (JWT_SECRET, ENCRYPTION_MASTER_KEY) for production deployments.
          </div>
          <div className="space-y-2 text-sm">
            {[['JWT_SECRET', 'change_me_in_production_256bit_secret_key!!', true],
              ['ENCRYPTION_MASTER_KEY', '0000...0000 (256-bit hex)', true],
              ['CONTROL_API_KEY', 'internal_control_secret', false],
            ].map(([key, val, sensitive]) => (
              <div key={key} className="flex items-center gap-3 p-3 bg-gray-50 rounded-lg">
                <span className="font-mono text-xs font-semibold text-gray-700 flex-shrink-0">{key}</span>
                <span className="text-gray-500 text-xs flex-1">{sensitive ? '••••••••' : val}</span>
                {sensitive && <span className="badge badge-red">sensitive</span>}
              </div>
            ))}
          </div>
        </div>
      )}

      {tab === 'notifications' && (
        <div className="card max-w-2xl space-y-4">
          <h3 className="font-semibold text-gray-900">Notification Settings</h3>
          <p className="text-sm text-gray-500">Alert delivery configuration (coming in next release)</p>
          <div className="space-y-3">
            <div><label>SMTP Host</label><input placeholder="smtp.company.com" /></div>
            <div><label>SMTP Port</label><input type="number" placeholder="587" /></div>
            <div><label>From Address</label><input type="email" placeholder="alerts@company.com" /></div>
            <div><label>Alert Recipients (comma-separated)</label><input placeholder="admin@company.com, ops@company.com" /></div>
            <button className="btn-primary" onClick={() => toast.error('Notification service coming soon')}>Save (Coming Soon)</button>
          </div>
        </div>
      )}
    </div>
  )
}
