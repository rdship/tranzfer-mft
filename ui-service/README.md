# Admin UI

> Full-featured enterprise admin dashboard with 47 pages for managing all platform capabilities.

**Port:** 3000 | **Stack:** React 18 + Vite + TailwindCSS | **Required:** Recommended

---

## Overview

The admin UI is the primary management interface for the TranzFer MFT platform. It provides:

- **Dashboard** -- Real-time transfer stats, protocol breakdown, alerts, quick actions
- **Account Management** -- Create, edit, enable/disable transfer accounts
- **Flow Designer** -- Build file processing pipelines (encrypt, compress, screen, deliver)
- **Server Management** -- Configure protocol server instances with sectioned forms
- **Analytics** -- Charts, predictions, scaling recommendations
- **Security** -- Compliance profiles, encryption keys, 2FA management, keystore, screening
- **EDI Tools** -- Format detection, conversion, validation
- **Monitoring** -- Logs, activity feed, transfer journey tracking, observatory
- **Configuration** -- Platform settings, connectors, SLAs, scheduling
- **Partner Management** -- AS2/AS4 partnerships, external destinations, delivery endpoints
- **API Console** -- Test API endpoints directly from the browser
- **Terminal** -- Admin CLI interface in the browser
- **Intelligence** -- Observatory, Platform Sentinel, AI recommendations, circuit breakers

---

## Quick Start

### Development (hot reload)
```bash
cd ui-service
npm install
npm run dev
# Opens http://localhost:3000
```

### Production (Docker)
```bash
docker compose up -d ui-service
# Opens http://localhost:3000
```

### Build for production
```bash
npm run build
# Output in dist/
```

---

## Pages

### Overview
| Page | Path | Description |
|------|------|-------------|
| **Login** | `/login` | User authentication |
| **Dashboard** | `/dashboard` | Real-time stats, charts, alerts, quick actions |
| **Activity Monitor** | `/activity-monitor` | Primary transfer monitoring view |
| **Live Activity** | `/activity` | Live activity feed |
| **Transfer Journey** | `/journey` | Transfer journey tracker |

### Partners & Accounts
| Page | Path | Description |
|------|------|-------------|
| **Partner Management** | `/partners` | Partner overview and management |
| **Partner Detail** | `/partners/:id` | Individual partner profile |
| **Onboard Partner** | `/partner-setup` | Guided partner onboarding wizard |
| **Transfer Accounts** | `/accounts` | Transfer account CRUD |
| **Users** | `/users` | Platform user management |

### File Processing
| Page | Path | Description |
|------|------|-------------|
| **Processing Flows** | `/flows` | File flow pipeline designer |
| **Folder Mappings** | `/folder-mappings` | Source to destination routing rules |
| **Folder Templates** | `/folder-templates` | Reusable folder structure templates |
| **External Destinations** | `/external-destinations` | Forwarding targets |
| **AS2/AS4 Partnerships** | `/as2-partnerships` | B2B trading partner setup |
| **P2P Transfers** | `/p2p` | Peer-to-peer transfers |

### Servers & Infrastructure
| Page | Path | Description |
|------|------|-------------|
| **Server Instances** | `/server-instances` | Protocol server instance management |
| **Gateway & DMZ** | `/gateway` | Gateway health and routing |
| **DMZ Proxy** | `/dmz-proxy` | DMZ proxy configuration and status |
| **Proxy Groups** | `/proxy-groups` | Proxy group management |
| **Storage Manager** | `/storage` | Storage tier management |
| **VFS Storage** | `/vfs-storage` | Virtual file system storage dashboard |
| **CAS Dedup** | `/cas-dedup` | Content-addressed deduplication savings |

### Security & Compliance
| Page | Path | Description |
|------|------|-------------|
| **Compliance Profiles** | `/compliance` | Compliance profile management and violations |
| **Security Profiles** | `/security-profiles` | TLS/SSH/auth policies |
| **Keystore Manager** | `/keystore` | Key and certificate manager |
| **Screening & DLP** | `/screening` | Sanctions screening and data loss prevention |
| **Two-Factor Auth** | `/2fa` | Two-factor authentication setup |
| **Blockchain Proof** | `/blockchain` | Blockchain audit anchors |

