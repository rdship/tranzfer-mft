import { useState, useRef, useEffect, useCallback } from 'react'
import toast from 'react-hot-toast'
import {
  PaperAirplaneIcon, SparklesIcon, ChevronDownIcon,
  ChevronRightIcon, LightBulbIcon, EyeIcon,
  ArrowPathIcon, QuestionMarkCircleIcon,
} from '@heroicons/react/24/outline'
import { chatWithMap } from '../api/ediConverter'

// ── Typing Indicator ─────────────────────────────────────────────────────────

function TypingIndicator() {
  return (
    <div className="flex items-center gap-1.5 px-3 py-2">
      {[0, 1, 2].map(i => (
        <div
          key={i}
          className="w-2 h-2 rounded-full"
          style={{
            background: 'rgb(var(--accent))',
            animation: `pulse 1.4s ease-in-out ${i * 0.2}s infinite`,
            opacity: 0.4,
          }}
        />
      ))}
      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 0.3; transform: scale(0.8); }
          50% { opacity: 1; transform: scale(1); }
        }
      `}</style>
    </div>
  )
}

// ── Collapsible Preview Block ────────────────────────────────────────────────

function PreviewBlock({ preview }) {
  const [expanded, setExpanded] = useState(false)

  if (!preview) return null

  const previewStr = typeof preview === 'string' ? preview : JSON.stringify(preview, null, 2)

  return (
    <div className="mt-2 rounded-lg overflow-hidden" style={{ border: '1px solid rgb(var(--border))' }}>
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-2 px-3 py-2 text-xs font-medium transition-colors"
        style={{ background: 'rgb(var(--canvas))', color: 'rgb(var(--tx-secondary))' }}
      >
        {expanded
          ? <ChevronDownIcon className="w-3.5 h-3.5" />
          : <ChevronRightIcon className="w-3.5 h-3.5" />}
        <EyeIcon className="w-3.5 h-3.5" />
        Preview
      </button>
      {expanded && (
        <pre
          className="px-3 py-2 text-[11px] overflow-auto"
          style={{
            background: 'rgb(var(--canvas))',
            color: '#4ade80',
            fontFamily: "'JetBrains Mono', monospace",
            maxHeight: 240,
            borderTop: '1px solid rgb(var(--border))',
          }}
        >
          {previewStr}
        </pre>
      )}
    </div>
  )
}

// ── Action Badge ─────────────────────────────────────────────────────────────

function ActionBadge({ action }) {
  const typeColor = {
    added: { bg: '#14532d22', color: '#4ade80', label: 'Added' },
    removed: { bg: '#7f1d1d22', color: '#f87171', label: 'Removed' },
    modified: { bg: '#78350f22', color: '#fbbf24', label: 'Modified' },
    default: { bg: 'rgb(var(--accent) / 0.1)', color: 'rgb(var(--accent))', label: 'Changed' },
  }

  const style = typeColor[action.type] || typeColor.default

  return (
    <span
      className="inline-flex items-center gap-1 text-[10px] font-medium px-1.5 py-0.5 rounded"
      style={{ background: style.bg, color: style.color }}
    >
      {style.label}: {action.field || action.description || ''}
    </span>
  )
}

// ── Message Bubble ───────────────────────────────────────────────────────────

function MessageBubble({ message, onFollowUp }) {
  const isUser = message.role === 'user'

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div
        className="max-w-[85%] rounded-xl px-3.5 py-2.5 space-y-2"
        style={{
          background: isUser
            ? 'rgb(var(--accent) / 0.15)'
            : 'rgb(var(--surface))',
          border: `1px solid ${isUser ? 'rgb(var(--accent) / 0.25)' : 'rgb(var(--border))'}`,
        }}
      >
        {/* Header for assistant */}
        {!isUser && (
          <div className="flex items-center gap-1.5 mb-1">
            <SparklesIcon className="w-3.5 h-3.5" style={{ color: 'rgb(var(--accent))' }} />
            <span className="text-[10px] font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--accent))' }}>
              Map Assistant
            </span>
          </div>
        )}

        {/* Content */}
        <div className="text-sm leading-relaxed whitespace-pre-wrap" style={{ color: 'rgb(var(--tx-primary))' }}>
          {message.content}
        </div>

        {/* Actions applied */}
        {message.actions && message.actions.length > 0 && (
          <div className="flex flex-wrap gap-1 pt-1">
            {message.actions.map((a, i) => (
              <ActionBadge key={i} action={a} />
            ))}
          </div>
        )}

        {/* Preview */}
        {message.preview && <PreviewBlock preview={message.preview} />}

        {/* Suggested follow-ups */}
        {message.suggestedFollowUp && message.suggestedFollowUp.length > 0 && (
          <div className="flex flex-wrap gap-1.5 pt-1.5">
            {message.suggestedFollowUp.map((text, i) => (
              <button
                key={i}
                onClick={() => onFollowUp(text)}
                className="text-[11px] px-2.5 py-1 rounded-full transition-colors"
                style={{
                  background: 'rgb(var(--accent) / 0.08)',
                  color: 'rgb(var(--accent))',
                  border: '1px solid rgb(var(--accent) / 0.2)',
                }}
              >
                {text}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// ── Quick Action Chips ───────────────────────────────────────────────────────

const QUICK_ACTIONS = [
  { label: "What's unmapped?", icon: QuestionMarkCircleIcon, message: "What fields aren't mapped yet?" },
  { label: 'Show preview', icon: EyeIcon, message: 'Show me a preview of the output' },
  { label: 'Suggest improvements', icon: LightBulbIcon, message: 'Suggest improvements' },
  { label: 'Explain a field', icon: SparklesIcon, message: 'What does [field] mean?' },
]

// ── Main Component ───────────────────────────────────────────────────────────

export default function MapAssistant({ mapId, currentMappings, onMapUpdated, sampleInput }) {
  const [messages, setMessages] = useState([
    {
      role: 'assistant',
      content: `I'm ready to help you refine this map. You can ask me to add or remove fields, explain mappings, or show a preview.\n\nTry one of the quick actions below, or type any instruction.`,
    },
  ])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const scrollRef = useRef(null)
  const inputRef = useRef(null)

  // Auto-scroll to bottom
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, sending])

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  // Send a message
  const handleSend = useCallback(async (text) => {
    const msg = (text || input).trim()
    if (!msg || sending) return

    // Add user message
    const userMsg = { role: 'user', content: msg }
    setMessages(prev => [...prev, userMsg])
    setInput('')
    setSending(true)

    try {
      const response = await chatWithMap(mapId, msg, {
        mappings: currentMappings || [],
        sampleInput: sampleInput || '',
      })

      // Build assistant message
      const assistantMsg = {
        role: 'assistant',
        content: response.message || response.content || 'Done.',
        actions: response.actions || undefined,
        preview: response.preview || undefined,
        suggestedFollowUp: response.suggestedFollowUp || response.suggestions || undefined,
      }

      setMessages(prev => [...prev, assistantMsg])

      // If response contains updated mappings, propagate up
      if (response.actions && response.actions.length > 0 && response.updatedMappings) {
        onMapUpdated?.(response.updatedMappings)
      }
    } catch (e) {
      const errMsg = e.response?.data?.message || e.message || 'Something went wrong'
      setMessages(prev => [
        ...prev,
        { role: 'assistant', content: `Sorry, I hit an error: ${errMsg}. Please try again.` },
      ])
      toast.error('Chat failed: ' + errMsg)
    } finally {
      setSending(false)
    }
  }, [input, sending, mapId, currentMappings, sampleInput, onMapUpdated])

  // Handle enter key
  const handleKeyDown = useCallback((e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }, [handleSend])

  // Quick action
  const handleQuickAction = useCallback((message) => {
    handleSend(message)
  }, [handleSend])

  // Follow-up from assistant suggestion
  const handleFollowUp = useCallback((text) => {
    handleSend(text)
  }, [handleSend])

  // Clear history
  const handleClear = useCallback(() => {
    setMessages([
      {
        role: 'assistant',
        content: 'History cleared. How can I help you with this map?',
      },
    ])
  }, [])

  return (
    <div className="flex flex-col h-full rounded-xl overflow-hidden"
      style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}>

      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 flex-shrink-0"
        style={{ borderBottom: '1px solid rgb(var(--border))' }}>
        <div className="flex items-center gap-2">
          <SparklesIcon className="w-4 h-4" style={{ color: 'rgb(var(--accent))' }} />
          <h3 className="text-sm font-bold" style={{ color: 'rgb(var(--tx-primary))' }}>
            Map Assistant
          </h3>
        </div>
        <button
          onClick={handleClear}
          className="p-1.5 rounded-lg transition-colors"
          style={{ color: 'rgb(var(--tx-muted))' }}
          title="Clear history"
        >
          <ArrowPathIcon className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Messages */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3 space-y-3" style={{ minHeight: 200 }}>
        {messages.map((msg, i) => (
          <MessageBubble key={i} message={msg} onFollowUp={handleFollowUp} />
        ))}
        {sending && (
          <div className="flex justify-start">
            <div className="rounded-xl px-3 py-2"
              style={{ background: 'rgb(var(--surface))', border: '1px solid rgb(var(--border))' }}>
              <div className="flex items-center gap-1.5 mb-1">
                <SparklesIcon className="w-3.5 h-3.5" style={{ color: 'rgb(var(--accent))' }} />
                <span className="text-[10px] font-bold uppercase tracking-wider" style={{ color: 'rgb(var(--accent))' }}>
                  Map Assistant
                </span>
              </div>
              <TypingIndicator />
            </div>
          </div>
        )}
      </div>

      {/* Input area */}
      <div className="flex-shrink-0 px-4 pb-3 space-y-2">
        {/* Quick actions */}
        <div className="flex flex-wrap gap-1.5">
          {QUICK_ACTIONS.map(qa => (
            <button
              key={qa.label}
              onClick={() => handleQuickAction(qa.message)}
              disabled={sending}
              className="flex items-center gap-1 text-[11px] px-2.5 py-1.5 rounded-lg transition-colors"
              style={{
                background: 'rgb(var(--canvas))',
                color: 'rgb(var(--tx-secondary))',
                border: '1px solid rgb(var(--border))',
                opacity: sending ? 0.5 : 1,
              }}
            >
              <qa.icon className="w-3 h-3" />
              {qa.label}
            </button>
          ))}
        </div>

        {/* Text input + send */}
        <div className="flex items-end gap-2">
          <div className="flex-1 rounded-xl overflow-hidden"
            style={{ background: 'rgb(var(--canvas))', border: '1px solid rgb(var(--border))' }}>
            <textarea
              ref={inputRef}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={sending}
              rows={1}
              className="w-full px-3 py-2.5 text-sm resize-none outline-none"
              style={{
                background: 'transparent',
                color: 'rgb(var(--tx-primary))',
                minHeight: 40,
                maxHeight: 120,
              }}
              placeholder="Type a message..."
            />
          </div>
          <button
            onClick={() => handleSend()}
            disabled={!input.trim() || sending}
            className="p-2.5 rounded-xl transition-all flex-shrink-0"
            style={{
              background: input.trim() && !sending ? 'rgb(var(--accent))' : 'rgb(var(--border))',
              color: input.trim() && !sending ? '#fff' : 'rgb(var(--tx-muted))',
            }}
          >
            <PaperAirplaneIcon className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  )
}
