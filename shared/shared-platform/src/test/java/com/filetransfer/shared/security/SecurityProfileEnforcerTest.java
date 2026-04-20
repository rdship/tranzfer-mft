package com.filetransfer.shared.security;

import com.filetransfer.shared.entity.core.SecurityProfile;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.repository.core.SecurityProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the precedence rules the R134n enforcer depends on:
 *   per-listener CSV  > SecurityProfile row  > platform static default.
 *
 * <p>Uses a stub subclass of {@link SecurityProfileRepository} because JDK 25
 * can't Byte-Buddy mock the JpaRepository interface (platform-wide policy in
 * {@code project_jdk25_testing.md}). The stub is seeded per-test.
 */
class SecurityProfileEnforcerTest {

    private StubRepo repo;
    private SecurityProfileEnforcer enforcer;

    @BeforeEach
    void setUp() {
        repo = new StubRepo();
        enforcer = new SecurityProfileEnforcer(repo);
    }

    // ── Layer 1: per-listener CSV wins ──────────────────────────────────────

    @Test
    void resolveSshCiphers_perListenerCsv_winsOverProfileAndDefault() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("SSH-strict").active(true)
                .type("SSH")
                .sshCiphers(List.of("aes256-gcm@openssh.com"))
                .build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("sftp-1")
                .securityProfileId(profileId)
                .allowedCiphers("aes128-ctr,aes256-ctr") // per-listener CSV
                .build();

        Set<String> resolved = enforcer.resolveSshCiphers(si);