### Notifications & Integrations
| Page | Path | Description |
|------|------|-------------|
| **Notifications** | `/notifications` | Notification rules and delivery |
| **Connectors** | `/connectors` | Webhook and alert integrations |
| **SLA Agreements** | `/sla` | SLA agreement management |
| **Scheduler** | `/scheduler` | Scheduled task management |

### Intelligence
| Page | Path | Description |
|------|------|-------------|
| **Observatory** | `/observatory` | Full platform observatory dashboard |
| **Platform Sentinel** | `/sentinel` | Automated rule engine (20th service) |
| **AI Recommendations** | `/recommendations` | AI-powered scaling and optimization tips |
| **Analytics** | `/analytics` | Metrics charts and dashboards |
| **Predictions** | `/predictions` | Scaling and capacity predictions |
| **Circuit Breakers** | `/circuit-breakers` | Service circuit breaker status |

### Tools
| Page | Path | Description |
|------|------|-------------|
| **EDI Translation** | `/edi` | EDI format detection, conversion, validation |
| **API Console** | `/api-console` | API testing tool |
| **Terminal** | `/terminal` | Admin CLI in browser |

### Administration
| Page | Path | Description |
|------|------|-------------|
| **Platform Config** | `/platform-config` | Environment and platform settings |
| **Multi-Tenant** | `/tenants` | Multi-tenant management |
| **License** | `/license` | License management |
| **Service Health** | `/services` | Service health monitoring (all 20 services) |
| **Logs** | `/logs` | Audit log viewer |

---

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| React | 18.3.1 | UI framework |
| Vite | 5.x | Build tool with hot reload |
| React Router | 6.23.1 | Client-side routing |
| TanStack Query | 5.40.0 | Server state management |
| Axios | 1.6.8 | HTTP client |
| Recharts | 2.12.7 | Analytics charts |
| TailwindCSS | 3.4.4 | Utility-first CSS |
| Heroicons | 2.1.4 | SVG icons |
| date-fns | 3.6.0 | Date formatting |
| react-hot-toast | 2.4.1 | Toast notifications |

---

## Project Structure

```
ui-service/
├── package.json
├── vite.config.js          <- Build configuration
├── tailwind.config.js      <- Tailwind theme
├── nginx.conf              <- Production nginx config
├── Dockerfile              <- Container build
└── src/
    ├── App.jsx             <- Root component with routing
    ├── main.jsx            <- Entry point
    ├── index.css           <- Global styles
    ├── pages/              <- Page components (one per route)
    ├── components/         <- Reusable UI components
    │   ├── Layout.jsx      <- Main layout (sidebar + content)
    │   ├── Header.jsx      <- Top navigation bar
    │   ├── Sidebar.jsx     <- Navigation menu
    │   ├── StatCard.jsx    <- Metrics display card
    │   ├── Modal.jsx       <- Dialog component
    │   └── ...
    └── context/
        ├── AuthContext.jsx      <- JWT auth state
        ├── BrandingContext.jsx  <- Custom branding
        └── ServiceContext.jsx   <- Service health state
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `/api` | Backend API base URL |
| Port (dev) | `3000` | Vite dev server port |
| Port (prod) | `80` | Nginx port in Docker |

The production build is served by Nginx, which proxies API requests to the api-gateway.

---

## Development

```bash
# Install dependencies
npm install

# Start development server (hot reload)
npm run dev

# Lint
npm run lint

# Build for production
npm run build

# Preview production build
npm run preview
```

---

## Dependencies

- **api-gateway** -- Routes API requests to backend services in production
- **onboarding-api** -- Authentication, accounts, transfers
- **config-service** -- Flows, connectors, settings
- **All other services** -- Dashboard communicates with every service via the API gateway
