import { XMarkIcon } from '@heroicons/react/24/outline'
import { useId, useEffect } from 'react'

export default function Modal({ title, onClose, children, size = 'md' }) {
  const sizeClass = { sm: 'max-w-sm', md: 'max-w-lg', lg: 'max-w-2xl', xl: 'max-w-4xl' }[size]
  const titleId = useId()

  // N3 fix: close on Escape key — works for ALL modals including Flows
  useEffect(() => {
    const handler = (e) => { if (e.key === 'Escape') onClose?.() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60" role="dialog" aria-modal="true" aria-labelledby={titleId}
         onClick={(e) => { if (e.target === e.currentTarget) onClose?.() }}>
      <div className={`bg-surface rounded-xl shadow-xl border border-border w-full ${sizeClass} max-h-[90vh] flex flex-col`}>
        <div className="flex items-center justify-between p-6 border-b border-border">
          <h3 id={titleId} className="text-lg font-semibold text-primary">{title}</h3>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-hover transition-colors" aria-label="Close">
            <XMarkIcon className="w-5 h-5 text-secondary" />
          </button>
        </div>
        <div className="p-6 overflow-y-auto">{children}</div>
      </div>
    </div>
  )
}
