import { useState } from 'react'
import { ArrowDownTrayIcon } from '@heroicons/react/24/outline'
import toast from 'react-hot-toast'
import { getStepFileUrl, getFileByHashUrl, getFileByTrackUrl } from '../api/executions'

/**
 * FileDownloadButton — reusable download trigger for any file reference.
 *
 * Props:
 *   trackId   string?   — download file by track ID
 *   sha256    string?   — download file by hash
 *   stepInfo  object?   — { trackId, stepIndex, direction } for step input/output
 *   filename  string?   — suggested filename for the download
 *   label     string?   — button text (default: "Download")
 *   size      'sm'|'md' — button size (default: "sm")
 *   icon      boolean   — show download icon (default: true)
 */
export default function FileDownloadButton({ trackId, sha256, stepInfo, filename, label, size = 'sm', icon = true }) {
  const [loading, setLoading] = useState(false)

  const handleDownload = async (e) => {
    e?.stopPropagation?.()
    setLoading(true)
    try {
      let url
      if (stepInfo) {
        url = getStepFileUrl(stepInfo.trackId, stepInfo.stepIndex, stepInfo.direction)
      } else if (sha256) {
        url = getFileByHashUrl(sha256)
      } else if (trackId) {
        url = getFileByTrackUrl(trackId)
      } else return

      const token = localStorage.getItem('token')
      const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } })
      if (!res.ok) throw new Error(`Download failed (${res.status})`)

      const blob = await res.blob()
      const blobUrl = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = blobUrl
      a.download = filename || `file-${trackId || sha256 || 'download'}`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(blobUrl)
      toast.success('Download started')
    } catch (err) {
      toast.error(err.message || 'Download failed')
    } finally {
      setLoading(false)
    }
  }

  const sizeClasses = size === 'sm'
    ? 'px-2 py-1 text-xs gap-1'
    : 'px-3 py-1.5 text-sm gap-1.5'

  return (
    <button
      onClick={handleDownload}
      disabled={loading}
      className={`inline-flex items-center rounded-md font-medium transition-colors ${sizeClasses}
        text-[rgb(100,140,255)] hover:bg-[rgba(100,140,255,0.1)] disabled:opacity-50`}
    >
      {loading ? (
        <svg className="animate-spin w-3.5 h-3.5" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" className="opacity-25" />
          <path d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" fill="currentColor" className="opacity-75" />
        </svg>
      ) : icon ? (
        <ArrowDownTrayIcon className="w-3.5 h-3.5" />
      ) : null}
      {label || 'Download'}
    </button>
  )
}
