import { useCallback, useRef } from 'react'
import toast from 'react-hot-toast'

/**
 * useUndoToast — persistent undo toast for destructive actions.
 *
 * Design principle (stolen from Gmail, Linear, Notion, Slack):
 *
 *   When a user deletes / archives / cancels something, don't demand a
 *   confirmation modal for every click. That's paternalistic and slow.
 *   Instead, perform the action optimistically and surface a 7-second
 *   toast in the corner: "Deleted 3 flows. Undo"
 *
 *   If the user clicks Undo within the window, the action is reversed.
 *   If they don't, the action becomes permanent. Result: 99% of the
 *   time, users never click Undo and never even think about it. The
 *   1% of the time they made a mistake, one click reverses it.
 *
 *   This is WAY more respectful than a confirm modal for routine actions
 *   (archiving a record, dismissing a notification, clearing a filter).
 *
 *   RESERVE the ConfirmDialog primitive for truly irreversible actions:
 *   deleting a partner with 50 child accounts, terminating a running
 *   execution, wiping a volume. For anything else, use this instead.
 *
 * Usage:
 *   const { showUndoToast } = useUndoToast()
 *
 *   const handleArchive = (flow) => {
 *     archiveFlow(flow.id)   // optimistic — server call fires immediately
 *     showUndoToast({
 *       message: `Archived flow "${flow.name}"`,
 *       onUndo: () => unarchiveFlow(flow.id),
 *       durationMs: 7000,
 *     })
 *   }
 */
export default function useUndoToast() {
  const pendingRef = useRef(new Map())

  const showUndoToast = useCallback(({ message, onUndo, durationMs = 7000 }) => {
    const id = toast.custom((t) => (
      <div
        className="flex items-center gap-3 px-4 py-3 rounded-lg shadow-xl"
        style={{
          background: 'rgb(18, 18, 22)',
          border: '1px solid rgb(48, 48, 56)',
          color: 'rgb(var(--tx-primary))',
          fontSize: '13px',
          minWidth: '280px',
          maxWidth: '420px',
          animation: t.visible ? 'slideInRight 0.2s ease-out' : 'none',
        }}
      >
        <div className="flex-1 min-w-0">
          <div className="truncate">{message}</div>
        </div>
        <button
          onClick={() => {
            onUndo?.()
            toast.dismiss(t.id)
            pendingRef.current.delete(t.id)
          }}
          className="px-2 py-1 text-xs font-bold uppercase tracking-wide rounded transition-colors"
          style={{
            background: 'rgba(100, 140, 255, 0.15)',
            color: 'rgb(100, 140, 255)',
            letterSpacing: '0.05em',
          }}
        >
          Undo
        </button>
      </div>
    ), {
      duration: durationMs,
      position: 'bottom-right',
    })

    pendingRef.current.set(id, { message, onUndo })
    setTimeout(() => pendingRef.current.delete(id), durationMs + 500)

    return id
  }, [])

  return { showUndoToast }
}
