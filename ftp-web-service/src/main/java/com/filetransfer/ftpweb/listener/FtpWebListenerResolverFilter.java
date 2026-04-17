package com.filetransfer.ftpweb.listener;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link FtpWebListenerContext} from the incoming HTTP request.
 * Must run BEFORE {@code JwtAuthFilter} so credential resolution sees a
 * populated listener id.
 *
 * <p>Ordering: {@code HIGHEST_PRECEDENCE + 10} places it ahead of Spring
 * Security's filter chain (~100) and JwtAuthFilter without colliding
 * with Spring's own framework filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class FtpWebListenerResolverFilter extends OncePerRequestFilter {

    private final FtpWebListenerContext listenerContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        listenerContext.initFromRequest(request);
        filterChain.doFilter(request, response);
    }
}
