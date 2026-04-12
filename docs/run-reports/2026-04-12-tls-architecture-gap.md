# TLS Architecture Gap — Nginx Frontend Cert Distribution

**Date:** 2026-04-12
**Status:** Partially working — Java services have HTTPS, nginx frontends need wiring

---

## Current State

### What Works (Java Services — 22 services)
`PlatformTlsConfig.java` auto-generates or loads a shared TLS cert for ALL Java services:
- Cert: `CN=*.filetransfer.local`, signed by `CN=TranzFer CA`
- Stored at: `/tmp/platform-tls.p12` (PKCS12 keystore)
- Dual-port: HTTP on original port + HTTPS on port+1000 (8080→9080)
- Enabled via: `PLATFORM_TLS_ENABLED=true` in docker-compose

### What Doesn't Work (Nginx Frontends — 4 services)
`ui-service`, `partner-portal`, `ftp-web-ui`, `api-gateway` are nginx containers that need TLS certs but CAN'T use `PlatformTlsConfig.java` (it's Java/Tomcat only).

Currently:
- `api-gateway` has its own self-signed cert via `entrypoint.sh` (works but NOT from keystore-manager)
- `ui-service`, `partner-portal`, `ftp-web-ui` have NO TLS — HTTP only on port 8080

---

## Proposed Solution: Nginx Entrypoint Fetches Cert from Keystore-Manager

### Architecture

```
[keystore-manager]
    │
    │ GET /api/v1/keys/platform-tls/download?format=cert  → server.crt
    │ GET /api/v1/keys/platform-tls/download?format=private → server.key
    │
    ▼
[nginx entrypoint.sh] ← runs on boot, before nginx starts
    │
    │ 1. Wait for keystore-manager health
    │ 2. Authenticate (service account or internal key)
    │ 3. Download cert + key as PEM
    │ 4. Save to /etc/nginx/ssl/server.crt + server.key
    │ 5. Start nginx with SSL listener
    │
    ▼
[nginx] ← listens on 8080 (HTTP) + 8443 (HTTPS)
```

### Implementation Steps for CTO

1. **Keystore-manager: ensure cert download returns proper PEM**
   - `GET /api/v1/keys/{alias}/download?format=cert` should return X.509 certificate (BEGIN CERTIFICATE), not public key (BEGIN PUBLIC KEY)
   - `GET /api/v1/keys/{alias}/download?format=private` should return RSA private key
   - Currently: both return the public key material — the download format routing may have a bug

2. **Keystore-manager: add internal auth for nginx services**
   - Nginx can't use JWT (no login). Options:
     a. Internal API key header (already exists: `X-Internal-Key` removed in favor of SPIFFE)
     b. SPIFFE mTLS (ideal but complex for nginx)
     c. Kubernetes service account token (for k8s deployments)
     d. Pre-shared secret env var: `KEYSTORE_FETCH_TOKEN` checked by keystore-manager

3. **Nginx entrypoint.sh (shared across all 4 frontends):**
```bash
#!/bin/sh
KEYSTORE_URL="${KEYSTORE_MANAGER_URL:-http://keystore-manager:8093}"
CERT_ALIAS="${PLATFORM_TLS_ALIAS:-platform-tls}"
MAX_RETRIES=30

# Wait for keystore-manager
echo "Waiting for keystore-manager..."
for i in $(seq 1 $MAX_RETRIES); do
    if curl -sf "$KEYSTORE_URL/health" > /dev/null 2>&1; then
        echo "Keystore-manager ready"
        break
    fi
    sleep 2
done

# Download platform TLS cert
echo "Fetching TLS certificate from keystore-manager..."
curl -sf "$KEYSTORE_URL/api/v1/keys/$CERT_ALIAS/download?format=cert" \
    -H "X-Service-Token: $KEYSTORE_FETCH_TOKEN" \
    -o /etc/nginx/ssl/server.crt

curl -sf "$KEYSTORE_URL/api/v1/keys/$CERT_ALIAS/download?format=private" \
    -H "X-Service-Token: $KEYSTORE_FETCH_TOKEN" \
    -o /etc/nginx/ssl/server.key

chmod 600 /etc/nginx/ssl/server.key

if [ -f /etc/nginx/ssl/server.crt ] && [ -s /etc/nginx/ssl/server.crt ]; then
    echo "TLS cert loaded from keystore-manager ($CERT_ALIAS)"
else
    echo "WARNING: Could not fetch TLS cert, generating self-signed fallback"
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout /etc/nginx/ssl/server.key \
        -out /etc/nginx/ssl/server.crt \
        -subj "/CN=*.filetransfer.local/O=TranzFer MFT" 2>/dev/null
fi

exec nginx -g "daemon off;"
```

4. **Docker-compose: add keystore-manager dependency**
```yaml
ui-service:
  depends_on:
    keystore-manager:
      condition: service_healthy
  environment:
    KEYSTORE_MANAGER_URL: http://keystore-manager:8093
    KEYSTORE_FETCH_TOKEN: ${KEYSTORE_FETCH_TOKEN:-internal-dev-token}
```

5. **Nginx configs: add HTTPS listener**
```nginx
listen 8080;
listen 8443 ssl;
ssl_certificate     /etc/nginx/ssl/server.crt;
ssl_certificate_key /etc/nginx/ssl/server.key;
ssl_protocols       TLSv1.2 TLSv1.3;
```

### Benefits

- **Single source of truth**: ALL services (Java + nginx) get certs from keystore-manager
- **Cert rotation**: rotate once in keystore-manager, restart frontends to pick up new cert
- **No self-signed in production**: mount real certs via `platform.tls.keystore-path` or Vault
- **Graceful fallback**: if keystore-manager is down, nginx falls back to self-signed (dev mode)

---

## Observations During Testing

### Keystore-Manager Bugs Found

1. **Download endpoint returns wrong format**: Both `?format=public` and `?format=private` return the same public key PEM. The `?format=cert` returns an empty response. The download routing logic needs to be fixed to return:
   - `format=cert` → X.509 certificate (from the generate response's embedded cert)
   - `format=private` → RSA private key (from `keyMaterial`)
   - `format=public` → public key (from `publicKeyMaterial`)

2. **Redis connection**: keystore-manager still connects to `localhost:6379` instead of `redis:6379`. This makes its health DOWN, which prevents it from being a reliable dependency for other services.

3. **TLS cert generate works**: `POST /api/v1/keys/generate/tls` with `san` as String (not Array) successfully generates `CN=*.filetransfer.local` cert signed by `CN=TranzFer CA`. HTTP 201.

### 308 Redirect Fix (Correct and Should Stay)

The HTTP→HTTPS redirect in `api-gateway/nginx.conf` uses HTTP 308 (Permanent Redirect) instead of 301. This is the correct approach because:
- 301: allows browser to change POST→GET (breaks login, create, update operations)
- 308: preserves the HTTP method (POST stays POST, body preserved)
- This is per RFC 7538 and is the professional-grade approach for API gateways

### How the System Should Work End-to-End

```
User's browser
    │ https://localhost (port 443)
    ▼
[api-gateway] ← TLS termination + 308 redirect
    │ HTTPS to backend services (9080, 9084, etc.)
    │ HTTPS to nginx frontends (8443)
    ▼
[onboarding-api:9080] ← PlatformTlsConfig auto-TLS
[config-service:9084] ← PlatformTlsConfig auto-TLS
[ui-service:8443]     ← cert from keystore-manager
[partner-portal:8443] ← cert from keystore-manager
```

All traffic encrypted. Single cert authority (keystore-manager). Professional-grade TLS everywhere.
