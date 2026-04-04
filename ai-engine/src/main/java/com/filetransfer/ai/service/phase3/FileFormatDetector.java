package com.filetransfer.ai.service.phase3;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * AI Phase 3: Auto-detect file formats regardless of extension.
 * Uses magic bytes (file signatures) to identify actual content type.
 */
@Service
@Slf4j
public class FileFormatDetector {

    private static final Map<String, byte[]> SIGNATURES = new LinkedHashMap<>();
    static {
        SIGNATURES.put("GZIP", new byte[]{0x1f, (byte)0x8b});
        SIGNATURES.put("ZIP", new byte[]{0x50, 0x4b, 0x03, 0x04});
        SIGNATURES.put("PDF", new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF
        SIGNATURES.put("PNG", new byte[]{(byte)0x89, 0x50, 0x4e, 0x47});
        SIGNATURES.put("PGP_MESSAGE", "-----BEGIN PGP MESSAGE-----".getBytes());
        SIGNATURES.put("PGP_PUBLIC_KEY", "-----BEGIN PGP PUBLIC KEY-----".getBytes());
        SIGNATURES.put("XML", "<?xml".getBytes());
        SIGNATURES.put("JSON_OBJECT", "{".getBytes());
        SIGNATURES.put("JSON_ARRAY", "[".getBytes());
    }

    public FormatResult detect(Path filePath) {
        try {
            long fileSize = Files.size(filePath);
            byte[] head = new byte[(int) Math.min(fileSize, 8192)];
            try (InputStream is = Files.newInputStream(filePath)) { is.read(head); }

            String filename = filePath.getFileName().toString();
            String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";

            // Magic byte detection
            String detectedFormat = "UNKNOWN";
            for (Map.Entry<String, byte[]> sig : SIGNATURES.entrySet()) {
                if (startsWith(head, sig.getValue())) {
                    detectedFormat = sig.getKey();
                    break;
                }
            }

            // CSV heuristic: check if first line has commas/tabs and looks tabular
            if ("UNKNOWN".equals(detectedFormat)) {
                String firstLines = new String(head, 0, Math.min(head.length, 2048));
                if (firstLines.contains(",") && firstLines.contains("\n")) {
                    String[] lines = firstLines.split("\n", 3);
                    if (lines.length >= 2) {
                        int cols1 = lines[0].split(",").length;
                        int cols2 = lines[1].split(",").length;
                        if (cols1 > 1 && cols1 == cols2) detectedFormat = "CSV";
                    }
                }
                if ("UNKNOWN".equals(detectedFormat) && firstLines.contains("\t") && firstLines.contains("\n")) {
                    detectedFormat = "TSV";
                }
                if ("UNKNOWN".equals(detectedFormat) && isPrintableText(head)) {
                    detectedFormat = "TEXT";
                }
            }

            // Extension mismatch detection
            boolean mismatch = false;
            String mismatchMsg = null;
            if (!extension.isEmpty()) {
                String expectedFormat = extensionToFormat(extension);
                if (expectedFormat != null && !expectedFormat.equals(detectedFormat) && !"UNKNOWN".equals(detectedFormat)) {
                    mismatch = true;
                    mismatchMsg = String.format("Extension says %s but content is %s", expectedFormat, detectedFormat);
                }
            }

            return FormatResult.builder()
                    .filename(filename)
                    .detectedFormat(detectedFormat)
                    .declaredExtension(extension)
                    .fileSizeBytes(fileSize)
                    .extensionMismatch(mismatch)
                    .mismatchMessage(mismatchMsg)
                    .suggestedAction(suggestAction(detectedFormat, extension, mismatch))
                    .build();

        } catch (Exception e) {
            return FormatResult.builder().filename(filePath.getFileName().toString())
                    .detectedFormat("ERROR").mismatchMessage(e.getMessage()).build();
        }
    }

    private String suggestAction(String format, String extension, boolean mismatch) {
        if (mismatch) return "WARNING: File content doesn't match extension. Verify with sender.";
        return switch (format) {
            case "GZIP" -> "Auto-decompress available (DECOMPRESS_GZIP step)";
            case "ZIP" -> "Auto-decompress available (DECOMPRESS_ZIP step)";
            case "PGP_MESSAGE" -> "Auto-decrypt available (DECRYPT_PGP step)";
            case "CSV", "TSV" -> "Content validation available (VALIDATE step)";
            default -> null;
        };
    }

    private String extensionToFormat(String ext) {
        return switch (ext.toLowerCase()) {
            case ".gz", ".gzip" -> "GZIP";
            case ".zip" -> "ZIP";
            case ".csv" -> "CSV";
            case ".tsv" -> "TSV";
            case ".json" -> "JSON_OBJECT";
            case ".xml" -> "XML";
            case ".pgp", ".gpg" -> "PGP_MESSAGE";
            case ".pdf" -> "PDF";
            case ".txt" -> "TEXT";
            default -> null;
        };
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private boolean isPrintableText(byte[] data) {
        for (int i = 0; i < Math.min(data.length, 512); i++) {
            byte b = data[i];
            if (b < 0x09 || (b > 0x0d && b < 0x20 && b != 0x1b)) return false;
        }
        return true;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FormatResult {
        private String filename;
        private String detectedFormat;
        private String declaredExtension;
        private long fileSizeBytes;
        private boolean extensionMismatch;
        private String mismatchMessage;
        private String suggestedAction;
    }
}
