FROM nginx:alpine

# Create non-root user and fix permissions for nginx to run as non-root
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /var/cache/nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown appuser:appgroup /var/run/nginx.pid

RUN rm /etc/nginx/conf.d/default.conf
COPY --chown=appuser:appgroup nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=appuser:appgroup proxy_params /etc/nginx/proxy_params

USER appuser
EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
