import { useState, useRef, useEffect } from 'react'
import { onboardingApi } from '../api/client'
import { nlpCommand, suggestFlow } from '../api/ai'
import { CommandLineIcon, SparklesIcon } from '@heroicons/react/24/outline'

const HISTORY_KEY = 'mft-cli-history'

export default function Terminal() {
  const [lines, setLines] = useState([
    { type: 'system', text: 'TranzFer MFT Admin CLI v2.1' },
    { type: 'system', text: 'Type "help" for available commands.\n' },
  ])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [history, setHistory] = useState(() => {
    try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') } catch { return [] }
  })
  const [historyIdx, setHistoryIdx] = useState(-1)
  const bottomRef = useRef(null)
  const inputRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [lines])

  const execute = async (cmd) => {
    if (!cmd.trim()) return
    const newHistory = [cmd, ...history.filter(h => h !== cmd)].slice(0, 50)
    setHistory(newHistory)
    localStorage.setItem(HISTORY_KEY, JSON.stringify(newHistory))
    setHistoryIdx(-1)

    setLines(prev => [...prev, { type: 'input', text: cmd }])

    if (cmd.trim() === 'clear') {
      setLines([{ type: 'system', text: 'Terminal cleared.\n' }])
      return
    }

    setLoading(true)
    try {
      // Every command goes through NLP first — admin speaks English.
      // NLP translates to CLI command → execute. If NLP is down → direct CLI fallback.
      // No prefix needed. "create a flow for acme" works. "track TRZ-X7K9M2" also works.
      let handled = false

      // Try flow design intent (creates full flow definition)
      if (/\b(design|create|build|make|set ?up)\b.*\bflow\b/i.test(cmd)) {
        try {
          const suggestion = await suggestFlow(cmd)
          if (suggestion.success) {
            setLines(prev => [...prev,
              { type: 'output', text: suggestion.explanation + '\n\nSuggested flow:\n' + JSON.stringify(suggestion.flowDefinition, null, 2) }
            ])
            handled = true
          }
        } catch { /* fall through */ }
      }

      // Try NLP translation (English → CLI command)
      if (!handled) {
        try {
          const nlp = await nlpCommand(cmd)
          if (nlp.understood && nlp.command) {
            setLines(prev => [...prev, { type: 'system', text: '→ ' + nlp.command }])
            const res = await onboardingApi.post('/api/cli/execute', { command: nlp.command })
            setLines(prev => [...prev, { type: 'output', text: res.data.output }])
            handled = true
          } else if (nlp.response) {
            setLines(prev => [...prev, { type: 'output', text: nlp.response }])
            handled = true
          }
        } catch { /* NLP unavailable — fall through to direct CLI */ }
      }

      // Direct CLI fallback (works without AI engine)
      if (!handled) {
        const res = await onboardingApi.post('/api/cli/execute', { command: cmd })
        setLines(prev => [...prev, { type: 'output', text: res.data.output }])
      }
    } catch (err) {
      setLines(prev => [...prev, {
        type: 'error',
        text: err.response?.data?.error || err.response?.data?.output || 'Command failed: ' + err.message
      }])
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      execute(input)
      setInput('')
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (historyIdx < history.length - 1) {
        const idx = historyIdx + 1
        setHistoryIdx(idx)
        setInput(history[idx])
      }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (historyIdx > 0) {
        const idx = historyIdx - 1
        setHistoryIdx(idx)
        setInput(history[idx])
      } else {
        setHistoryIdx(-1)
        setInput('')
      }
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <CommandLineIcon className="w-6 h-6 text-secondary" />
        <h1 className="text-2xl font-bold text-primary">Admin Terminal</h1>
      </div>
      <p className="text-secondary text-sm">Execute admin commands directly. Type <code className="bg-surface px-1 rounded">help</code> to see all available commands.</p>

      <div
        className="bg-gray-950 rounded-xl border border-gray-800 font-mono text-sm overflow-hidden"
        onClick={() => inputRef.current?.focus()}
        style={{ minHeight: '500px' }}>

        {/* Terminal output */}
        <div className="p-4 space-y-0.5 max-h-[600px] overflow-y-auto">
          {lines.map((line, i) => (
            <div key={i} className={`whitespace-pre-wrap leading-relaxed ${
              line.type === 'input' ? 'text-green-400' :
              line.type === 'error' ? 'text-red-400' :
              line.type === 'system' ? 'text-blue-400' :
              'text-gray-300'
            }`}>
              {line.type === 'input' && <span className="text-emerald-500">$ </span>}
              {line.text}
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        {/* Input line */}
        <div className="flex items-center border-t border-gray-800 px-4 py-2 bg-gray-900">
          <span className="text-emerald-500 mr-2 flex-shrink-0">$</span>
          <input
            ref={inputRef}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={loading}
            autoFocus
            className="flex-1 bg-transparent text-green-400 outline-none border-none text-sm font-mono placeholder-gray-600"
            placeholder={loading ? 'Processing...' : 'Enter command...'}
            spellCheck="false"
            autoComplete="off"
          />
          {loading && (
            <div className="w-4 h-4 border-2 border-green-500 border-t-transparent rounded-full animate-spin ml-2" />
          )}
        </div>
      </div>

      {/* Quick command buttons */}
      <div className="flex flex-wrap gap-2">
        {['status', 'accounts list', 'services', 'flows list', 'search recent 10', 'logs recent 20',
          'ask show me recent transfers', 'design flow: decrypt PGP then compress and route'].map(cmd => (
          <button key={cmd} onClick={() => { setInput(cmd); execute(cmd) }}
            className="px-3 py-1 bg-surface text-secondary text-xs font-mono rounded-lg hover:bg-hover transition-colors">
            {cmd}
          </button>
        ))}
      </div>
    </div>
  )
}
