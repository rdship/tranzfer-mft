#!/bin/sh
# Auto-generate self-signed TLS cert if none is mounted.
# Production: mount real certs at /etc/nginx/ssl/server.crt and server.key
# Dev/test: auto-generated, valid 365 days

if [ ! -f /etc/nginx/ssl/server.crt ]; then
    echo "Generating self-signed TLS certificate for API gateway..."
    openssl req -x509 -nodes -days 365 \
        -newkey rsa:2048 \
        -keyout /etc/nginx/ssl/server.key \
        -out /etc/nginx/ssl/server.crt \
        -subj "/CN=api-gateway.filetransfer.local/O=TranzFer MFT/C=US" \
        -addext "subjectAltName=DNS:localhost,DNS:api-gateway,IP:127.0.0.1" \
        2>/dev/null
    echo "Self-signed TLS cert generated (CN=api-gateway.filetransfer.local)"
fi

exec nginx -g "daemon off;"
