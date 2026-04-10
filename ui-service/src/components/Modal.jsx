import { XMarkIcon } from '@heroicons/react/24/outline'

export default function Modal({ title, onClose, children, size = 'md' }) {
  const sizeClass = { sm: 'max-w-sm', md: 'max-w-lg', lg: 'max-w-2xl', xl: 'max-w-4xl' }[size]
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60">
      <div className={`bg-surface rounded-xl shadow-xl border border-border w-full ${sizeClass} max-h-[90vh] flex flex-col`}>
        <div className="flex items-center justify-between p-6 border-b border-border">
          <h3 className="text-lg font-semibold text-primary">{title}</h3>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-hover transition-colors">
            <XMarkIcon className="w-5 h-5 text-secondary" />
          </button>
        </div>
        <div className="p-6 overflow-y-auto">{children}</div>
      </div>
    </div>
  )
}
