#!/bin/sh
# TLS cert for the API gateway HTTPS listener.
# Priority: 1) Operator-mounted  2) Shared platform cert from Vault  3) Self-generated

mkdir -p /etc/nginx/ssl

if [ -f /etc/nginx/ssl/server.crt ]; then
    echo "Using operator-mounted TLS certificate"
elif [ -f /tls/platform-tls.crt ] && [ -f /tls/platform-tls.key ]; then
    echo "Using shared platform TLS certificate from Vault"
    cp /tls/platform-tls.crt /etc/nginx/ssl/server.crt
    cp /tls/platform-tls.key /etc/nginx/ssl/server.key
else
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
