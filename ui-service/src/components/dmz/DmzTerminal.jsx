import { useState, useRef, useEffect, useCallback } from 'react'
import { CommandLineIcon } from '@heroicons/react/24/outline'
import * as dmzApi from '../../api/dmz'

const HELP_TEXT = `Available commands:
  help              — show this help
  mappings          — list active port mappings
  listeners         — show listener status (bound ports)
  health            — show proxy health + features
  security stats    — full security metrics
  security summary  — quick security overview
  ip <address>      — lookup IP intelligence
  check <host> <port> — TCP reachability test
  egress <host> <port> — egress filter check
  zone <srcIp> <host> <port> — zone transition check
  qos               — QoS utilization stats
  backends          — backend health status
  audit stats       — audit logger stats
  audit flush       — trigger audit log flush
  rate-limits       — rate limiter stats
  metrics           — Prometheus metrics (raw)
  clear             — clear terminal`

const COMMANDS = {
  help: async () => HELP_TEXT,

  mappings: async () => {
    const data = await dmzApi.listMappings()
    if (!data?.length) return 'No mappings configured.'
    return data.map(m =>
      `  ${m.name.padEnd(20)} :${String(m.listenPort).padEnd(6)} → ${m.targetHost}:${m.targetPort}  [${m.securityTier || 'NONE'}] ${m.active ? 'ACTIVE' : 'INACTIVE'}  conns=${m.activeConnections || 0}`
    ).join('\n')
  },

  listeners: async () => {
    const data = await dmzApi.getListeners()
    if (!data?.length) return 'No listeners active.'
    return data.map(l =>
      `  ${l.name.padEnd(20)} :${String(l.listenPort).padEnd(6)} ${l.bound ? 'LISTENING' : 'DOWN'}  → ${l.targetHost}:${l.targetPort}  conns=${l.activeConnections || 0}  ${(l.bytesForwarded / 1048576).toFixed(1)}MB`
    ).join('\n')
  },

  health: async () => {
    const data = await dmzApi.getDmzHealth()
    const lines = [`  Status: ${data.status}`, `  Active Mappings: ${data.activeMappings}`, `  Security: ${data.securityEnabled ? 'ON' : 'OFF'}`]
    if (data.features?.length) lines.push(`  Features: ${data.features.join(', ')}`)
    if (data.unhealthyBackends?.length) lines.push(`  Unhealthy Backends: ${data.unhealthyBackends.join(', ')}`)
    return lines.join('\n')
  },

  'security stats': async () => {
    const data = await dmzApi.getSecurityStats()
    if (!data.securityEnabled) return 'Security layer disabled.'
    return JSON.stringify(data, null, 2)
  },

  'security summary': async () => {
    const data = await dmzApi.getSecuritySummary()
    if (!data.securityEnabled) return 'Security layer disabled.'
    const lines = [
      `  AI Engine: ${data.aiEngineAvailable ? 'Available' : 'Unavailable'}`,
      `  Tracked IPs: ${data.trackedIps}`,
      `  Active Connections: ${data.activeConnections}`,
      `  Verdict Cache Size: ${data.verdictCacheSize}`,
    ]
    if (data.connectionSummary) {
      const cs = data.connectionSummary
      lines.push(`  Total: ${cs.total || 0}  Allowed: ${cs.allowed || 0}  Blocked: ${cs.blocked || 0}  Throttled: ${cs.throttled || 0}`)
    }
    return lines.join('\n')
  },

  ip: async (args) => {
    if (!args[0]) return 'Usage: ip <address>'
    const data = await dmzApi.getIpIntelligence(args[0])
    return JSON.stringify(data, null, 2)
  },

  check: async (args) => {
    if (args.length < 2) return 'Usage: check <host> <port>'
    const data = await dmzApi.testReachability(args[0], parseInt(args[1]))
    return `  ${data.host}:${data.port} — ${data.reachable ? 'REACHABLE' : 'UNREACHABLE'} (${data.latencyMs}ms)${data.error ? ` [${data.error}]` : ''}`
  },

  egress: async (args) => {
    if (args.length < 2) return 'Usage: egress <host> <port>'
    const data = await dmzApi.checkEgress(args[0], parseInt(args[1]))
    return `  ${data.host}:${data.port} — ${data.allowed ? 'ALLOWED' : 'BLOCKED'} (${data.reason})${data.resolvedIp !== 'N/A' ? ` [resolved: ${data.resolvedIp}]` : ''}`
  },

  zone: async (args) => {
    if (args.length < 3) return 'Usage: zone <sourceIp> <targetHost> <targetPort>'
    const data = await dmzApi.checkZone(args[0], args[1], parseInt(args[2]))
    return `  ${data.sourceZone} (${data.sourceIp}) → ${data.targetZone} (${data.targetHost}:${data.targetPort}) — ${data.allowed ? 'ALLOWED' : 'BLOCKED'} (${data.reason})`
  },

  qos: async () => {
    const data = await dmzApi.getQosStats()
    if (!data.qosEnabled && data.qosEnabled !== undefined) return 'QoS disabled.'
    return JSON.stringify(data, null, 2)
  },

  backends: async () => {
    const data = await dmzApi.getBackendHealth()
    if (!data.healthCheckEnabled) return 'Backend health checks disabled.'
    const backends = data.backends || {}
    if (!Object.keys(backends).length) return 'No backends registered.'
    return Object.entries(backends).map(([name, h]) =>
      `  ${name.padEnd(20)} ${h.healthy !== false ? 'HEALTHY' : 'UNHEALTHY'}  failures=${h.consecutiveFailures || 0}`
    ).join('\n')
  },

  'audit stats': async () => {
    const data = await dmzApi.getAuditStats()
    if (!data.auditEnabled && data.auditEnabled !== undefined) return 'Audit logging disabled.'
    return JSON.stringify(data, null, 2)
  },

  'audit flush': async () => {
    await dmzApi.flushAudit()
    return 'Audit log flushed.'
  },

  'rate-limits': async () => {
    const data = await dmzApi.getRateLimits()
    if (!data.securityEnabled && data.securityEnabled !== undefined) return 'Security layer disabled.'
    return JSON.stringify(data, null, 2)
  },

  metrics: async () => {
    const data = await dmzApi.getMetrics()
    return typeof data === 'string' ? data.slice(0, 5000) : JSON.stringify(data, null, 2).slice(0, 5000)
  },
}

