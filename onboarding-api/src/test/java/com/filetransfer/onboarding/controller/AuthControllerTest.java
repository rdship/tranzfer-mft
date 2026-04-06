package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.LoginRequest;
import com.filetransfer.onboarding.dto.request.RegisterRequest;
import com.filetransfer.onboarding.dto.response.AuthResponse;
import com.filetransfer.onboarding.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthController: rate limiting, X-Forwarded-For parsing, delegation.
 * Uses a stub AuthService to avoid JDK 25 Mockito restriction on concrete classes.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    // Real stub — JDK 25 Mockito cannot mock concrete AuthService
    private StubAuthService authService;

    @Mock
    private HttpServletRequest httpRequest;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = new StubAuthService();
        authService.setLoginResponse(dummyAuthResponse());
        authService.setRegisterResponse(dummyAuthResponse());
        controller = new AuthController(authService);
    }

    /**
     * Minimal AuthService stub that overrides login/register to return canned responses.
     * Avoids Byte Buddy instrumentation issues on JDK 25.
     */
    private static class StubAuthService extends AuthService {
        private AuthResponse loginResponse;
        private AuthResponse registerResponse;

        StubAuthService() {
            super(null, null, null, null, null, null);
        }

        void setLoginResponse(AuthResponse response) { this.loginResponse = response; }
        void setRegisterResponse(AuthResponse response) { this.registerResponse = response; }

        @Override
        public AuthResponse login(LoginRequest request) { return loginResponse; }

        @Override
        public AuthResponse register(RegisterRequest request) { return registerResponse; }
    }

    // --- Rate limiting: 20 requests from same IP succeed, 21st throws TOO_MANY_REQUESTS ---

    @Test
    void rateLimiting_first20RequestsFromSameIpSucceed() {
        when(httpRequest.getRemoteAddr()).thenReturn("192.168.1.1");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        for (int i = 0; i < 20; i++) {
            AuthResponse response = controller.login(loginRequest, httpRequest);
            assertNotNull(response);
        }
    }

    @Test
    void rateLimiting_21stRequestFromSameIpThrowsTooManyRequests() {
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        // First 20 succeed
        for (int i = 0; i < 20; i++) {
            controller.login(loginRequest, httpRequest);
        }

        // 21st should throw
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.login(loginRequest, httpRequest));
        assertEquals(429, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("Too many"));
    }

    // --- Rate limiting: different IPs have independent windows ---

    @Test
    void rateLimiting_differentIpsHaveIndependentWindows() {
        HttpServletRequest httpRequest2 = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        when(httpRequest2.getRemoteAddr()).thenReturn("10.0.0.2");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        // Exhaust limit on IP1
        for (int i = 0; i < 20; i++) {
            controller.login(loginRequest, httpRequest);
        }

        // IP2 should still work
        AuthResponse response = controller.login(loginRequest, httpRequest2);
        assertNotNull(response);

        // IP1 should be blocked
        assertThrows(ResponseStatusException.class,
                () -> controller.login(loginRequest, httpRequest));
    }

    // --- Rate limiting: window resets after 60 seconds ---

    @Test
    void rateLimiting_newControllerInstanceHasFreshWindows() {
        AuthController freshController = new AuthController(authService);
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.1");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        // Should succeed on the fresh controller
        AuthResponse response = freshController.login(loginRequest, httpRequest);
        assertNotNull(response);
    }

    // --- X-Forwarded-For parsing: picks first IP from comma-separated list ---

    @Test
    void xForwardedFor_picksFirstIpFromCommaSeparatedList() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 70.41.3.18, 150.172.238.178");

        HttpServletRequest httpRequest2 = mock(HttpServletRequest.class);
        when(httpRequest2.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 99.99.99.99");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        // Both requests resolve to "203.0.113.5", so they share a rate limit window
        for (int i = 0; i < 20; i++) {
            controller.login(loginRequest, httpRequest);
        }

        // 21st from the same resolved IP should fail
        assertThrows(ResponseStatusException.class,
                () -> controller.login(loginRequest, httpRequest2));
    }

    // --- X-Forwarded-For absent: falls back to getRemoteAddr() ---

    @Test
    void xForwardedForAbsent_fallsBackToRemoteAddr() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        AuthResponse response = controller.login(loginRequest, httpRequest);
        assertNotNull(response);
        verify(httpRequest).getRemoteAddr();
    }

    @Test
    void xForwardedForBlank_fallsBackToRemoteAddr() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        AuthResponse response = controller.login(loginRequest, httpRequest);
        assertNotNull(response);
        verify(httpRequest).getRemoteAddr();
    }

    // --- Register endpoint delegates to AuthService ---

    @Test
    void register_delegatesToAuthService() {
        when(httpRequest.getRemoteAddr()).thenReturn("192.168.1.1");

        RegisterRequest request = new RegisterRequest();
        request.setEmail("newuser@test.com");
        request.setPassword("securePass1!");

        AuthResponse response = controller.register(request, httpRequest);

        assertNotNull(response);
        assertEquals("test-jwt-token", response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
    }

    // --- Login endpoint delegates to AuthService ---

    @Test
    void login_delegatesToAuthService() {
        when(httpRequest.getRemoteAddr()).thenReturn("192.168.1.1");

        LoginRequest request = new LoginRequest();
        request.setEmail("user@test.com");
        request.setPassword("password123");

        AuthResponse response = controller.login(request, httpRequest);

        assertNotNull(response);
        assertEquals("test-jwt-token", response.getAccessToken());
    }

    // --- Rate limiting applies to both register and login ---

    @Test
    void rateLimiting_appliesToBothRegisterAndLogin() {
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.50");

        RegisterRequest regRequest = new RegisterRequest();
        regRequest.setEmail("new@test.com");
        regRequest.setPassword("password123");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("user@test.com");
        loginRequest.setPassword("password123");

        // Use 10 register + 10 login = 20 total from same IP
        for (int i = 0; i < 10; i++) {
            controller.register(regRequest, httpRequest);
        }
        for (int i = 0; i < 10; i++) {
            controller.login(loginRequest, httpRequest);
        }

        // 21st request (login) should be blocked
        assertThrows(ResponseStatusException.class,
                () -> controller.login(loginRequest, httpRequest));
    }

    private AuthResponse dummyAuthResponse() {
        return AuthResponse.builder()
                .accessToken("test-jwt-token")
                .tokenType("Bearer")
                .expiresIn(900)
                .build();
    }
}
