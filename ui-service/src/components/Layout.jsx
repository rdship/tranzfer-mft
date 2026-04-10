import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'
import Header from './Header'

/**
 * Unified Scandinavian dark theme — all pages share the same
 * warm-dark palette defined in index.css under :root.
 * No more per-route theme switching; every page is visually cohesive.
 */

export default function Layout() {
  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar is always dark — uses hardcoded zinc palette, not theme vars */}
      <Sidebar />

      {/* Content area — driven by [data-theme] CSS variable tokens */}
      <div className="flex-1 flex flex-col overflow-hidden page-canvas">
        <Header />
        <main className="flex-1 overflow-y-auto p-6 animate-page">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
