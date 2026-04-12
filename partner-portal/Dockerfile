FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install --silent
COPY . .
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache openssl && mkdir -p /etc/nginx/ssl && chmod 777 /etc/nginx/ssl

# Non-root nginx must listen on an unprivileged port (>=1024). Host port
# 3002 -> container port 8080 is wired in docker-compose.yml.
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /usr/share/nginx/html /var/cache/nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown appuser:appgroup /var/run/nginx.pid

COPY --from=builder --chown=appuser:appgroup /app/dist /usr/share/nginx/html
RUN echo 'server { listen 8080; root /usr/share/nginx/html; index index.html; location / { try_files $uri $uri/ /index.html; } }' > /etc/nginx/conf.d/default.conf && \
    chown appuser:appgroup /etc/nginx/conf.d/default.conf

USER appuser
EXPOSE 8080
COPY --chmod=755 entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
