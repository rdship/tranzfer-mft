package com.filetransfer.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects a correlation ID into every request for distributed tracing.
 *
 * Flow:
 * 1. Check for incoming X-Correlation-ID header (from upstream service)
 * 2. If absent, generate a new UUID
 * 3. Put it into MDC so all log statements include it automatically
 * 4. Set it as a response header for the caller to correlate
 * 5. Clean up MDC after request completes
 *
 * Combined with logback pattern: %d{ISO8601} [%X{correlationId}] [%X{service}] %-5level %logger{36} - %msg%n
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String TRACK_ID_HEADER = "X-Track-ID";
    public static final String CORRELATION_ID_MDC = "correlationId";
    public static final String TRACK_ID_MDC = "trackId";
    public static final String SERVICE_NAME_MDC = "service";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString().substring(0, 8);
            }

            MDC.put(CORRELATION_ID_MDC, correlationId);
            MDC.put(SERVICE_NAME_MDC, resolveServiceName());
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            // Propagate trackId from upstream (file transfer tracking across services)
            String trackId = request.getHeader(TRACK_ID_HEADER);
            if (trackId != null && !trackId.isBlank()) {
                MDC.put(TRACK_ID_MDC, trackId);
                response.setHeader(TRACK_ID_HEADER, trackId);
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String resolveServiceName() {
        String name = System.getenv("SERVICE_NAME");
        if (name != null && !name.isBlank()) return name;
        return System.getProperty("spring.application.name", "unknown");
    }
}
