# Partner Portal

> Self-service portal for trading partners with transfer tracking, dashboards, and account management.

**Port:** 3002 | **Stack:** React 18 + Vite + TailwindCSS | **Required:** Optional

---

## Overview

The partner portal gives trading partners a self-service interface to manage their file transfers without admin involvement:

- **Dashboard** — KPI overview (transfers today, success rate, SLA compliance)
- **Transfer history** — Paginated list of sent/received files with filtering
- **File tracking** — Real-time tracking by Track ID (e.g., `TRZA3X5T3LUY`)
- **Delivery receipts** — Download proof of delivery
- **Account settings** — SSH key rotation, password changes
- **SLA compliance** — View SLA status for the last 7 days

---

## Quick Start

### Development
```bash
cd partner-portal
npm install
npm run dev
# Opens http://localhost:3002
```

### Production
```bash
docker compose up -d onboarding-api partner-portal
# Opens http://localhost:3002
```

---

## Pages

| Page | Path | Description |
|------|------|-------------|
| **Login** | `/login` | Partner login (uses transfer account credentials) |
| **Dashboard** | `/` | KPI overview with greeting |
| **Transfers** | `/transfers` | Transfer history with pagination and filtering |
| **Track** | `/track` | Real-time file tracking by Track ID |
| **Settings** | `/settings` | SSH key rotation, password change |

---

## Features

### Partner Login
Partners login with their **transfer account** credentials (username + password), not admin credentials. The partner portal API returns a JWT token scoped to the partner's account.

### Dashboard
- Greeting based on time of day
- Total transfers, today's transfers, weekly transfers
- Failed transfers count
- Success rate percentage
- Last transfer timestamp
- SLA compliance indicator

### Transfer Tracking
Enter a Track ID to see the complete journey:
- File metadata (name, size, checksums)
- Journey stages with timestamps
- Processing steps and durations
- Integrity verification
- Audit trail

### Account Management
- **Rotate SSH key** — Upload a new public key
- **Change password** — Requires current password
- **Test connection** — View connection instructions

---

## Tech Stack

| Technology | Purpose |
|-----------|---------|
| React 18 | UI framework |
| Vite | Build tool |
| React Router | Client-side routing |
| Axios | HTTP client |
| TailwindCSS | Styling |
| Heroicons | Icons |
| react-hot-toast | Notifications |

---

## Project Structure

```
partner-portal/
├── package.json
├── vite.config.js
├── tailwind.config.js
├── nginx.conf
├── Dockerfile
└── src/
    ├── App.jsx
    ├── main.jsx
    ├── pages/
    │   ├── Login.jsx       ← Partner authentication
    │   ├── Dashboard.jsx   ← KPI overview
    │   ├── Transfers.jsx   ← Transfer history
    │   ├── Track.jsx       ← File tracking
    │   └── Settings.jsx    ← Account management
    └── components/
        └── Layout.jsx      ← Navigation frame
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_URL` | `/api` | API base URL |
| Port (dev) | `3002` | Vite dev server port |
| Port (prod) | `80` | Nginx port in Docker |

---

## Dependencies

- **onboarding-api** — Partner portal API (`/api/partner/*` endpoints)
