FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install --silent
COPY . .
RUN npm run build

FROM nginx:alpine

# Create non-root user and fix permissions for nginx to run as non-root
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /usr/share/nginx/html /var/cache/nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown appuser:appgroup /var/run/nginx.pid

COPY --from=builder --chown=appuser:appgroup /app/dist /usr/share/nginx/html
RUN echo 'server { listen 80; location / { try_files $uri /index.html; } }' > /etc/nginx/conf.d/default.conf && \
    chown appuser:appgroup /etc/nginx/conf.d/default.conf

USER appuser
EXPOSE 80
