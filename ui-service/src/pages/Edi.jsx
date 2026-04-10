import { useState } from 'react'
import toast from 'react-hot-toast'
import { ediApi as api } from '../api/client'

const TABS = [
  { id: 'convert', label: 'Convert' },
  { id: 'explain', label: 'Explain' },
  { id: 'heal', label: 'Self-Heal' },
  { id: 'compliance', label: 'Compliance' },
  { id: 'diff', label: 'Diff' },
  { id: 'nlcreate', label: 'NL Create' },
  { id: 'mapping', label: 'AI Mapping' },
  { id: 'partners', label: 'Partners' },
]

const SAMPLE_X12 = `ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00501*000000001*0*P*>~
GS*PO*SENDER*RECEIVER*20230101*1200*1*X*005010~
ST*850*0001~
BEG*00*NE*PO123456**20230101~
NM1*BY*2*ACME CORP*****ZZ*BUYER001~
NM1*SE*2*GLOBAL SUPPLY*****ZZ*SELLER001~
PO1*1*100*EA*12.50**VP*WIDGET-A1~
PO1*2*50*EA*25.00**VP*GADGET-B2~
CTT*2~
SE*9*0001~
GE*1*1~
IEA*1*000000001~`

export default function Edi() {
  const [content, setContent] = useState('')
  const [content2, setContent2] = useState('')
  const [nlText, setNlText] = useState('')
  const [mappingSource, setMappingSource] = useState('')
  const [mappingTarget, setMappingTarget] = useState('')
  const [partnerId, setPartnerId] = useState('')
  const [partnerName, setPartnerName] = useState('')
  const [result, setResult] = useState(null)
  const [tab, setTab] = useState('convert')
  const [target, setTarget] = useState('JSON')
  const [loading, setLoading] = useState(false)

  const call = async (endpoint, data, label) => {
    setLoading(true)
    try {
      const r = await api.post(endpoint, data)
      setResult({ label, data: r.data })
    } catch (e) { toast.error(label + ' failed: ' + (e.response?.data?.message || e.message)) }
    finally { setLoading(false) }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-primary">EDI Converter v3.0</h1>
        <p className="text-secondary text-sm">11 formats, 66 conversion paths, AI-powered — convert, explain, heal, diff, score, map, create</p>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border gap-1 overflow-x-auto">
        {TABS.map(t => (
          <button key={t.id} onClick={() => { setTab(t.id); setResult(null) }}
            className={`pb-2 px-3 text-sm font-medium whitespace-nowrap transition-colors ${tab === t.id ? 'border-b-2 border-blue-600 text-blue-600' : 'text-secondary hover:text-primary'}`}>
            {t.label}
          </button>
        ))}
      </div>

      {/* Convert Tab */}
      {tab === 'convert' && (
        <div className="card space-y-4">
          <div className="flex items-center gap-3">
            <h3 className="font-semibold text-primary">Convert / Detect / Validate / Explain</h3>
            <button onClick={() => setContent(SAMPLE_X12)} className="text-xs text-blue-600 hover:underline">Load sample X12 850</button>
          </div>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste any EDI content — X12, EDIFACT, HL7, SWIFT, PEPPOL..." />
          <div className="flex flex-wrap gap-2">
            <button onClick={() => call('/api/v1/convert/detect', { content }, 'Detect')} className="btn-sm bg-gray-700 text-white">Detect Format</button>
            <button onClick={() => call('/api/v1/convert/validate', { content }, 'Validate')} className="btn-sm bg-green-600 text-white">Validate</button>
            <button onClick={() => call('/api/v1/convert/explain', { content }, 'Explain')} className="btn-sm bg-purple-600 text-white">Explain in English</button>
            <button onClick={() => call('/api/v1/convert/canonical', { content }, 'Canonical')} className="btn-sm bg-indigo-600 text-white">Canonical Model</button>
            <span className="border-l mx-1" />
            <select value={target} onChange={e => setTarget(e.target.value)} className="text-sm border rounded px-2 py-1">
              {['JSON', 'XML', 'CSV', 'YAML', 'FLAT', 'TIF'].map(f => <option key={f}>{f}</option>)}
            </select>
            <button onClick={() => call('/api/v1/convert/convert', { content, target }, 'Convert → ' + target)} className="btn-sm bg-blue-600 text-white">Convert</button>
          </div>
        </div>
      )}

      {/* Explain Tab */}
      {tab === 'explain' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Explain EDI in Plain English</h3>
          <p className="text-sm text-secondary">Paste any EDI document and get a human-readable explanation of every segment.</p>
          <button onClick={() => setContent(SAMPLE_X12)} className="text-xs text-blue-600 hover:underline">Load sample</button>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste EDI content..." />
          <button onClick={() => call('/api/v1/convert/explain', { content }, 'Explain')} className="btn-primary" disabled={loading}>
            {loading ? 'Explaining...' : 'Explain This'}
          </button>
        </div>
      )}

      {/* Self-Heal Tab */}
      {tab === 'heal' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Self-Healing Engine</h3>
          <p className="text-sm text-secondary">Paste a broken or malformed EDI document. The engine auto-detects and fixes common errors.</p>
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-3 text-sm text-blue-800">
            Fixes 25+ error types: missing terminators, wrong segment counts, ISA padding, missing trailers, BOM removal, null bytes, mixed line endings, and more.
          </div>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste broken EDI content..." />
          <button onClick={() => call('/api/v1/convert/heal', { content }, 'Self-Heal')} className="btn-primary" disabled={loading}>
            {loading ? 'Healing...' : 'Auto-Fix'}
          </button>
        </div>
      )}

      {/* Compliance Tab */}
      {tab === 'compliance' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Compliance Scoring (0-100)</h3>
          <p className="text-sm text-secondary">Score your EDI document against the standard. Get a grade (A+ to F) with detailed breakdown.</p>
          <button onClick={() => setContent(SAMPLE_X12)} className="text-xs text-blue-600 hover:underline">Load sample</button>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste EDI content..." />
          <button onClick={() => call('/api/v1/convert/compliance', { content }, 'Compliance')} className="btn-primary" disabled={loading}>
            {loading ? 'Scoring...' : 'Score Compliance'}
          </button>
        </div>
      )}

      {/* Diff Tab */}
      {tab === 'diff' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Semantic Diff</h3>
          <p className="text-sm text-secondary">Compare two EDI documents with field-level semantic diff — not character-level, BUSINESS-level.</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs font-medium text-secondary">Left (before)</label>
              <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste first EDI..." />
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Right (after)</label>
              <textarea value={content2} onChange={e => setContent2(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste second EDI..." />
            </div>
          </div>
          <button onClick={() => call('/api/v1/convert/diff', { left: content, right: content2 }, 'Diff')} className="btn-primary" disabled={loading}>
            {loading ? 'Comparing...' : 'Compare'}
          </button>
        </div>
      )}

      {/* NL Create Tab */}
      {tab === 'nlcreate' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Create EDI from Natural Language</h3>
          <p className="text-sm text-secondary">Describe what you need in plain English. We'll generate valid EDI.</p>
          <div className="bg-green-50 border border-green-100 rounded-lg p-3 text-sm text-green-800 space-y-1">
            <p className="font-medium">Try these:</p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Create a purchase order for 500 widgets at $12.50 each to Acme Corp')}>
              "Create a purchase order for 500 widgets at $12.50 each to Acme Corp"
            </p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Generate an invoice for $15,000 from GlobalSupplier to RetailBuyer')}>
              "Generate an invoice for $15,000 from GlobalSupplier to RetailBuyer"
            </p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Create a healthcare claim for patient John Doe, $1500, diagnosis J06.9')}>
              "Create a healthcare claim for patient John Doe, $1500, diagnosis J06.9"
            </p>
            <p className="cursor-pointer hover:underline" onClick={() => setNlText('Send a wire transfer for $50,000 from Acme Corp to Global Trading')}>
              "Send a wire transfer for $50,000 from Acme Corp to Global Trading"
            </p>
          </div>
          <textarea value={nlText} onChange={e => setNlText(e.target.value)} rows={3}
            className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="Describe what EDI document you need..." />
          <button onClick={() => call('/api/v1/convert/create', { text: nlText }, 'NL Create')} className="btn-primary" disabled={loading}>
            {loading ? 'Generating...' : 'Generate EDI'}
          </button>
        </div>
      )}

      {/* AI Mapping Tab */}
      {tab === 'mapping' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">AI Mapping Generator</h3>
          <p className="text-sm text-secondary">Upload a source EDI and your desired JSON output. We auto-generate the mapping rules.</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs font-medium text-secondary">Source EDI</label>
              <textarea value={mappingSource} onChange={e => setMappingSource(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste source EDI..." />
            </div>
            <div>
              <label className="text-xs font-medium text-secondary">Desired JSON Output</label>
              <textarea value={mappingTarget} onChange={e => setMappingTarget(e.target.value)} rows={8}
                className="w-full rounded-lg border px-3 py-2 text-xs font-mono"
                placeholder='{"poNumber":"PO123456","buyer":"ACME CORP","seller":"GLOBAL SUPPLY","items":[...]}' />
            </div>
          </div>
          <button onClick={() => call('/api/v1/convert/mapping/generate', { source: mappingSource, target: mappingTarget }, 'AI Mapping')} className="btn-primary" disabled={loading}>
            {loading ? 'Generating Mapping...' : 'Generate Mapping'}
          </button>
        </div>
      )}

      {/* Partners Tab */}
      {tab === 'partners' && (
        <div className="card space-y-4">
          <h3 className="font-semibold text-primary">Partner Profile Manager</h3>
          <p className="text-sm text-secondary">Upload a sample EDI from a new trading partner to auto-generate their profile.</p>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="text-xs font-medium text-secondary">Partner ID</label>
              <input value={partnerId} onChange={e => setPartnerId(e.target.value)} placeholder="acme-corp" /></div>
            <div><label className="text-xs font-medium text-secondary">Partner Name</label>
              <input value={partnerName} onChange={e => setPartnerName(e.target.value)} placeholder="Acme Corporation" /></div>
          </div>
          <label className="text-xs font-medium text-secondary">Sample EDI from this partner</label>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={6}
            className="w-full rounded-lg border px-3 py-2 text-xs font-mono" placeholder="Paste a sample EDI from this partner..." />
          <button onClick={() => call(`/api/v1/convert/partners/${partnerId || 'partner-1'}/analyze`,
            { content, partnerName: partnerName || partnerId }, 'Analyze Partner')} className="btn-primary" disabled={loading}>
            {loading ? 'Analyzing...' : 'Analyze & Create Profile'}
          </button>
        </div>
      )}

      {/* Results */}
      {result && (
        <div className="card">
          <h3 className="font-semibold text-primary mb-2">{result.label} Result</h3>

          {/* Special rendering for compliance */}
          {result.label === 'Compliance' && result.data?.overallScore !== undefined && (
            <div className="mb-4 p-4 rounded-lg bg-canvas">
              <div className="flex items-center gap-4 mb-3">
                <div className={`text-4xl font-bold ${result.data.overallScore >= 90 ? 'text-green-600' : result.data.overallScore >= 70 ? 'text-yellow-600' : 'text-red-600'}`}>
                  {result.data.overallScore}
                </div>
                <div>
                  <div className="text-lg font-semibold">Grade: {result.data.grade}</div>
                  <div className="text-sm text-secondary">{result.data.verdict}</div>
                </div>
              </div>
              <div className="grid grid-cols-4 gap-2 text-sm">
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-blue-600">{result.data.structureScore}</div>
                  <div className="text-xs text-secondary">Structure</div>
                </div>
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-purple-600">{result.data.elementScore}</div>
                  <div className="text-xs text-secondary">Elements</div>
                </div>
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-green-600">{result.data.businessRuleScore}</div>
                  <div className="text-xs text-secondary">Business Rules</div>
                </div>
                <div className="bg-surface rounded p-2 text-center">
                  <div className="font-bold text-amber-600">{result.data.bestPracticeScore}</div>
                  <div className="text-xs text-secondary">Best Practice</div>
                </div>
              </div>
            </div>
          )}

          {/* Special rendering for self-heal */}
          {result.label === 'Self-Heal' && result.data?.verdict && (
            <div className={`mb-4 p-3 rounded-lg text-sm ${result.data.wasHealed ? 'bg-green-50 text-green-800 border border-green-100' : 'bg-canvas text-primary'}`}>
              <span className="font-medium">{result.data.verdict}</span>
              {result.data.wasHealed && <span className="ml-2">({result.data.issuesFixed} of {result.data.issuesFound} fixed)</span>}
            </div>
          )}

          {/* Special rendering for NL create */}
          {result.label === 'NL Create' && result.data?.generatedEdi && (
            <div className="mb-4 space-y-2">
              <div className="p-3 bg-blue-50 rounded-lg text-sm text-blue-800">{result.data.explanation}</div>
              {result.data.confidence > 0 && (
                <div className="text-xs text-secondary">Confidence: {result.data.confidence}%</div>
              )}
              <label className="text-xs font-medium text-secondary">Generated EDI:</label>
              <pre className="text-xs bg-gray-900 text-green-400 rounded-lg p-3 overflow-auto font-mono max-h-64">
                {result.data.generatedEdi}
              </pre>
            </div>
          )}

          {/* Special rendering for diff */}
          {result.label === 'Diff' && result.data?.verdict && (
            <div className="mb-4 p-3 bg-canvas rounded-lg text-sm">
              <span className="font-medium">{result.data.verdict}</span>
              <span className="text-secondary ml-2">
                (+{result.data.segmentsAdded} added, -{result.data.segmentsRemoved} removed, ~{result.data.segmentsModified} modified)
              </span>
            </div>
          )}

          {/* JSON output */}
          <pre className="text-xs bg-canvas rounded-lg p-3 overflow-auto font-mono max-h-96">
            {typeof result.data === 'string' ? result.data : JSON.stringify(result.data, null, 2)}
          </pre>
        </div>
      )}

      {/* Feature Overview */}
      {!result && tab === 'convert' && (
        <div className="grid grid-cols-3 gap-4">
          {[
            ['11 Input Formats', 'X12, EDIFACT, TRADACOMS, SWIFT MT, HL7, NACHA, BAI2, ISO20022, FIX, PEPPOL/UBL, Auto-detect'],
            ['6 Output Formats', 'JSON, XML, CSV, YAML, Fixed-width, TIF (TranzFer Internal)'],
            ['Self-Healing', 'Auto-fixes 25+ common EDI errors — missing terminators, wrong counts, padding, trailers'],
            ['Compliance 0-100', 'A+ to F grading with structure, element, business rule, and best practice categories'],
            ['Semantic Diff', 'Field-level comparison — "PO1*02 changed from EA to CS" not "line 47 char 12 changed"'],
            ['AI Mapping', 'Upload source EDI + desired output → auto-generates mapping rules (no LLM needed)'],
            ['NL Create', 'Say "create a PO for 500 widgets to Acme" → get valid X12 850'],
            ['Partner Profiles', 'Upload a sample from a new partner → auto-generate their profile in minutes'],
            ['Canonical Model', 'Universal JSON schema — one model for all formats. Eliminates per-partner mapping.'],
          ].map(([title, desc]) => (
            <div key={title} className="p-4 bg-canvas rounded-lg">
              <h4 className="font-medium text-primary text-sm">{title}</h4>
              <p className="text-xs text-secondary mt-1">{desc}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
