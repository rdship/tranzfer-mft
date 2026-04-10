import { PencilSquareIcon, ArrowTopRightOnSquareIcon } from '@heroicons/react/24/outline'
import { useNavigate } from 'react-router-dom'

/**
 * ConfigLink — inline clickable config reference with edit-on-click.
 *
 * Renders the entity name in accent color. On hover a pencil icon fades in.
 * Clicking the name fires onEdit({ type, id, name }).
 * If navigateTo is provided an external-link icon appears that navigates
 * to the full CRUD page.
 *
 * Props:
 *   type        'flow' | 'account' | 'partner' | 'server' | 'mapping' | 'destination' | 'securityProfile'
 *   id          string | number — entity ID (if known)
 *   name        string — display text
 *   onEdit      (info: { type, id, name }) => void
 *   navigateTo  string (optional) — route for the full page link
 */
export default function ConfigLink({ type, id, name, onEdit, navigateTo }) {
  const navigate = useNavigate()

  if (!name) return <span className="text-[rgb(var(--tx-muted))]">&mdash;</span>

  return (
    <span className="inline-flex items-center gap-1 group">
      <button
        onClick={(e) => { e.stopPropagation(); onEdit?.({ type, id, name }) }}
        className="text-[rgb(100,140,255)] hover:underline text-left font-medium text-sm leading-tight"
        title={`Edit ${type}: ${name}`}
      >
        {name}
      </button>
      <PencilSquareIcon
        className="w-3 h-3 text-[rgb(100,140,255)] opacity-0 group-hover:opacity-60 transition-opacity flex-shrink-0"
      />
      {navigateTo && (
        <button
          onClick={(e) => { e.stopPropagation(); navigate(navigateTo) }}
          className="opacity-0 group-hover:opacity-60 transition-opacity flex-shrink-0"
          title={`Open ${type} page`}
        >
          <ArrowTopRightOnSquareIcon className="w-3 h-3 text-[rgb(var(--tx-secondary))]" />
        </button>
      )}
    </span>
  )
}
