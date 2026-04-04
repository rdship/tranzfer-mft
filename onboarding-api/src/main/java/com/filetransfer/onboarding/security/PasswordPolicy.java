package com.filetransfer.onboarding.security;

import org.springframework.stereotype.Component;

/**
 * PCI DSS 8.2 password requirements:
 * - Minimum 8 characters (PCI requires 7+, we enforce 8+)
 * - At least 1 uppercase, 1 lowercase, 1 digit, 1 special char
 * - Cannot be the same as username/email
 */
@Component
public class PasswordPolicy {

    public void validate(String password, String email) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("Password must contain at least one digit");
        }
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\",./<>?].*")) {
            throw new IllegalArgumentException("Password must contain at least one special character");
        }
        if (email != null && password.toLowerCase().contains(email.split("@")[0].toLowerCase())) {
            throw new IllegalArgumentException("Password cannot contain your username");
        }
    }
}
