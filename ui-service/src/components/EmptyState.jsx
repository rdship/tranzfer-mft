export default function EmptyState({ title, description, action }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="w-12 h-12 bg-hover rounded-xl flex items-center justify-center mb-4">
        <span className="text-2xl">📭</span>
      </div>
      <h3 className="text-base font-semibold text-primary mb-1">{title}</h3>
      {description && <p className="text-sm text-secondary mb-4">{description}</p>}
      {action}
    </div>
  )
}
