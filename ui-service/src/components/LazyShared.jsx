import { lazy, Suspense } from 'react'

/**
 * Lazy-loaded wrappers for shared components that cause Vite chunk circular
 * dependencies when eagerly imported by multiple pages.
 *
 * Usage: import { LazyExecutionDetailDrawer } from '../components/LazyShared'
 * instead of: import ExecutionDetailDrawer from '../components/ExecutionDetailDrawer'
 */

const ExecutionDetailDrawer = lazy(() => import('./ExecutionDetailDrawer'))
const FileDownloadButton = lazy(() => import('./FileDownloadButton'))
const ConfigInlineEditor = lazy(() => import('./ConfigInlineEditor'))

export function LazyExecutionDetailDrawer(props) {
  return <Suspense fallback={null}><ExecutionDetailDrawer {...props} /></Suspense>
}

export function LazyFileDownloadButton(props) {
  return <Suspense fallback={null}><FileDownloadButton {...props} /></Suspense>
}

export function LazyConfigInlineEditor(props) {
  return <Suspense fallback={null}><ConfigInlineEditor {...props} /></Suspense>
}
