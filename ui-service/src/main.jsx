import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider, QueryCache, MutationCache } from '@tanstack/react-query'
import toast, { Toaster } from 'react-hot-toast'
import App from './App'
import './index.css'

// Global error surface — every failed query and every failed mutation that
// doesn't have its own onError bubbles up to a single calm toast. The rule
// from R11 forward is "it cannot just silently fail in the background" —
// this is the safety net that catches pages which don't set per-query error
// handling. Toast ID is stable per query key so a flapping backend produces
// one toast, not a toast storm.
function extractMessage(err) {
  return err?.response?.data?.message
      || err?.response?.data?.error
      || err?.message
      || 'request failed'
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, staleTime: 30000, refetchOnWindowFocus: false },
    mutations: { retry: 0 },
  },
  queryCache: new QueryCache({
    onError: (err, query) => {
      // Per-query onError still wins — this global handler only fires when
      // the component didn't set one. Queries marked `meta: { silent: true }`
      // opt out (sidebar badge counters, background refreshes, etc.).
      if (query?.meta?.silent) return
      const key = Array.isArray(query?.queryKey) ? query.queryKey.join('-') : 'query-error'
      toast.error(`Couldn't load data: ${extractMessage(err)}`, { id: `qerr-${key}` })
    },
  }),
  mutationCache: new MutationCache({
    onError: (err, _vars, _ctx, mutation) => {
      // Mutations can still set their own onError; this only runs for the
      // ones that don't, so a button click with no error UI still surfaces
      // something visible instead of vanishing.
      if (mutation?.options?.onError) return
      toast.error(`Action failed: ${extractMessage(err)}`, { id: 'merr-global' })
    },
  }),
})

ReactDOM.createRoot(document.getElementById('root')).render(
  <BrowserRouter>
    <QueryClientProvider client={queryClient}>
      <App />
      <Toaster position="top-right" toastOptions={{ duration: 4000 }} />
    </QueryClientProvider>
  </BrowserRouter>
)
