#!/bin/sh
if [ ! -f /etc/nginx/ssl/server.crt ]; then
    echo "Generating self-signed TLS certificate for partner-portal..."
    openssl req -x509 -nodes -days 365 \
        -newkey rsa:2048 \
        -keyout /etc/nginx/ssl/server.key \
        -out /etc/nginx/ssl/server.crt \
        -subj "/CN=partner-portal.filetransfer.local/O=TranzFer MFT/C=US" \
        -addext "subjectAltName=DNS:localhost,DNS:partner-portal,IP:127.0.0.1" \
        2>/dev/null
    echo "Self-signed TLS cert generated (CN=partner-portal.filetransfer.local)"
fi
exec nginx -g "daemon off;"
