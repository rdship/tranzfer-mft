import { Outlet } from 'react-router-dom'
import OperationsTabs from '../components/OperationsTabs'

/**
 * OperationsLayout — thin wrapper that renders the persistent OperationsTabs
 * and delegates the actual page content to <Outlet />.
 *
 * Used by /operations/* routes defined in App.jsx:
 *   /operations              → Dashboard
 *   /operations/activity     → ActivityMonitor
 *   /operations/live         → Activity (live event stream)
 *   /operations/journey      → Journey
 *   /operations/fabric       → FabricDashboard
 *   /operations/instances    → Fabric Instances view (new)
 *
 * All existing routes (/dashboard, /activity-monitor, /fabric, etc.) are
 * preserved as redirects in App.jsx so bookmarks keep working.
 */
export default function OperationsLayout() {
  return (
    <div className="space-y-0">
      <OperationsTabs />
      <Outlet />
    </div>
  )
}
