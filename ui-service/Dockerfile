# Build stage
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install --silent
COPY . .
RUN npm run build

# Runtime stage
FROM nginx:alpine
RUN apk add --no-cache openssl && mkdir -p /etc/nginx/ssl

# Create non-root user and fix permissions for nginx to run as non-root.
# nginx.conf now listens on 8080 (not 80) because non-root users cannot
# bind to ports below 1024 without CAP_NET_BIND_SERVICE, which Docker
# strips when you add a new user with adduser. Host port 3000 -> container
# port 8080 is wired in docker-compose.yml.
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /usr/share/nginx/html /var/cache/nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown appuser:appgroup /var/run/nginx.pid

COPY --from=builder --chown=appuser:appgroup /app/dist /usr/share/nginx/html
COPY --chown=appuser:appgroup nginx.conf /etc/nginx/conf.d/default.conf

USER appuser
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
