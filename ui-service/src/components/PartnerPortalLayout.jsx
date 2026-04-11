import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import {
  HomeIcon,
  ArrowsRightLeftIcon,
  AdjustmentsHorizontalIcon,
  ArrowRightOnRectangleIcon,
  BoltIcon,
} from '@heroicons/react/24/outline'

const navItems = [
  { to: '/portal',           icon: HomeIcon,                  label: 'Dashboard', end: true },
  { to: '/portal/transfers', icon: ArrowsRightLeftIcon,       label: 'My Transfers' },
  { to: '/portal/settings',  icon: AdjustmentsHorizontalIcon, label: 'Settings' },
]

export default function PartnerPortalLayout() {
  const navigate = useNavigate()

  /* Read partner info from localStorage */
  const partnerRaw = localStorage.getItem('partner-user')
  const partner = partnerRaw ? JSON.parse(partnerRaw) : {}
  const username = partner.username || partner.name || 'Partner'
  const accountType = partner.accountType || partner.protocol || 'SFTP'
  const initials = (username[0] || 'P').toUpperCase()

  const handleLogout = () => {
    localStorage.removeItem('partner-token')
    localStorage.removeItem('partner-user')
    navigate('/portal/login')
  }

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside
        className="w-56 flex flex-col overflow-hidden flex-shrink-0"
        style={{ background: 'rgb(var(--canvas))' }}
      >
        {/* Brand */}
        <div
          className="px-4 py-4 flex items-center gap-2.5"
          style={{ borderBottom: '1px solid rgb(var(--border))' }}
        >
          <div
            className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0"
            style={{ background: 'rgb(100, 140, 255)' }}
          >
            <BoltIcon className="w-4 h-4 text-white" />
          </div>
          <div className="min-w-0">
            <p className="text-white font-bold text-sm leading-tight truncate">TranzFer</p>
            <p className="text-[10px] leading-tight" style={{ color: 'rgb(70, 75, 85)' }}>Partner Portal</p>
          </div>
        </div>

        {/* Navigation */}
        <nav className="flex-1 overflow-y-auto px-2 py-3 space-y-5">
          <div>
            <p
              className="px-2 mb-1.5"
              style={{
                fontSize: '0.6rem',
                fontWeight: 700,
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                color: 'rgb(70, 75, 85)',
              }}
            >
              Navigation
            </p>
            <div className="space-y-0.5">
              {navItems.map(item => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => `sidebar-nav-item ${isActive ? 'active' : ''}`}
                  title={item.label}
                >
                  <item.icon className="w-[15px] h-[15px] flex-shrink-0" />
                  <span className="truncate">{item.label}</span>
                </NavLink>
              ))}
            </div>
          </div>
        </nav>

        {/* User Footer */}
        <div
          className="px-3 py-3 flex items-center gap-2.5 flex-shrink-0"
          style={{ borderTop: '1px solid rgb(var(--border))' }}
        >
          {/* Avatar */}
          <div
            className="w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 text-white font-semibold text-xs"
            style={{ background: 'rgb(100, 140, 255)' }}
          >
            {initials}
          </div>

          {/* Info */}
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium truncate leading-tight" style={{ color: 'rgb(160, 165, 175)' }}>
              {username}
            </p>
            <p style={{ fontSize: '9px', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'rgb(90, 95, 105)' }}>
              {accountType}
            </p>
          </div>

          {/* Logout */}
          <button
            onClick={handleLogout}
            title="Sign out"
            aria-label="Sign out"
            className="p-1.5 rounded-md transition-all flex-shrink-0"
            style={{ color: 'rgb(90, 95, 105)' }}
            onMouseEnter={e => { e.currentTarget.style.background = 'rgb(25, 25, 30)'; e.currentTarget.style.color = 'rgb(240, 120, 120)' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'rgb(90, 95, 105)' }}
          >
            <ArrowRightOnRectangleIcon className="w-4 h-4" />
          </button>
        </div>
      </aside>

      {/* Main content area */}
      <div className="flex-1 flex flex-col overflow-hidden page-canvas">
        <main className="flex-1 overflow-y-auto p-6 animate-page">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
