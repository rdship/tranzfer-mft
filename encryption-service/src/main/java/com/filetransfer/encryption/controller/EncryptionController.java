package com.filetransfer.encryption.controller;

import com.filetransfer.encryption.service.AesService;
import com.filetransfer.encryption.service.PgpService;
import com.filetransfer.shared.entity.EncryptionKey;
import com.filetransfer.shared.enums.EncryptionAlgorithm;
import com.filetransfer.shared.repository.EncryptionKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

import java.util.Base64;
import java.util.UUID;

/**
 * Encryption API
 *
 * POST /api/encrypt?keyId={id}         — encrypt uploaded file, returns encrypted bytes
 * POST /api/decrypt?keyId={id}         — decrypt uploaded file, returns plain bytes
 * POST /api/encrypt/base64?keyId={id}  — encrypt Base64 payload, return Base64
 * POST /api/decrypt/base64?keyId={id}  — decrypt Base64 payload, return Base64
 */
@Slf4j
@RestController
@RequestMapping("/api/encrypt")
@RequiredArgsConstructor
public class EncryptionController {

    private final PgpService pgpService;
    private final AesService aesService;
    private final EncryptionKeyRepository keyRepository;

    @PostMapping(value = "/encrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> encryptFile(@RequestParam UUID keyId,
                                               @RequestPart("file") MultipartFile file) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] result = performEncrypt(file.getBytes(), key);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + file.getOriginalFilename() + ".enc\"")
                .body(result);
    }

    @PostMapping(value = "/decrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> decryptFile(@RequestParam UUID keyId,
                                               @RequestPart("file") MultipartFile file) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] result = performDecrypt(file.getBytes(), key);
        String filename = file.getOriginalFilename();
        if (filename != null && filename.endsWith(".enc")) filename = filename.substring(0, filename.length() - 4);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(result);
    }

    @PostMapping("/encrypt/base64")
    public String encryptBase64(@RequestParam UUID keyId, @RequestBody String base64Input) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] plain = Base64.getDecoder().decode(base64Input);
        byte[] encrypted = performEncrypt(plain, key);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    @PostMapping("/decrypt/base64")
    public String decryptBase64(@RequestParam UUID keyId, @RequestBody String base64Input) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] cipher = Base64.getDecoder().decode(base64Input);
        byte[] plain = performDecrypt(cipher, key);
        return Base64.getEncoder().encodeToString(plain);
    }

    private byte[] performEncrypt(byte[] data, EncryptionKey key) throws Exception {
        if (key.getAlgorithm() == EncryptionAlgorithm.PGP) {
            if (key.getPublicKey() == null) throw new IllegalArgumentException("No public key for PGP encryption");
            return pgpService.encrypt(data, key.getPublicKey());
        } else {
            if (key.getEncryptedSymmetricKey() == null) throw new IllegalArgumentException("No symmetric key for AES encryption");
            // NOTE: In production, unwrap the symmetric key from the master key first
            return aesService.encrypt(data, key.getEncryptedSymmetricKey());
        }
    }

    private byte[] performDecrypt(byte[] data, EncryptionKey key) throws Exception {
        if (key.getAlgorithm() == EncryptionAlgorithm.PGP) {
            if (key.getEncryptedPrivateKey() == null) throw new IllegalArgumentException("No private key for PGP decryption");
            // passphrase would come from a secure vault in production
            return pgpService.decrypt(data, key.getEncryptedPrivateKey(), new char[0]);
        } else {
            if (key.getEncryptedSymmetricKey() == null) throw new IllegalArgumentException("No symmetric key for AES decryption");
            return aesService.decrypt(data, key.getEncryptedSymmetricKey());
        }
    }

    private EncryptionKey findKey(UUID keyId) {
        return keyRepository.findByIdAndActiveTrue(keyId)
                .orElseThrow(() -> new IllegalArgumentException("Encryption key not found or inactive: " + keyId));
    }
}
