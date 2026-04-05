package com.filetransfer.keystore.service;

import com.filetransfer.keystore.entity.ManagedKey;
import com.filetransfer.keystore.repository.ManagedKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class KeyManagementService {

    private final ManagedKeyRepository keyRepository;

    @Value("${keystore.master-password:change-this-master-password}")
    private String masterPassword;

    // === Key Generation ===

    public ManagedKey generateSshHostKey(String alias, String ownerService) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair kp = kpg.generateKeyPair();

        String privatePem = encodePem("EC PRIVATE KEY", kp.getPrivate().getEncoded());
        String publicPem = encodePem("PUBLIC KEY", kp.getPublic().getEncoded());
        String fingerprint = sha256Fingerprint(kp.getPublic().getEncoded());

        return save(ManagedKey.builder()
                .alias(alias).keyType("SSH_HOST_KEY").algorithm("EC-P256")
                .keyMaterial(privatePem).publicKeyMaterial(publicPem)
                .fingerprint(fingerprint).ownerService(ownerService)
                .keySizeBits(256).description("SSH host key for " + ownerService)
                .build());
    }

    public ManagedKey generateSshUserKey(String alias, String partnerAccount, int keySize) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(keySize);
        KeyPair kp = kpg.generateKeyPair();

        String privatePem = encodePem("RSA PRIVATE KEY", kp.getPrivate().getEncoded());
        String publicPem = encodePem("PUBLIC KEY", kp.getPublic().getEncoded());

        return save(ManagedKey.builder()
                .alias(alias).keyType("SSH_USER_KEY").algorithm("RSA-" + keySize)
                .keyMaterial(privatePem).publicKeyMaterial(publicPem)
                .fingerprint(sha256Fingerprint(kp.getPublic().getEncoded()))
                .partnerAccount(partnerAccount).keySizeBits(keySize)
                .description("SSH user key for " + partnerAccount)
                .build());
    }

    public ManagedKey generateAesKey(String alias, String ownerService) throws Exception {
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
        kg.init(256);
        byte[] key = kg.generateKey().getEncoded();

        return save(ManagedKey.builder()
                .alias(alias).keyType("AES_SYMMETRIC").algorithm("AES-256")
                .keyMaterial(HexFormat.of().formatHex(key))
                .fingerprint(sha256Fingerprint(key))
                .ownerService(ownerService).keySizeBits(256)
                .description("AES-256 symmetric key")
                .build());
    }

    public ManagedKey generateTlsCertificate(String alias, String cn, int validDays) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Self-signed cert using basic X509
        String privatePem = encodePem("RSA PRIVATE KEY", kp.getPrivate().getEncoded());
        String publicPem = encodePem("PUBLIC KEY", kp.getPublic().getEncoded());

        return save(ManagedKey.builder()
                .alias(alias).keyType("TLS_CERTIFICATE").algorithm("RSA-2048")
                .keyMaterial(privatePem).publicKeyMaterial(publicPem)
                .fingerprint(sha256Fingerprint(kp.getPublic().getEncoded()))
                .subjectDn("CN=" + cn).issuerDn("CN=TranzFer CA")
                .validFrom(Instant.now())
                .expiresAt(Instant.now().plus(validDays, ChronoUnit.DAYS))
                .keySizeBits(2048).description("TLS certificate for " + cn)
                .build());
    }

    public ManagedKey generateHmacKey(String alias, String ownerService) throws Exception {
        byte[] key = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(key);

        return save(ManagedKey.builder()
                .alias(alias).keyType("HMAC_SECRET").algorithm("HmacSHA256")
                .keyMaterial(HexFormat.of().formatHex(key))
                .fingerprint(sha256Fingerprint(key))
                .ownerService(ownerService).keySizeBits(256)
                .description("HMAC-SHA256 secret")
                .build());
    }

    // === PGP Key Generation ===

    public ManagedKey generatePgpKeypair(String alias, String identity, String passphrase) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(4096, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, new Date());

        // Build secret key
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build()
                .get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA1);
        PGPSecretKey secretKey = new PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION, pgpKeyPair, identity, sha1Calc,
                null, null,
                new JcaPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(),
                        org.bouncycastle.bcpg.HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(
                        org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .setProvider("BC").build(passphrase.toCharArray()));

        // Export armored public key
        ByteArrayOutputStream pubOut = new ByteArrayOutputStream();
        try (org.bouncycastle.bcpg.ArmoredOutputStream armoredPub = new org.bouncycastle.bcpg.ArmoredOutputStream(pubOut)) {
            secretKey.getPublicKey().encode(armoredPub);
        }
        String publicArmored = pubOut.toString();

        // Export armored private key
        ByteArrayOutputStream privOut = new ByteArrayOutputStream();
        try (org.bouncycastle.bcpg.ArmoredOutputStream armoredPriv = new org.bouncycastle.bcpg.ArmoredOutputStream(privOut)) {
            secretKey.encode(armoredPriv);
        }
        String privateArmored = privOut.toString();

        String fingerprint = sha256Fingerprint(kp.getPublic().getEncoded());

        return save(ManagedKey.builder()
                .alias(alias).keyType("PGP_KEYPAIR").algorithm("RSA-4096")
                .keyMaterial(privateArmored).publicKeyMaterial(publicArmored)
                .fingerprint(fingerprint).keySizeBits(4096)
                .description("PGP keypair for " + identity)
                .build());
    }

    // === Key Import ===

    public ManagedKey importKey(String alias, String keyType, String material, String description,
                                 String ownerService, String partnerAccount) {
        return save(ManagedKey.builder()
                .alias(alias).keyType(keyType)
                .keyMaterial(material)
                .fingerprint(sha256Fingerprint(material.getBytes()))
                .ownerService(ownerService).partnerAccount(partnerAccount)
                .description(description)
                .build());
    }

    // === Key Retrieval ===

    public Optional<ManagedKey> getKey(String alias) {
        return keyRepository.findByAliasAndActiveTrue(alias);
    }

    public Optional<String> getPublicKey(String alias) {
        return keyRepository.findByAliasAndActiveTrue(alias)
                .map(k -> k.getPublicKeyMaterial() != null ? k.getPublicKeyMaterial() : k.getKeyMaterial());
    }

    public List<ManagedKey> getKeysByType(String keyType) {
        return keyRepository.findByKeyTypeAndActiveTrue(keyType);
    }

    public List<ManagedKey> getKeysByService(String service) {
        return keyRepository.findByOwnerServiceAndActiveTrue(service);
    }

    public List<ManagedKey> getKeysByPartner(String partner) {
        return keyRepository.findByPartnerAccountAndActiveTrue(partner);
    }

    public List<ManagedKey> getAllActiveKeys() {
        return keyRepository.findByActiveTrue();
    }

    // === Key Deactivation ===

    public ManagedKey deactivateKey(String alias) {
        ManagedKey key = keyRepository.findByAliasAndActiveTrue(alias)
                .orElseThrow(() -> new IllegalArgumentException("Key not found: " + alias));
        key.setActive(false);
        keyRepository.save(key);
        log.info("Key deactivated: alias={} type={}", alias, key.getKeyType());
        return key;
    }

    // === Statistics ===

    public Map<String, Object> getKeyStats() {
        List<ManagedKey> all = keyRepository.findByActiveTrue();
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> byService = new LinkedHashMap<>();
        int expiringCount = 0;
        Instant in30Days = Instant.now().plus(30, ChronoUnit.DAYS);

        for (ManagedKey k : all) {
            byType.merge(k.getKeyType(), 1L, Long::sum);
            String owner = k.getOwnerService() != null ? k.getOwnerService() : "unassigned";
            byService.merge(owner, 1L, Long::sum);
            if (k.getExpiresAt() != null && k.getExpiresAt().isBefore(in30Days)) {
                expiringCount++;
            }
        }

        long rotatedCount = keyRepository.findByRotatedToAliasIsNotNullAndActiveFalse().size();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalActive", all.size());
        stats.put("byType", byType);
        stats.put("byService", byService);
        stats.put("expiringSoon", expiringCount);
        stats.put("rotatedKeys", rotatedCount);
        return stats;
    }

    public List<ManagedKey> getExpiringKeys(int withinDays) {
        Instant deadline = Instant.now().plus(withinDays, ChronoUnit.DAYS);
        return keyRepository.findByActiveTrueAndExpiresAtBefore(deadline);
    }

    // === Key Rotation ===

    public ManagedKey rotateKey(String oldAlias, String newAlias) throws Exception {
        ManagedKey oldKey = keyRepository.findByAliasAndActiveTrue(oldAlias)
                .orElseThrow(() -> new IllegalArgumentException("Key not found: " + oldAlias));

        ManagedKey newKey;
        switch (oldKey.getKeyType()) {
            case "AES_SYMMETRIC" -> newKey = generateAesKey(newAlias, oldKey.getOwnerService());
            case "SSH_HOST_KEY" -> newKey = generateSshHostKey(newAlias, oldKey.getOwnerService());
            case "HMAC_SECRET" -> newKey = generateHmacKey(newAlias, oldKey.getOwnerService());
            default -> throw new UnsupportedOperationException("Auto-rotation not supported for: " + oldKey.getKeyType());
        }

        oldKey.setActive(false);
        oldKey.setRotatedToAlias(newAlias);
        keyRepository.save(oldKey);

        log.info("Key rotated: {} -> {}", oldAlias, newAlias);
        return newKey;
    }

    // === Expiry Monitoring ===

    @Scheduled(cron = "0 0 8 * * *") // Daily at 8am
    @SchedulerLock(name = "keystore_checkExpiring", lockAtLeastFor = "PT23H", lockAtMostFor = "PT24H")
    public void checkExpiring() {
        Instant in30Days = Instant.now().plus(30, ChronoUnit.DAYS);
        List<ManagedKey> expiring = keyRepository.findByActiveTrueAndExpiresAtBefore(in30Days);
        for (ManagedKey k : expiring) {
            long daysLeft = ChronoUnit.DAYS.between(Instant.now(), k.getExpiresAt());
            log.warn("Key '{}' ({}) expires in {} days", k.getAlias(), k.getKeyType(), daysLeft);
        }
    }

    // === Helpers ===

    private ManagedKey save(ManagedKey key) {
        if (keyRepository.existsByAlias(key.getAlias())) {
            throw new IllegalArgumentException("Key alias already exists: " + key.getAlias());
        }
        ManagedKey saved = keyRepository.save(key);
        log.info("Key created: alias={} type={} fingerprint={}", saved.getAlias(), saved.getKeyType(),
                saved.getFingerprint() != null ? saved.getFingerprint().substring(0, 16) + "..." : "n/a");
        return saved;
    }

    private String encodePem(String type, byte[] data) {
        return "-----BEGIN " + type + "-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(data) +
                "\n-----END " + type + "-----";
    }

    private String sha256Fingerprint(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { return null; }
    }
}
