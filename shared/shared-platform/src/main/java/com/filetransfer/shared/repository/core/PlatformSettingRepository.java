package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.PlatformSetting;
import com.filetransfer.shared.enums.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, UUID> {

    List<PlatformSetting> findByEnvironmentAndActiveTrue(Environment environment);

    List<PlatformSetting> findByEnvironmentAndServiceNameAndActiveTrue(Environment environment, String serviceName);

    List<PlatformSetting> findByEnvironment(Environment environment);

    List<PlatformSetting> findByServiceName(String serviceName);

    List<PlatformSetting> findByCategory(String category);

    Optional<PlatformSetting> findBySettingKeyAndEnvironmentAndServiceName(
            String settingKey, Environment environment, String serviceName);

    /**
     * Load all settings for a service in a given environment.
     * Returns both GLOBAL and service-specific settings.
     * Service-specific settings should take precedence (handled in code).
     */
    @Query("SELECT s FROM PlatformSetting s WHERE s.environment = :env " +
           "AND (s.serviceName = 'GLOBAL' OR s.serviceName = :service) " +
           "AND s.active = true ORDER BY s.serviceName DESC")
    List<PlatformSetting> findForServiceInEnvironment(
            @Param("env") Environment env, @Param("service") String service);

    /** All distinct environments that have at least one setting */
    @Query("SELECT DISTINCT s.environment FROM PlatformSetting s ORDER BY s.environment")
    List<Environment> findDistinctEnvironments();

    /** All distinct service names */
    @Query("SELECT DISTINCT s.serviceName FROM PlatformSetting s ORDER BY s.serviceName")
    List<String> findDistinctServiceNames();

    /** All distinct categories */
    @Query("SELECT DISTINCT s.category FROM PlatformSetting s WHERE s.category IS NOT NULL ORDER BY s.category")
    List<String> findDistinctCategories();

    boolean existsBySettingKeyAndEnvironmentAndServiceName(
            String settingKey, Environment environment, String serviceName);
}
