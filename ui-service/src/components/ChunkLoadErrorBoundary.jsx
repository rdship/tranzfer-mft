import { Component } from 'react'
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline'

/**
 * ChunkLoadErrorBoundary — catches chunk-load failures from React.lazy()
 * and renders a recovery UI instead of a blank white screen.
 *
 * Why this exists:
 *   After a deploy, any user who had the previous index.html loaded in
 *   a tab can hit the following failure mode:
 *     1. User clicks a page they haven't visited yet
 *     2. React.lazy() tries to fetch `/assets/PageName-HASH.js`
 *     3. That hash no longer exists on the server (new deploy replaced it)
 *     4. Chunk load fails with a cryptic error
 *     5. Without this boundary: white screen, user thinks app is broken
 *     6. WITH this boundary: clean "App was updated" message + Refresh button
 *
 * The refresh button forces a hard reload so the fresh index.html loads
 * and the new chunk hashes take effect. Zero downtime for users.
 *
 * Design principles (locked):
 *   • Stability     — the whole point; no white screens ever
 *   • Resilience    — a one-click recovery path from any chunk failure
 *   • Transparency  — honest "new version available" message, not a hidden bug
 *   • Minimalism    — single card, two buttons (Refresh / Go Home)
 *   • Guidance      — tells the user exactly what happened and what to do
 *
 * React error boundaries must be class components — there's no hook for
 * componentDidCatch yet.
 */
export default class ChunkLoadErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null, isChunkError: false }
  }

  static getDerivedStateFromError(error) {
    const msg = error?.message || ''
    const isChunkError =
      error?.name === 'ChunkLoadError' ||
      /Loading chunk \d+ failed/.test(msg) ||
      /Failed to fetch dynamically imported module/.test(msg) ||
      /Importing a module script failed/.test(msg)
    const isInitError = /before initialization/.test(msg)
    return { hasError: true, error, isChunkError, isInitError }
  }

  componentDidCatch(error, info) {
    console.error('[ChunkLoadErrorBoundary] caught error:', error, info)
    // "Cannot access X before initialization" = Vite circular dep.
    // Auto-reload once — the shared chunk initializes on second load.
    if (this.state.isInitError && !sessionStorage.getItem('chunk-retry')) {
      sessionStorage.setItem('chunk-retry', '1')
      window.location.reload()
    }
  }

  handleRefresh = () => {
    // Full reload — bypasses all SW/cache and picks up the latest index.html
    window.location.reload()
  }

  handleGoHome = () => {
    // Navigate to root + reload to ensure a clean state
    window.location.href = '/operations'
  }

  render() {
    if (!this.state.hasError) return this.props.children

    const isChunk = this.state.isChunkError
    const title   = isChunk ? 'App was updated'           : 'Something went wrong'
    const hint    = isChunk
      ? 'The admin UI was updated while this tab was open. Refresh to load the latest version and continue.'
      : 'The page failed to render. Refresh the tab to try again.'
    const errMsg = this.state.error?.message || String(this.state.error || '')

    return (
      <div className="flex items-center justify-center min-h-[60vh] px-4">
        <div
          className="max-w-md w-full rounded-xl p-6 text-center"
          style={{
            background: 'rgb(var(--surface, 18 18 22))',
            border: '1px solid rgba(239, 68, 68, 0.25)',
          }}
        >
          <div
            className="inline-flex items-center justify-center w-12 h-12 rounded-full mb-3"
            style={{ background: 'rgba(239, 68, 68, 0.1)' }}
          >
            <ExclamationTriangleIcon className="w-6 h-6" style={{ color: 'rgb(248, 113, 113)' }} />
          </div>
          <h3 className="text-base font-bold mb-1" style={{ color: 'rgb(var(--tx-primary, 255 255 255))' }}>
            {title}
          </h3>
          <p className="text-xs mb-3" style={{ color: 'rgb(148, 163, 184)' }}>
            {hint}
          </p>
          {errMsg && (
            <pre
              className="text-[10px] text-left rounded p-2 mb-3 overflow-x-auto font-mono max-h-24"
              style={{ background: 'rgb(var(--canvas, 12 12 15))', color: 'rgb(148, 163, 184)' }}
            >
              {errMsg}
            </pre>
          )}
          <div className="flex items-center justify-center gap-2">
            <button
              onClick={this.handleRefresh}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
              style={{ background: 'rgb(var(--accent, 79 70 229))', color: '#fff' }}
            >
              <ArrowPathIcon className="w-3.5 h-3.5" />
              Refresh Page
            </button>
            <button
              onClick={this.handleGoHome}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors"
              style={{
                background: 'transparent',
                border: '1px solid rgb(48, 48, 56)',
                color: 'rgb(148, 163, 184)',
              }}
            >
              Go to Dashboard
            </button>
          </div>
        </div>
      </div>
    )
  }
}
