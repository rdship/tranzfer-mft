FROM nginx:alpine

# nginx.conf listens on 8080 (not 80) because non-root users cannot
# bind to privileged ports. Docker-compose maps host :80 -> container :8080.
# TLS termination happens upstream (ingress controller / load balancer
# in production, docker-compose port map in dev).
RUN apk add --no-cache openssl && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /var/cache/nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown appuser:appgroup /var/run/nginx.pid && \
    mkdir -p /etc/nginx/ssl && chown appuser:appgroup /etc/nginx/ssl

RUN rm /etc/nginx/conf.d/default.conf
COPY --chown=appuser:appgroup nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=appuser:appgroup proxy_params /etc/nginx/proxy_params
COPY --chown=appuser:appgroup entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

USER appuser
EXPOSE 8080 8443
ENTRYPOINT ["/entrypoint.sh"]
