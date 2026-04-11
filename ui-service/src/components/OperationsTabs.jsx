import { NavLink, useLocation } from 'react-router-dom'
import {
  ChartBarIcon,
  WifiIcon,
  MagnifyingGlassIcon,
  BoltIcon,
  HomeIcon,
} from '@heroicons/react/24/outline'

/**
 * OperationsTabs — persistent tab bar rendered above every Operations page.
 *
 * This is the core UX primitive that ties Dashboard, Activity Monitor,
 * Live Activity, Journey, Flow Fabric, and Instances into a single
 * coherent experience. Users stay in one "place" and swap views via tabs
 * instead of hunting through the sidebar.
 *
 * Design principles (locked):
 *   • Speed         — NavLink uses client-side routing, no full-page reload
 *   • Information   — active tab has strong visual contrast + matching underline
 *   • Minimalism    — monochrome tabs, one accent color, no icons gratuitous
 *   • Guidance      — short labels, clear ordering from overview → detail
 *   • Attractiveness— smooth transitions, consistent dark palette
 */
const TABS = [
  { to: '/operations',             label: 'Dashboard',        icon: HomeIcon,           end: true },
  { to: '/operations/fabric',      label: 'Flow Fabric',      icon: BoltIcon },
  { to: '/operations/activity',    label: 'Activity Monitor', icon: ChartBarIcon },
  { to: '/operations/live',        label: 'Live Activity',    icon: WifiIcon },
  { to: '/operations/journey',     label: 'Transfer Journey', icon: MagnifyingGlassIcon },
]

export default function OperationsTabs() {
  const { search } = useLocation()
  return (
    <div
      className="flex items-center gap-1 overflow-x-auto pb-0.5"
      style={{
        borderBottom: '1px solid rgb(var(--border))',
        marginBottom: '1rem',
      }}
    >
      {TABS.map(t => {
        const Icon = t.icon
        return (
          <NavLink
            key={t.to}
            to={{ pathname: t.to, search: t.to === '/operations/activity' ? search : '' }}
            end={t.end}
            className={({ isActive }) =>
              `flex items-center gap-1.5 px-3 py-2 text-xs font-semibold whitespace-nowrap transition-colors ${
                isActive
                  ? 'text-white border-b-2'
                  : 'text-secondary hover:text-primary border-b-2 border-transparent'
              }`
            }
            style={({ isActive }) => ({
              borderBottomColor: isActive ? 'rgb(var(--accent, 79 70 229))' : 'transparent',
              marginBottom: '-1px',
            })}
          >
            <Icon className="w-3.5 h-3.5" />
            {t.label}
          </NavLink>
        )
      })}
    </div>
  )
}
