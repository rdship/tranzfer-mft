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
      <header className="bg-white border-b border-gray-100 px-6 py-3 flex items-center justify-between shadow-sm">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-blue-700 rounded-lg flex items-center justify-center shadow-sm">
            <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4" />
            </svg>
          </div>
          <div className="hidden sm:block">
            <span className="font-bold text-gray-900 text-sm">Partner Portal</span>
            <span className="text-xs text-gray-400 ml-2">by TranzFer MFT</span>
          </div>
          <nav className="flex gap-0.5 ml-4 bg-gray-50 p-1 rounded-lg">
            {nav.map(n => (
              <NavLink key={n.to} to={n.to} end className={({isActive}) =>
                `flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-all ${isActive ? 'bg-white text-blue-700 shadow-sm' : 'text-gray-500 hover:text-gray-700'}`}>
                <n.icon className="w-4 h-4" /><span className="hidden md:inline">{n.label}</span>
              </NavLink>
            ))}
          </nav>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 bg-blue-100 rounded-full flex items-center justify-center text-blue-700 text-xs font-bold">
              {user.username?.charAt(0)?.toUpperCase() || 'U'}
            </div>
            <span className="text-sm font-medium text-gray-700 hidden sm:inline">{user.username}</span>
          </div>
          <span className="text-xs px-2.5 py-0.5 bg-blue-50 text-blue-700 rounded-full font-semibold ring-1 ring-inset ring-blue-600/10">{user.protocol}</span>
          <button onClick={onLogout} className="p-1.5 text-gray-400 hover:text-red-500 rounded-md hover:bg-red-50 transition-all" title="Sign out">
            <ArrowRightOnRectangleIcon className="w-5 h-5" />
          </button>
        </div>
      </header>
      <main className="max-w-6xl mx-auto p-6"><Outlet /></main>
    </div>
  )
}
