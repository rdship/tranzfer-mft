# Admin UI

> Full-featured enterprise admin dashboard with 34+ pages for managing all platform capabilities.

**Port:** 3000 | **Stack:** React 18 + Vite + TailwindCSS | **Required:** Recommended

---

## Overview

The admin UI is the primary management interface for the TranzFer MFT platform. It provides:

- **Dashboard** — Real-time transfer stats, protocol breakdown, alerts, quick actions
- **Account Management** — Create, edit, enable/disable transfer accounts
- **Flow Designer** — Build file processing pipelines (encrypt → compress → screen → deliver)
- **Server Management** — Configure protocol server instances
- **Analytics** — Charts, predictions, scaling recommendations
- **Security** — Encryption keys, 2FA management, keystore, screening results
- **EDI Tools** — Format detection, conversion, validation
- **Monitoring** — Logs, activity feed, transfer journey tracking
- **Configuration** — Platform settings, connectors, SLAs, scheduling
- **Partner Management** — AS2 partnerships, external destinations, delivery endpoints
- **API Console** — Test API endpoints directly from the browser
- **Terminal** — Admin CLI interface in the browser

---

## Quick Start

### Development (hot reload)
```bash
cd admin-ui
npm install
npm run dev
# Opens http://localhost:3000
```

### Production (Docker)
```bash
docker compose up -d admin-ui
# Opens http://localhost:3000
```

### Build for production
```bash
npm run build
# Output in dist/
```

---

## Pages

| Page | Path | Description |
|------|------|-------------|
| **Login** | `/login` | User authentication |
| **Dashboard** | `/` | Real-time stats, charts, alerts, quick actions |
| **Accounts** | `/accounts` | Transfer account CRUD |
| **Users** | `/users` | Platform user management |
| **Flows** | `/flows` | File flow pipeline designer |
| **Servers** | `/servers` | Protocol server configuration |
| **Server Instances** | `/server-instances` | Deployed server instance management |
| **Folder Mappings** | `/folder-mappings` | Source → destination routing rules |
| **Security Profiles** | `/security-profiles` | TLS/SSH/auth policies |
| **External Destinations** | `/external-destinations` | Forwarding targets |
| **Delivery Endpoints** | `/delivery-endpoints` | Modern delivery configurations |
| **AS2 Partnerships** | `/as2-partnerships` | B2B trading partner setup |
| **Connectors** | `/connectors` | Webhook alert integrations |
| **Scheduler** | `/scheduler` | Scheduled task management |
| **Analytics** | `/analytics` | Metrics charts and dashboards |
| **Predictions** | `/predictions` | Scaling recommendations |
| **Monitoring** | `/monitoring` | Service health monitoring |
| **Activity** | `/activity` | Live activity feed |
| **Logs** | `/logs` | Audit log viewer |
| **Journey** | `/journey` | Transfer journey tracker |
| **Encryption** | `/encryption` | Encryption key management |
| **2FA** | `/2fa` | Two-factor authentication setup |
| **Keystore** | `/keystore` | Key and certificate manager |
| **Screening** | `/screening` | Sanctions screening results |
| **SLA** | `/sla` | SLA agreement management |
| **EDI** | `/edi` | EDI format tools |
| **Storage** | `/storage` | Storage tier management |
| **License** | `/license` | License management |
| **Gateway Status** | `/gateway` | Gateway health and routing |
| **Recommendations** | `/recommendations` | AI-powered recommendations |
| **Tenants** | `/tenants` | Multi-tenant management |
| **Blockchain** | `/blockchain` | Blockchain audit anchors |
| **Platform Config** | `/platform-config` | Environment settings |
| **P2P Transfers** | `/p2p` | Peer-to-peer transfers |
| **Terminal** | `/terminal` | Admin CLI in browser |
| **API Console** | `/api-console` | API testing tool |
| **Settings** | `/settings` | Application settings |

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
admin-ui/
├── package.json
├── vite.config.js          ← Build configuration
├── tailwind.config.js      ← Tailwind theme
├── nginx.conf              ← Production nginx config
├── Dockerfile              ← Container build
└── src/
    ├── App.jsx             ← Root component with routing
    ├── main.jsx            ← Entry point
    ├── index.css           ← Global styles
    ├── pages/              ← Page components (one per route)
    ├── components/         ← Reusable UI components
    │   ├── Layout.jsx      ← Main layout (sidebar + content)
    │   ├── Header.jsx      ← Top navigation bar
    │   ├── Sidebar.jsx     ← Navigation menu
    │   ├── StatCard.jsx    ← Metrics display card
    │   ├── Modal.jsx       ← Dialog component
    │   └── ...
    └── context/
        ├── AuthContext.jsx      ← JWT auth state
        ├── BrandingContext.jsx  ← Custom branding
        └── ServiceContext.jsx   ← Service health state
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

- **api-gateway** — Routes API requests to backend services in production
- **onboarding-api** — Authentication, accounts, transfers
- **config-service** — Flows, connectors, settings
- **All other services** — Dashboard communicates with every service via the API gateway
