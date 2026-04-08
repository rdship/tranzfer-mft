package com.filetransfer.shared.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates 12-character tracking IDs for file transfers.
 * Format: [3-char prefix][9-char alphanumeric]
 * Example: TRZ-A1B2C3D4E
 *
 * The 3-char prefix is configurable via platform.track-id.prefix
 */
@Component
public class TrackIdGenerator {

    private static final String ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${platform.track-id.prefix:TRZ}")
    private String prefix;

    public String generate() {
        String pfx = prefix.length() >= 3 ? prefix.substring(0, 3).toUpperCase() : padRight(prefix.toUpperCase(), 3);
        StringBuilder sb = new StringBuilder(pfx);
        for (int i = 0; i < 9; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    private String padRight(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append('X');
        return sb.toString();
    }
}
