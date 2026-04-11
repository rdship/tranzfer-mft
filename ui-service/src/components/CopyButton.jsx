import { useState } from 'react'
import { ClipboardDocumentIcon, CheckIcon } from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'

/**
 * CopyButton — one-click copy of a trackId, SHA hash, key alias, or any
 * other monospaced identifier that appears throughout the UI. Gives users
 * a single, visible affordance instead of forcing a select-all + Cmd+C.
 *
 * Design principles (locked):
 *   • Speed         — single async call to navigator.clipboard, no re-renders elsewhere
 *   • Transparency  — shows a checkmark + toast after copy
 *   • Minimalism    — 14px icon, inline with text, no borders by default
 *   • Accessibility — button + title attribute + aria-label
 *   • Guidance      — tooltip tells user exactly what will be copied
 *
 * Usage:
 *   <CopyButton value={trackId} label="trackId" />
 *   <CopyButton value={hash} label="SHA-256" size="sm" />
 *   <CopyButton value={user.email} label="email" toastMessage="Email copied" />
 *
 * Props:
 *   value        string  — required, the text that will be copied
 *   label        string  — optional, for the tooltip ("trackId", "SHA-256", etc.)
 *   size         string  — "xs" | "sm" | "md" (default "sm")
 *   className    string  — extra classes for positioning
 *   toastMessage string  — override the default toast text
 */
export default function CopyButton({
  value,
  label = 'value',
  size = 'sm',
  className = '',
  toastMessage = null,
}) {
  const [copied, setCopied] = useState(false)

  const sizePx = size === 'xs' ? 'w-3 h-3' : size === 'md' ? 'w-4 h-4' : 'w-3.5 h-3.5'

  const handleCopy = async (e) => {
    e.preventDefault()
    e.stopPropagation()
    if (!value) return
    try {
      await navigator.clipboard.writeText(String(value))
      setCopied(true)
      toast.success(toastMessage || `Copied ${label}`, { duration: 1500 })
      setTimeout(() => setCopied(false), 1500)
    } catch (err) {
      toast.error(`Failed to copy ${label}: ${err.message}`)
    }
  }

  return (
    <button
      onClick={handleCopy}
      className={`inline-flex items-center justify-center p-0.5 rounded transition-colors opacity-60 hover:opacity-100 ${className}`}
      style={{ color: 'rgb(148, 163, 184)' }}
      title={`Copy ${label} to clipboard`}
      aria-label={`Copy ${label} to clipboard`}
    >
      {copied ? (
        <CheckIcon className={sizePx} style={{ color: 'rgb(74, 222, 128)' }} />
      ) : (
        <ClipboardDocumentIcon className={sizePx} />
      )}
    </button>
  )
}
