import { Link, useLocation, useParams } from 'react-router-dom'
import { ChevronRightIcon, HomeIcon } from '@heroicons/react/24/outline'

/**
 * Breadcrumbs — thin horizontal navigation showing the user where they are
 * in the hierarchy and letting them jump back one level with one click.
 *
 * Design principles (locked):
 *   • Speed        — zero fetches, derived entirely from URL pathname
 *   • Transparency — home icon + every segment labeled
 *   • Minimalism   — small text, chevron separator, no decoration
 *   • Guidance     — every segment except the last is clickable
 *   • Stability    — handles unknown paths gracefully (falls back to segment labels)
 *
 * Rendered once by Layout.jsx at the top of every page. Hidden on the
 * Operations shell because OperationsTabs already provides in-context nav.
 *
 * Route label registry — easier to maintain here than inline in 60 pages.
 * Keys are path prefixes; value can be a string or a function(params).
 */
const ROUTE_LABELS = {
  '/operations':              'Operations',
  '/operations/fabric':       'Flow Fabric',
  '/operations/activity':     'Activity Monitor',
  '/operations/live':         'Live Activity',
  '/operations/journey':      'Transfer Journey',
  '/partners':                'Partners',
  '/partner-setup':           'Onboard Partner',
  '/accounts':                'Transfer Accounts',
  '/users':                   'Users',
  '/flows':                   'Processing Flows',
  '/folder-mappings':         'Folder Mappings',
  '/folder-templates':        'Folder Templates',
  '/external-destinations':   'External Destinations',
  '/as2-partnerships':        'AS2/AS4 Partnerships',
  '/p2p':                     'P2P Transfers',
  '/file-manager':            'File Manager',
  '/server-instances':        'Server Instances',
  '/gateway':                 'Gateway & DMZ',
  '/dmz-proxy':               'DMZ Proxy',
  '/proxy-groups':            'Proxy Groups',
  '/storage':                 'Storage Manager',
  '/vfs-storage':             'VFS Storage',
  '/cas-dedup':               'CAS Dedup',
  '/cluster':                 'Cluster',
  '/observatory':             'Observatory',
  '/compliance':              'Compliance Profiles',
  '/security-profiles':       'Security Profiles',
  '/keystore':                'Keystore Manager',
  '/screening':               'Screening & DLP',
  '/quarantine':              'Quarantine',
  '/sentinel':                'Platform Sentinel',
  '/threat-intelligence':     'Threat Intelligence',
  '/proxy-intelligence':      'Proxy Intelligence',
  '/2fa':                     'Two-Factor Auth',
  '/blockchain':              'Blockchain Proof',
  '/notifications':           'Notifications',
  '/connectors':              'Connectors',
  '/sla':                     'SLA Agreements',
  '/scheduler':               'Scheduler',
  '/analytics':               'Analytics',
  '/predictions':             'Predictions',
  '/recommendations':         'AI Recommendations',
  '/auto-onboarding':         'Auto-Onboarding',
  '/edi-training':            'EDI AI Training',
  '/edi':                     'EDI Convert',
  '/edi-mapping':             'EDI Map Builder',
  '/edi-partners':            'EDI Partners',
  '/api-console':             'API Console',
  '/terminal':                'Terminal',
  '/platform-config':         'Platform Config',
  '/tenants':                 'Multi-Tenant',
  '/license':                 'License',
  '/services':                'Service Health',
  '/circuit-breakers':        'Circuit Breakers',
  '/migration':               'Migration Center',
  '/logs':                    'Logs',
  '/dlq':                     'Dead Letter Queue',
}

/** Pretty-print an unknown segment as a fallback label. */
function prettify(segment) {
  if (!segment) return ''
  return segment
    .replace(/-/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase())
}

export default function Breadcrumbs() {
  const { pathname } = useLocation()
  const params = useParams()

  // Hide on the root / login / portal / operations shell — those have their own nav
  if (
    pathname === '/' ||
    pathname === '/login' ||
    pathname.startsWith('/portal') ||
    pathname === '/operations' ||
    pathname.startsWith('/operations/')
  ) {
    return null
  }

  // Build crumb list from pathname segments
  const segments = pathname.split('/').filter(Boolean)
  if (segments.length === 0) return null

  const crumbs = []
  let soFar = ''
  for (let i = 0; i < segments.length; i++) {
    const seg = segments[i]
    soFar += '/' + seg
    // Detail segments that look like an ID (UUID-ish or numeric) — label them
    // with the parent entity type + ID. e.g. /partners/abc → "Partners › Partner abc"
    const isIdSegment = /^[0-9a-f]{8,}$/i.test(seg) || /^[0-9]+$/.test(seg)
    if (isIdSegment && i > 0) {
      const parentLabel = ROUTE_LABELS['/' + segments.slice(0, i).join('/')] || prettify(segments[i - 1])
      crumbs.push({
        to: soFar,
        label: `${singularize(parentLabel)} ${seg.slice(0, 8)}`,
      })
    } else {
      crumbs.push({
        to: soFar,
        label: ROUTE_LABELS[soFar] || prettify(seg),
      })
    }
  }

  return (
    <nav
      aria-label="Breadcrumb"
      className="flex items-center gap-1 px-1 text-xs mb-4"
      style={{ color: 'rgb(148, 163, 184)' }}
    >
      <Link
        to="/operations"
        className="flex items-center hover:underline"
        style={{ color: 'rgb(148, 163, 184)' }}
        title="Home (Operations Dashboard)"
      >
        <HomeIcon className="w-3.5 h-3.5" />
      </Link>
      {crumbs.map((c, i) => {
        const isLast = i === crumbs.length - 1
        return (
          <span key={c.to} className="flex items-center gap-1">
            <ChevronRightIcon className="w-3 h-3 opacity-50" />
            {isLast ? (
              <span style={{ color: 'rgb(var(--tx-primary))' }} className="font-medium truncate max-w-xs">
                {c.label}
              </span>
            ) : (
              <Link to={c.to} className="hover:underline truncate max-w-[10rem]">
                {c.label}
              </Link>
            )}
          </span>
        )
      })}
    </nav>
  )
}

function singularize(label) {
  if (!label) return ''
  // Drop plural 's' for single-entity detail breadcrumbs
  if (label.endsWith('ies')) return label.slice(0, -3) + 'y'
  if (label.endsWith('s')) return label.slice(0, -1)
  return label
}
