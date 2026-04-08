# API Gateway

> Nginx reverse proxy that routes all API requests and serves frontend applications.

**Port:** 80 (HTTP) / 443 (HTTPS) | **Type:** Nginx | **Required:** Recommended

---

## Overview

The API gateway is an Nginx reverse proxy that provides a single entry point for all platform services:

- **API routing** — Routes `/api/*` requests to the appropriate microservice
- **Frontend serving** — Serves ui-service, ftp-web-ui, and partner-portal
- **Security headers** — HSTS, X-Frame-Options, X-Content-Type-Options, CSP
- **Rate limiting** — 100 req/s for API, 10 req/s for auth endpoints
- **CORS handling** — Centralized CORS configuration
- **File upload** — Supports up to 512 MB uploads

---

## Quick Start

```bash
docker compose up -d api-gateway
# Access at http://localhost (or port 80)
```

---

## Routing Rules

### API Routes

| Path Pattern | Backend Service | Port |
|-------------|----------------|------|
| `/api/auth/` | onboarding-api | 8080 |
| `/api/accounts`, `/api/users` | onboarding-api | 8080 |
| `/api/folder-mappings` | onboarding-api | 8080 |
| `/api/servers`, `/api/security-profiles` | config-service | 8084 |
| `/api/flows`, `/api/connectors` | config-service | 8084 |
| `/api/external-destinations` | config-service | 8084 |
| `/api/platform-settings` | config-service | 8084 |
| `/api/scheduler` | config-service | 8084 |
| `/api/sla` | config-service | 8084 |
| `/api/gateway/` | gateway-service | 8085 |
| `/api/encrypt/`, `/api/decrypt/` | encryption-service | 8086 |
| `/api/forward/` | external-forwarder | 8087 |
| `/api/dmz/`, `/api/proxy/` | dmz-proxy | 8088 |
| `/api/v1/licenses` | license-service | 8089 |
| `/api/v1/analytics/` | analytics-service | 8090 |
| `/api/v1/ai/`, `/api/v1/proxy/` | ai-engine | 8091 |
| `/api/v1/screening/` | screening-service | 8092 |
| `/api/v1/keys` | keystore-manager | 8093 |
| `/api/v1/storage/` | storage-manager | 8094 |
| `/api/v1/edi/` | edi-converter | 8095 |
| `/api/partner/` | onboarding-api | 8080 |
| `/api/2fa/`, `/api/cli/` | onboarding-api | 8080 |
| `/api/p2p/`, `/api/journey` | onboarding-api | 8080 |
| `/api/v1/tenants`, `/api/v1/blockchain/` | onboarding-api | 8080 |

### Frontend Routes

| Path | Backend | Description |
|------|---------|-------------|
| `/` | ui-service:80 | Admin dashboard |
| `/portal/` | ftp-web-ui:80 | File manager |

---

## Security Headers

```nginx
X-Content-Type-Options: nosniff
X-Frame-Options: SAMEORIGIN
X-XSS-Protection: 1; mode=block
Strict-Transport-Security: max-age=31536000; includeSubDomains
Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'
```

---

## Rate Limiting

| Zone | Rate | Applies To |
|------|------|-----------|
| API | 100 req/s | All `/api/*` endpoints |
| Auth | 10 req/s | `/api/auth/*` endpoints |

---

## Configuration

### nginx.conf

The main configuration file is `nginx.conf`. Key settings:

| Setting | Value | Description |
|---------|-------|-------------|
| `client_max_body_size` | `512m` | Max file upload size |
| `proxy_connect_timeout` | `60s` | Backend connection timeout |
| `proxy_read_timeout` | `120s` | Backend read timeout |
| `keepalive_timeout` | `65s` | Client keepalive |

### Docker

```yaml
api-gateway:
  build: ./api-gateway
  ports:
    - "80:80"
    - "443:443"
  depends_on:
    - onboarding-api
    - config-service
    - ui-service
```

---

## Files

```
api-gateway/
├── Dockerfile          ← Nginx container build
├── nginx.conf          ← Main configuration
└── proxy_params        ← Common proxy settings
```

---

## Dependencies

All backend microservices and frontend applications that are being routed to.
