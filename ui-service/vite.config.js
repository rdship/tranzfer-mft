import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/onboarding': { target: 'http://localhost:8080', rewrite: (p) => p.replace(/^\/api\/onboarding/, '') },
      '/api/config': { target: 'http://localhost:8084', rewrite: (p) => p.replace(/^\/api\/config/, '') },
      '/api/analytics': { target: 'http://localhost:8090', rewrite: (p) => p.replace(/^\/api\/analytics/, '') },
      '/api/license': { target: 'http://localhost:8089', rewrite: (p) => p.replace(/^\/api\/license/, '') },
    }
  }
})
