package com.filetransfer.keystore.controller;

import com.filetransfer.keystore.entity.ManagedKey;
import com.filetransfer.keystore.service.KeyManagementService;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * REST API for centralized key/certificate management.
 * All other services call this instead of managing keys locally.
 *
 * Designed to be compatible with any external system — standard REST + PEM output.
 */
@RestController @RequestMapping("/api/v1/keys") @RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class KeystoreController {

    private final KeyManagementService keyService;

    // === Retrieve ===

    @GetMapping
    public List<ManagedKey> listKeys(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String partner) {
        if (type != null) return keyService.getKeysByType(type);
        if (service != null) return keyService.getKeysByService(service);
        if (partner != null) return keyService.getKeysByPartner(partner);
        return keyService.getAllActiveKeys();
    }

    @GetMapping("/{alias}")
    public ResponseEntity<ManagedKey> getKey(@PathVariable String alias) {
        return keyService.getKey(alias).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Get only the public key (safe to share externally) */
    @GetMapping("/{alias}/public")
    public ResponseEntity<String> getPublicKey(@PathVariable String alias) {
        return keyService.getPublicKey(alias)
                .map(k -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(k))
                .orElse(ResponseEntity.notFound().build());
    }

    // === Generate ===

    @PostMapping("/generate/ssh-host")
    public ResponseEntity<ManagedKey> generateSshHost(@RequestBody Map<String, String> body) throws Exception {
        return created(keyService.generateSshHostKey(body.get("alias"), body.get("ownerService")));
    }

    @PostMapping("/generate/ssh-user")
    public ResponseEntity<ManagedKey> generateSshUser(@RequestBody Map<String, String> body) throws Exception {
        int keySize = Integer.parseInt(body.getOrDefault("keySize", "4096"));
        return created(keyService.generateSshUserKey(body.get("alias"), body.get("partnerAccount"), keySize));
    }

    @PostMapping("/generate/aes")
    public ResponseEntity<ManagedKey> generateAes(@RequestBody Map<String, String> body) throws Exception {
        return created(keyService.generateAesKey(body.get("alias"), body.get("ownerService")));
    }

    @PostMapping("/generate/tls")
    public ResponseEntity<ManagedKey> generateTls(@RequestBody Map<String, String> body) throws Exception {
        int days = Integer.parseInt(body.getOrDefault("validDays", "365"));
        return created(keyService.generateTlsCertificate(body.get("alias"), body.get("cn"), days));
    }

    @PostMapping("/generate/hmac")
    public ResponseEntity<ManagedKey> generateHmac(@RequestBody Map<String, String> body) throws Exception {
        return created(keyService.generateHmacKey(body.get("alias"), body.get("ownerService")));
    }

    // === Generate PGP ===

    @PostMapping("/generate/pgp")
    public ResponseEntity<ManagedKey> generatePgp(@RequestBody Map<String, String> body) throws Exception {
        String passphrase = body.getOrDefault("passphrase", "");
        return created(keyService.generatePgpKeypair(body.get("alias"), body.get("identity"), passphrase));
    }

    // === Import ===

    @PostMapping("/import")
    public ResponseEntity<ManagedKey> importKey(@RequestBody Map<String, String> body) {
        return created(keyService.importKey(
                body.get("alias"), body.get("keyType"), body.get("keyMaterial"),
                body.get("description"), body.get("ownerService"), body.get("partnerAccount")));
    }

    // === Rotate ===

    @PostMapping("/{alias}/rotate")
    public ResponseEntity<ManagedKey> rotateKey(@PathVariable String alias, @RequestBody Map<String, String> body) throws Exception {
        String newAlias = body.getOrDefault("newAlias", alias + "-" + System.currentTimeMillis());
        return ResponseEntity.ok(keyService.rotateKey(alias, newAlias));
    }

    // === Deactivate ===

    @DeleteMapping("/{alias}")
    public ResponseEntity<ManagedKey> deactivateKey(@PathVariable String alias) {
        return ResponseEntity.ok(keyService.deactivateKey(alias));
    }

    // === Download key material as file ===

    @GetMapping("/{alias}/download")
    public ResponseEntity<byte[]> downloadKey(@PathVariable String alias,
                                               @RequestParam(defaultValue = "public") String part) {
        return keyService.getKey(alias).map(key -> {
            String material;
            String filename;
            if ("private".equals(part) && key.getKeyMaterial() != null) {
                material = key.getKeyMaterial();
                filename = alias + "-private.pem";
            } else if (key.getPublicKeyMaterial() != null) {
                material = key.getPublicKeyMaterial();
                filename = alias + "-public.pem";
            } else {
                material = key.getKeyMaterial();
                filename = alias + ".key";
            }
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(material.getBytes());
        }).orElse(ResponseEntity.notFound().build());
    }

    // === Statistics ===

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return keyService.getKeyStats();
    }

    // === Expiring keys ===

    @GetMapping("/expiring")
    public List<ManagedKey> expiringKeys(@RequestParam(defaultValue = "30") int days) {
        return keyService.getExpiringKeys(days);
    }

    // === Health ===

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<ManagedKey> all = keyService.getAllActiveKeys();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (ManagedKey k : all) byType.merge(k.getKeyType(), 1L, Long::sum);
        return Map.of("status", "UP", "service", "keystore-manager",
                "totalKeys", all.size(), "keysByType", byType);
    }

    @GetMapping("/types")
    public List<Map<String, String>> supportedTypes() {
        return List.of(
                Map.of("type", "SSH_HOST_KEY", "description", "SFTP/SSH server host key"),
                Map.of("type", "SSH_USER_KEY", "description", "SFTP user authentication key"),
                Map.of("type", "PGP_PUBLIC", "description", "PGP public key for encryption"),
                Map.of("type", "PGP_PRIVATE", "description", "PGP private key for decryption"),
                Map.of("type", "PGP_KEYPAIR", "description", "PGP keypair for sign + encrypt"),
                Map.of("type", "AES_SYMMETRIC", "description", "AES-256 symmetric encryption key"),
                Map.of("type", "TLS_CERTIFICATE", "description", "X.509 TLS certificate + private key"),
                Map.of("type", "TLS_KEYSTORE", "description", "Java keystore (JKS/PKCS12)"),
                Map.of("type", "HMAC_SECRET", "description", "HMAC-SHA256 signing secret"),
                Map.of("type", "API_KEY", "description", "Inter-service API key")
        );
    }

    private ResponseEntity<ManagedKey> created(ManagedKey key) {
        return ResponseEntity.status(HttpStatus.CREATED).body(key);
    }
}
