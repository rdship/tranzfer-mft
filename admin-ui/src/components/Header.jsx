import { useAuth } from '../context/AuthContext'
import { useBranding } from '../context/BrandingContext'
import { BellIcon, ArrowRightOnRectangleIcon } from '@heroicons/react/24/outline'
import { useQuery } from '@tanstack/react-query'
import { getDashboard } from '../api/analytics'

const ENV_COLORS = {
  DEV: 'bg-gray-200 text-gray-800',
  TEST: 'bg-yellow-200 text-yellow-900',
  CERT: 'bg-orange-200 text-orange-900',
  STAGING: 'bg-purple-200 text-purple-900',
  PROD: 'bg-red-200 text-red-900'
}

const CURRENT_ENV = import.meta.env.VITE_PLATFORM_ENVIRONMENT || 'PROD'

export default function Header() {
  const { user, logout } = useAuth()
  const { branding } = useBranding()
  const { data: dashboard } = useQuery({ queryKey: ['dashboard'], queryFn: getDashboard, refetchInterval: 30000 })
  const alertCount = dashboard?.alerts?.length || 0

  return (
    <header className="bg-white border-b border-gray-200 px-6 py-3 flex items-center justify-between">
      <div className="flex items-center gap-3">
        <h2 className="text-sm text-gray-500">Managed File Transfer Platform</h2>
        <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold tracking-wider ${ENV_COLORS[CURRENT_ENV] || ENV_COLORS.PROD}`}>
          {CURRENT_ENV}
        </span>
      </div>
      <div className="flex items-center gap-4">
        <button className="relative p-2 text-gray-500 hover:text-gray-700 rounded-lg hover:bg-gray-100">
          <BellIcon className="w-5 h-5" />
          {alertCount > 0 && (
            <span className="absolute -top-1 -right-1 bg-red-500 text-white text-xs rounded-full w-4 h-4 flex items-center justify-center">
              {alertCount}
            </span>
          )}
        </button>
        <div className="flex items-center gap-2 text-sm">
          <div className="w-7 h-7 rounded-full bg-blue-600 flex items-center justify-center text-white font-medium text-xs">
            {user?.email?.[0]?.toUpperCase() || 'A'}
          </div>
          <span className="text-gray-700 font-medium">{user?.email}</span>
          <span className="badge badge-blue">{user?.role}</span>
        </div>
        <button onClick={logout} className="p-2 text-gray-500 hover:text-red-500 rounded-lg hover:bg-gray-100 transition-colors">
          <ArrowRightOnRectangleIcon className="w-5 h-5" />
        </button>
      </div>
    </header>
  )
}
