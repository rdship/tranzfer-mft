package com.filetransfer.ai.repository.intelligence;

import com.filetransfer.ai.entity.intelligence.IndicatorType;
import com.filetransfer.ai.entity.intelligence.ThreatIndicator;
import com.filetransfer.ai.entity.intelligence.ThreatLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link ThreatIndicator} entities.
 *
 * <p>Provides standard CRUD operations plus specialised queries for
 * threat-intelligence workflows: lookup by type+value, filtering by
 * threat level, staleness detection, and aggregate counts.</p>
 */
@Repository
public interface ThreatIndicatorRepository extends JpaRepository<ThreatIndicator, UUID> {

    /**
     * Find a single indicator by its type and observable value.
     * The combination (type, value) is unique, so this returns at most one result.
     *
     * @param type  the indicator type (IP, DOMAIN, HASH_SHA256, etc.)
     * @param value the observable value
     * @return the matching indicator, if present
     */
    Optional<ThreatIndicator> findByTypeAndValue(IndicatorType type, String value);

    /**
     * Find all indicators of a given type.
     *
     * @param type the indicator type
     * @return list of matching indicators
     */
    List<ThreatIndicator> findByType(IndicatorType type);

    /**
     * Find indicators whose threat level is one of the supplied values.
     *
     * @param levels the threat levels to include
     * @return list of matching indicators
     */
    List<ThreatIndicator> findByThreatLevelIn(List<ThreatLevel> levels);

    /**
     * Find indicators that have been sighted after the given timestamp.
     *
     * @param since the cutoff timestamp
     * @return list of recently active indicators
     */
    List<ThreatIndicator> findByLastSeenAfter(Instant since);

    /**
     * Count indicators by type (for dashboard summaries).
     *
     * @param type the indicator type
     * @return the count
     */
    long countByType(IndicatorType type);

    /**
     * Count indicators by threat level (for dashboard summaries).
     *
     * @param level the threat level
     * @return the count
     */
    long countByThreatLevel(ThreatLevel level);

    /**
     * Find stale indicators that have not been seen since the given cutoff.
     * Used by the eviction/maintenance process.
     *
     * @param cutoff the staleness cutoff timestamp
     * @return list of stale indicators
     */
    @Query("SELECT t FROM ThreatIndicator t WHERE t.lastSeen < :cutoff")
    List<ThreatIndicator> findStale(@Param("cutoff") Instant cutoff);

    /**
     * Find the top N indicators ordered by sighting count descending.
     * Useful for "most seen" dashboards.
     *
     * @param type  the indicator type to filter (or null for all)
     * @param limit maximum number of results
     * @return list of most-sighted indicators
     */
    @Query("SELECT t FROM ThreatIndicator t WHERE (:type IS NULL OR t.type = :type) ORDER BY t.sightings DESC")
    List<ThreatIndicator> findTopBySightings(@Param("type") IndicatorType type,
                                              org.springframework.data.domain.Pageable limit);

    /**
     * Find indicators whose sources column contains the given source name.
     *
     * @param source the source feed name (partial match)
     * @return list of matching indicators
     */
    @Query("SELECT t FROM ThreatIndicator t WHERE t.sources LIKE %:source%")
    List<ThreatIndicator> findBySource(@Param("source") String source);

    /**
     * Find indicators associated with a given MITRE technique ID.
     *
     * @param techniqueId the MITRE technique ID (e.g., T1566)
     * @return list of matching indicators
     */
    @Query("SELECT t FROM ThreatIndicator t WHERE t.mitreTechniques LIKE %:techniqueId%")
    List<ThreatIndicator> findByMitreTechnique(@Param("techniqueId") String techniqueId);
}