export default function DmzTerminal() {
  const [lines, setLines] = useState([{ type: 'system', text: 'DMZ Proxy Terminal v1.0 — Type "help" for available commands' }])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [history, setHistory] = useState(() => {
    try { return JSON.parse(localStorage.getItem('dmz-terminal-history') || '[]') } catch { return [] }
  })
  const [historyIdx, setHistoryIdx] = useState(-1)
  const bottomRef = useRef(null)
  const inputRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [lines])

  const execute = useCallback(async (cmd) => {
    const trimmed = cmd.trim()
    if (!trimmed) return

    // Save to history
    setHistory(prev => {
      const next = [trimmed, ...prev.filter(h => h !== trimmed)].slice(0, 50)
      localStorage.setItem('dmz-terminal-history', JSON.stringify(next))
      return next
    })
    setHistoryIdx(-1)

    setLines(prev => [...prev, { type: 'input', text: `$ ${trimmed}` }])

    if (trimmed === 'clear') {
      setLines([{ type: 'system', text: 'Terminal cleared.' }])
      return
    }

    setRunning(true)

    // Match command — try multi-word first, then single-word with args
    let handler = null
    let args = []

    // Try two-word commands first (e.g. "security stats", "audit flush")
    const twoWord = trimmed.split(/\s+/).slice(0, 2).join(' ').toLowerCase()
    if (COMMANDS[twoWord]) {
      handler = COMMANDS[twoWord]
      args = trimmed.split(/\s+/).slice(2)
    } else {
      const parts = trimmed.split(/\s+/)
      const singleCmd = parts[0].toLowerCase()
      if (COMMANDS[singleCmd]) {
        handler = COMMANDS[singleCmd]
        args = parts.slice(1)
      }
    }

    if (!handler) {
      setLines(prev => [...prev, { type: 'error', text: `Unknown command: ${trimmed.split(/\s+/)[0]}. Type "help" for available commands.` }])
      setRunning(false)
      return
    }

    try {
      const result = await handler(args)
      setLines(prev => [...prev, { type: 'output', text: result }])
    } catch (err) {
      setLines(prev => [...prev, { type: 'error', text: `Error: ${err.response?.data?.message || err.message}` }])
    } finally {
      setRunning(false)
    }
  }, [])

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !running) {
      execute(input)
      setInput('')
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (history.length > 0) {
        const next = Math.min(historyIdx + 1, history.length - 1)
        setHistoryIdx(next)
        setInput(history[next])
      }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (historyIdx > 0) {
        const next = historyIdx - 1
        setHistoryIdx(next)
        setInput(history[next])
      } else {
        setHistoryIdx(-1)
        setInput('')
      }
    }
  }

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 overflow-hidden font-mono text-sm">
      <div className="px-4 py-2 bg-gray-800 border-b border-gray-700 flex items-center gap-2">
        <div className="flex gap-1.5">
          <span className="w-3 h-3 rounded-full bg-red-500" />
          <span className="w-3 h-3 rounded-full bg-yellow-500" />
          <span className="w-3 h-3 rounded-full bg-green-500" />
        </div>
        <CommandLineIcon className="w-4 h-4 text-gray-400 ml-2" />
        <span className="text-gray-400 text-xs">DMZ Proxy Terminal</span>
      </div>

      <div
        className="p-4 h-96 overflow-y-auto space-y-1 cursor-text"
        onClick={() => inputRef.current?.focus()}
      >
        {lines.map((line, i) => (
          <div key={i} className={`whitespace-pre-wrap ${
            line.type === 'input' ? 'text-cyan-400'
              : line.type === 'error' ? 'text-red-400'
              : line.type === 'system' ? 'text-yellow-400'
              : 'text-gray-300'
          }`}>
            {line.text}
          </div>
        ))}

        <div className="flex items-center gap-1" ref={bottomRef}>
          <span className="text-green-400">$</span>
          <input
            ref={inputRef}
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={running}
            className="flex-1 bg-transparent text-gray-100 outline-none caret-green-400 disabled:opacity-50"
            autoFocus
            spellCheck={false}
          />
          {running && <span className="text-gray-500 animate-pulse">running...</span>}
        </div>
      </div>
    </div>
  )
}
