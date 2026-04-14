import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/onboarding': { target: 'http://localhost:8080', rewrite: (p) => p.replace(/^\/api\/onboarding/, '') },
      '/api/config':     { target: 'http://localhost:8084', rewrite: (p) => p.replace(/^\/api\/config/,     '') },
      '/api/analytics':  { target: 'http://localhost:8090', rewrite: (p) => p.replace(/^\/api\/analytics/,  '') },
      '/api/license':    { target: 'http://localhost:8089', rewrite: (p) => p.replace(/^\/api\/license/,    '') },
    },
  },
  build: {
    // Raise the warn threshold — our monolithic 2.2MB bundle was breaching
    // the default 500KB, but with manual vendor splitting below we expect
    // the largest per-page chunk to stay under 350KB.
    chunkSizeWarningLimit: 400,
    rollupOptions: {
      output: {
        // ── Vendor chunk sharding ────────────────────────────────────────
        // Pin common libraries to their own long-lived chunks so they cache
        // independently of page code. Changing a page does not invalidate
        // the vendor bundles, and vice versa.
        manualChunks(id) {
          // ── Shared app code ────────────────────────────────────────
          // Components, hooks, API modules, and contexts used by BOTH
          // eager (index) and lazy page chunks must live in their own
          // chunk. Without this, Vite inlines them into index → lazy
          // chunk imports from index → circular reference →
          // "Cannot access 'be' before initialization" crash.
          if (!id.includes('node_modules')) {
            if (id.includes('/components/')) return 'shared-components'
            if (id.includes('/hooks/'))      return 'shared-hooks'
            if (id.includes('/api/'))        return 'shared-api'
            if (id.includes('/context/'))    return 'shared-context'
            return undefined
          }

          // React core
          if (id.includes('react-dom') || /\/react\//.test(id) || id.includes('scheduler')) {
            return 'vendor-react'
          }
          // Router
          if (id.includes('react-router')) {
            return 'vendor-router'
          }
          // Data fetching
          if (id.includes('@tanstack/react-query')) {
            return 'vendor-query'
          }
          // Date + charts + misc heavy utils
          if (id.includes('date-fns')) {
            return 'vendor-date'
          }
          if (id.includes('recharts') || id.includes('d3-')) {
            return 'vendor-charts'
          }
          // Heroicons — imported all over the app
          if (id.includes('@heroicons')) {
            return 'vendor-icons'
          }
          // Toast
          if (id.includes('react-hot-toast')) {
            return 'vendor-toast'
          }
          // Everything else in node_modules goes to one general vendor chunk
          return 'vendor'
        },
      },
    },
  },
})