        assertEquals(Set.of("aes128-ctr", "aes256-ctr"), resolved,
                "per-listener CSV must beat the profile row");
    }

    // ── Layer 2: SecurityProfile row wins when per-listener CSV is blank ────

    @Test
    void resolveSshMacs_profileRow_winsOverPlatformDefault() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("SSH-etm-only").active(true)
                .type("SSH")
                .sshMacs(List.of("hmac-sha2-512-etm@openssh.com"))
                .build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("sftp-1")
                .securityProfileId(profileId)
                .allowedMacs(null) // no per-listener override
                .build();

        Set<String> resolved = enforcer.resolveSshMacs(si);

        assertEquals(Set.of("hmac-sha2-512-etm@openssh.com"), resolved);
        assertNotEquals(SecurityProfile.ALLOWED_SSH_MACS, resolved,
                "profile row must override the platform static default");
    }

    // ── Layer 3: platform static default when profile + CSV both blank ─────

    @Test
    void resolveSshKex_platformDefault_whenNoProfileAndNoCsv() {
        ServerInstance si = ServerInstance.builder()
                .instanceId("sftp-1")
                .securityProfileId(null)
                .allowedKex(null)
                .build();

        Set<String> resolved = enforcer.resolveSshKex(si);

        assertEquals(SecurityProfile.ALLOWED_SSH_KEX, resolved);
    }

    @Test
    void resolveSshKex_platformDefault_whenProfileIsEmpty() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("SSH-empty").active(true)
                .type("SSH")
                .kexAlgorithms(List.of()) // explicitly empty, not populated
                .build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("sftp-1")
                .securityProfileId(profileId)
                .build();

        Set<String> resolved = enforcer.resolveSshKex(si);

        assertEquals(SecurityProfile.ALLOWED_SSH_KEX, resolved,
                "empty profile list must fall through to platform default");
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    void resolveSshCiphers_missingProfileRow_warnsAndFallsBack() {
        UUID fakeId = UUID.randomUUID(); // not stored in repo
        ServerInstance si = ServerInstance.builder()
                .instanceId("sftp-1")
                .securityProfileId(fakeId)
                .build();

        Set<String> resolved = enforcer.resolveSshCiphers(si);

        assertEquals(SecurityProfile.ALLOWED_SSH_CIPHERS, resolved,
                "dangling security_profile_id must fall through to platform default");
    }

    @Test
    void resolveSshCiphers_inactiveProfile_fallsThrough() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("SSH-disabled").active(false)
                .type("SSH")
                .sshCiphers(List.of("some-disabled-cipher"))
                .build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("sftp-1")
                .securityProfileId(profileId)
                .build();

        Set<String> resolved = enforcer.resolveSshCiphers(si);

        assertEquals(SecurityProfile.ALLOWED_SSH_CIPHERS, resolved,
                "inactive profile must not be enforced");
    }

    @Test
    void resolveClientAuthRequired_perListenerTrue_overridesProfileFalse() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("TLS-soft").active(true)
                .type("TLS").clientAuthRequired(false).build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("https-1")
                .securityProfileId(profileId)
                .httpsClientCertRequired(true) // per-listener opt-in to mTLS
                .build();

        assertTrue(enforcer.resolveClientAuthRequired(si));
    }

    @Test
    void resolveClientAuthRequired_profileTrue_winsWhenListenerFalse() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("TLS-mtls").active(true)
                .type("TLS").clientAuthRequired(true).build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("https-1")
                .securityProfileId(profileId)
                .httpsClientCertRequired(false) // cannot downgrade
                .build();

        assertTrue(enforcer.resolveClientAuthRequired(si),
                "profile-level mTLS-required cannot be downgraded by per-listener false");
    }

    @Test
    void resolveTlsMinVersion_defaultsTo12_whenProfileMissing() {
        ServerInstance si = ServerInstance.builder().instanceId("x").build();
        assertEquals("TLSv1.2", enforcer.resolveTlsMinVersion(si));
    }

    @Test
    void resolveTlsMinVersion_usesProfile_whenSet() {
        UUID profileId = UUID.randomUUID();
        repo.store(SecurityProfile.builder().id(profileId).name("TLS13").active(true)
                .type("TLS").tlsMinVersion("TLSv1.3").build());

        ServerInstance si = ServerInstance.builder()
                .instanceId("x").securityProfileId(profileId).build();

        assertEquals("TLSv1.3", enforcer.resolveTlsMinVersion(si));
    }

    // ── Stub repo (JDK 25 can't Byte-Buddy the JpaRepository interface) ────

    private static final class StubRepo implements SecurityProfileRepository {
        private final java.util.Map<UUID, SecurityProfile> byId = new java.util.HashMap<>();
        void store(SecurityProfile p) { byId.put(p.getId(), p); }
        @Override public Optional<SecurityProfile> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public List<SecurityProfile> findByActiveTrue() {
            return byId.values().stream().filter(SecurityProfile::isActive).toList();
        }
        @Override public Optional<SecurityProfile> findByNameAndActiveTrue(String name) {
            return byId.values().stream()
                    .filter(p -> name.equals(p.getName()) && p.isActive()).findFirst();
        }
        @Override public boolean existsByName(String name) {
            return byId.values().stream().anyMatch(p -> name.equals(p.getName()));
        }
        // ── unused JpaRepository surface (stubbed out for JDK 25) ──────────
        @Override public List<SecurityProfile> findAll() { return new java.util.ArrayList<>(byId.values()); }
        @Override public List<SecurityProfile> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<SecurityProfile> findAll(org.springframework.data.domain.Pageable pageable) {
            return new org.springframework.data.domain.PageImpl<>(findAll());
        }
        @Override public List<SecurityProfile> findAllById(Iterable<UUID> ids) {
            return java.util.stream.StreamSupport.stream(ids.spliterator(), false)
                    .map(byId::get).filter(java.util.Objects::nonNull).toList();
        }
        @Override public long count() { return byId.size(); }
        @Override public void deleteById(UUID id) { byId.remove(id); }
        @Override public void delete(SecurityProfile entity) { byId.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) { ids.forEach(byId::remove); }
        @Override public void deleteAll(Iterable<? extends SecurityProfile> entities) { entities.forEach(this::delete); }
        @Override public void deleteAll() { byId.clear(); }
        @Override public <S extends SecurityProfile> S save(S entity) {
            if (entity.getId() == null) entity.setId(UUID.randomUUID());
            byId.put(entity.getId(), entity);
            return entity;
        }
        @Override public <S extends SecurityProfile> List<S> saveAll(Iterable<S> entities) {
            java.util.List<S> out = new java.util.ArrayList<>();
            entities.forEach(e -> out.add(save(e)));
            return out;
        }
        @Override public boolean existsById(UUID id) { return byId.containsKey(id); }
        @Override public void flush() {}
        @Override public <S extends SecurityProfile> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends SecurityProfile> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
        @Override public void deleteAllInBatch(Iterable<SecurityProfile> entities) { entities.forEach(this::delete); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { ids.forEach(byId::remove); }
        @Override public void deleteAllInBatch() { byId.clear(); }
        @Override public SecurityProfile getOne(UUID id) { return byId.get(id); }
        @Override public SecurityProfile getById(UUID id) { return byId.get(id); }
        @Override public SecurityProfile getReferenceById(UUID id) { return byId.get(id); }
        @Override public <S extends SecurityProfile> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends SecurityProfile> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends SecurityProfile> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends SecurityProfile> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends SecurityProfile> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends SecurityProfile> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends SecurityProfile, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }
}
