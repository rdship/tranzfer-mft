import { Outlet, NavLink } from 'react-router-dom'
import { HomeIcon, DocumentTextIcon, MagnifyingGlassIcon, Cog6ToothIcon, ArrowRightOnRectangleIcon } from '@heroicons/react/24/outline'

export default function Layout({ user, onLogout }) {
  const nav = [
    { to: '/', icon: HomeIcon, label: 'Dashboard' },
    { to: '/transfers', icon: DocumentTextIcon, label: 'My Transfers' },
    { to: '/track', icon: MagnifyingGlassIcon, label: 'Track File' },
    { to: '/settings', icon: Cog6ToothIcon, label: 'Settings' },
  ]
  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white border-b px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center text-white font-bold text-sm">T</div>
          <span className="font-bold text-gray-900">Partner Portal</span>
          <nav className="flex gap-1 ml-6">
            {nav.map(n => (
              <NavLink key={n.to} to={n.to} end className={({isActive}) =>
                `flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-100'}`}>
                <n.icon className="w-4 h-4" />{n.label}
              </NavLink>
            ))}
          </nav>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-600">{user.username}</span>
          <span className="text-xs px-2 py-0.5 bg-blue-100 text-blue-700 rounded-full">{user.protocol}</span>
          <button onClick={onLogout} className="p-1.5 text-gray-400 hover:text-red-500 transition-colors">
            <ArrowRightOnRectangleIcon className="w-5 h-5" />
          </button>
        </div>
      </header>
      <main className="max-w-6xl mx-auto p-6"><Outlet /></main>
    </div>
  )
}
