import ErrorBoundary from './ErrorBoundary'

/**
 * Page-level error boundary with navigation-aware recovery.
 * Wraps each page route to isolate failures.
 */
export default function PageErrorBoundary({ children, pageName }) {
  return (
    <ErrorBoundary
      fallback={
        <div style={{ padding: '2rem', textAlign: 'center' }}>
          <div style={{
            maxWidth: '500px', margin: '4rem auto', padding: '2rem',
            borderRadius: '12px', background: 'rgb(127 29 29 / 0.15)', border: '1px solid rgb(239 68 68 / 0.25)'
          }}>
            <div style={{ fontSize: '2.5rem', marginBottom: '1rem' }}>⚠</div>
            <h2 style={{ color: '#f87171', marginBottom: '0.5rem', fontSize: '1.25rem' }}>
              {pageName || 'Page'} failed to load
            </h2>
            <p style={{ color: 'rgb(var(--tx-secondary))', fontSize: '0.875rem', marginBottom: '1.5rem' }}>
              This page encountered an error. Other pages should still work normally.
            </p>
            <button
              onClick={() => window.location.href = '/dashboard'}
              className="btn-primary"
              style={{ marginRight: '0.5rem' }}
            >
              Go to Dashboard
            </button>
            <button
              onClick={() => window.location.reload()}
              className="btn-secondary"
            >
              Reload
            </button>
          </div>
        </div>
      }
    >
      {children}
    </ErrorBoundary>
  )
}
