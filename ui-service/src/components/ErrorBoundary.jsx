import { Component } from 'react'

/**
 * Error boundary that catches render errors in child components.
 * Prevents the entire app from crashing — shows a recovery UI instead.
 *
 * Usage:
 *   <ErrorBoundary><Dashboard /></ErrorBoundary>
 *   <ErrorBoundary fallback={<CustomError />}><Page /></ErrorBoundary>
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, errorInfo) {
    console.error('[ErrorBoundary]', error, errorInfo)
  }

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) return this.props.fallback

      return (
        <div style={{ padding: '2rem', textAlign: 'center' }}>
          <div style={{
            maxWidth: '500px', margin: '4rem auto', padding: '2rem',
            borderRadius: '12px', background: 'rgb(127 29 29 / 0.15)', border: '1px solid rgb(239 68 68 / 0.25)'
          }}>
            <div style={{ fontSize: '2.5rem', marginBottom: '1rem' }}>⚠</div>
            <h2 style={{ color: '#f87171', marginBottom: '0.5rem', fontSize: '1.25rem' }}>
              Something went wrong
            </h2>
            <p style={{ color: 'rgb(var(--tx-secondary))', fontSize: '0.875rem', marginBottom: '1.5rem' }}>
              {this.state.error?.message || 'An unexpected error occurred'}
            </p>
            <button
              onClick={() => this.setState({ hasError: false, error: null })}
              style={{
                padding: '0.5rem 1.5rem', borderRadius: '6px', cursor: 'pointer',
                backgroundColor: '#dc2626', color: 'white', border: 'none', fontWeight: 500,
                marginRight: '0.5rem'
              }}
            >
              Try Again
            </button>
            <button
              onClick={() => window.location.reload()}
              style={{
                padding: '0.5rem 1.5rem', borderRadius: '6px', cursor: 'pointer',
                background: 'rgb(var(--surface))', color: '#f87171', border: '1px solid rgb(239 68 68 / 0.4)', fontWeight: 500
              }}
            >
              Reload Page
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
