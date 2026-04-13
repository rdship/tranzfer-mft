package com.filetransfer.shared.util;

/**
 * Strips HTML/script tags from user input at the API boundary.
 * React escapes on render, but raw DB content is consumed by
 * email templates, PDF exports, and non-React clients.
 */
public final class InputSanitizer {
    private InputSanitizer() {}

    private static final java.util.regex.Pattern HTML_TAG =
        java.util.regex.Pattern.compile("<[^>]*>");

    public static String stripHtml(String input) {
        if (input == null) return null;
        return HTML_TAG.matcher(input).replaceAll("").trim();
    }
}
