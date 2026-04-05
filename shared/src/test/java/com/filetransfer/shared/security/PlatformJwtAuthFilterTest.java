package com.filetransfer.shared.security;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformJwtAuthFilterTest {

    private static final String SECRET = "this_is_a_256bit_secret_key_for_testing_purposes!!";
    private static final String CONTROL_API_KEY = "test_internal_key";

    private JwtUtil jwtUtil;
    private PlatformConfig platformConfig;
    private PlatformJwtAuthFilter filter;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 900_000);

        platformConfig = new PlatformConfig();
        platformConfig.getSecurity().setControlApiKey(CONTROL_API_KEY);

        filter = new PlatformJwtAuthFilter(jwtUtil, platformConfig);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);

        SecurityContextHolder.clearContext();
    }

    @Test
    void validJwtToken_setsAuthenticationWithCorrectRoleAndSubject() throws Exception {
        String token = jwtUtil.generateToken("admin@test.com", "ADMIN");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("X-Internal-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("admin@test.com", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void validInternalKey_setsInternalServiceAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Internal-Key")).thenReturn(CONTROL_API_KEY);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("internal-service", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void invalidJwtToken_fallsThrough_noAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token.here");
        when(request.getHeader("X-Internal-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void wrongInternalKey_noAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Internal-Key")).thenReturn("wrong_key");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void noHeaders_noAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Internal-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void jwtTakesPrecedenceOverInternalKey() throws Exception {
        String token = jwtUtil.generateToken("partner@co.com", "PARTNER");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("X-Internal-Key")).thenReturn(CONTROL_API_KEY);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("partner@co.com", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PARTNER")));
    }

    @Test
    void expiredJwtToken_fallsThrough() throws Exception {
        JwtUtil expiredUtil = new JwtUtil(SECRET, -1000);
        String token = expiredUtil.generateToken("user@test.com", "ADMIN");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("X-Internal-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void bearerPrefixRequired_rawTokenIgnored() throws Exception {
        String token = jwtUtil.generateToken("user@test.com", "ADMIN");
        when(request.getHeader("Authorization")).thenReturn(token); // no "Bearer " prefix
        when(request.getHeader("X-Internal-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
    }

    @Test
    void emptyInternalKey_noAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Internal-Key")).thenReturn("");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
    }

    @Test
    void filterAlwaysContinuesChain() throws Exception {
        // Even with no auth, filter should call doFilter
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-Internal-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
    }
}
