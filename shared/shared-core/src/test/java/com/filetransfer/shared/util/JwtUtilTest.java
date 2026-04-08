package com.filetransfer.shared.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String SECRET = "this_is_a_256bit_secret_key_for_testing_purposes!!";
    private static final long EXPIRATION_MS = 900_000; // 15 min

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, EXPIRATION_MS);
    }

    @Test
    void generateToken_shouldReturnNonEmptyToken() {
        String token = jwtUtil.generateToken("admin", "ADMIN");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void parseToken_shouldReturnCorrectSubjectAndRole() {
        String token = jwtUtil.generateToken("partner1", "PARTNER");

        Claims claims = jwtUtil.parseToken(token);

        assertEquals("partner1", claims.getSubject());
        assertEquals("PARTNER", claims.get("role", String.class));
    }

    @Test
    void isValid_shouldReturnTrueForValidToken() {
        String token = jwtUtil.generateToken("user1", "ADMIN");
        assertTrue(jwtUtil.isValid(token));
    }

    @Test
    void isValid_shouldReturnFalseForTamperedToken() {
        String token = jwtUtil.generateToken("user1", "ADMIN");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertFalse(jwtUtil.isValid(tampered));
    }

    @Test
    void isValid_shouldReturnFalseForGarbageInput() {
        assertFalse(jwtUtil.isValid("not.a.jwt"));
        assertFalse(jwtUtil.isValid(""));
        assertFalse(jwtUtil.isValid(null));
    }

    @Test
    void isValid_shouldReturnFalseForTokenSignedWithDifferentKey() {
        JwtUtil otherUtil = new JwtUtil("another_256bit_secret_key_for_different_signer!!", EXPIRATION_MS);
        String token = otherUtil.generateToken("user1", "ADMIN");
        assertFalse(jwtUtil.isValid(token));
    }

    @Test
    void getSubject_shouldReturnCorrectSubject() {
        String token = jwtUtil.generateToken("testuser", "PARTNER");
        assertEquals("testuser", jwtUtil.getSubject(token));
    }

    @Test
    void getRole_shouldReturnCorrectRole() {
        String token = jwtUtil.generateToken("testuser", "ADMIN");
        assertEquals("ADMIN", jwtUtil.getRole(token));
    }

    @Test
    void expiredToken_shouldBeInvalid() {
        JwtUtil shortLived = new JwtUtil(SECRET, -1000); // already expired
        String token = shortLived.generateToken("user1", "ADMIN");
        assertFalse(jwtUtil.isValid(token));
    }

    @Test
    void generateToken_preservesSpecialCharactersInSubject() {
        String token = jwtUtil.generateToken("user@domain.com", "PARTNER");
        assertEquals("user@domain.com", jwtUtil.getSubject(token));
    }
}
