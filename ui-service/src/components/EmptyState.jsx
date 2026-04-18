/**
 * R127: compact empty state per UX review — was 400 px tall, is now ~120 px.
 * Fine for first-run; stops dominating the viewport on every subsequent
 * visit. No emoji ("📄", "📭"). Single-line description. Primary action
 * retained.
 */
export default function EmptyState({ title, description, action }) {
  return (
    <div className="flex flex-col items-center justify-center py-8 text-center">
      <h3 className="text-sm font-semibold text-primary mb-1">{title}</h3>
      {description && <p className="text-xs text-secondary mb-3 max-w-sm">{description}</p>}
      {action}
    </div>
  )
}
