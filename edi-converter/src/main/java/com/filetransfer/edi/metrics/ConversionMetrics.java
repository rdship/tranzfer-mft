package com.filetransfer.edi.metrics;

import com.filetransfer.edi.map.MapResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * R135a — first observability primitive for the EDI converter.
 *
 * <p>The tester's {@code docs/gap-analysis/edi-converter-maturity-gap-report.md}
 * §8 found zero Micrometer usage in this service: no Counter, no Timer, no
 * Gauge, no DLQ. Without meters, ops has no signal for conversion volume,
 * duration, error rate, or per-message-type distribution.
 *
 * <p>This class defines the three primitives the rest of the R135 series
 * will wire into. Wiring lives in {@code EdiConverterController} for now —
 * fine-grained instrumentation inside {@code MapBasedConverter} /
 * {@code UniversalConverter} / parsers follows in later R135 commits.
 *
 * <h3>Emitted meters</h3>
 * <ul>
 *   <li>{@code edi_conversions_total{result, source_type, target_type, map_category}}
 *       — Counter. One increment per conversion attempt. {@code result} is
 *       {@code success | parse_failed | map_not_found | conversion_failed |
 *       validation_failed}. {@code map_category} is
 *       {@code standard | trained | partner | none}.</li>
 *   <li>{@code edi_conversion_duration_seconds{source_type, target_type, result}}
 *       — Timer. Wall-clock latency around the dispatch.</li>
 *   <li>{@code edi_maps_loaded{category="standard"}} — Gauge. Polls
 *       {@link MapResolver#getStandardMapCount()}.</li>
 * </ul>
 *
 * <p>Partner/trained map counts are not yet gauged — the resolver caches them
 * on-demand so a gauge would lie. A future R135 commit will surface them via
 * a proper {@code cache.size()} snapshot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionMetrics {

    /** Constants so callers don't drift on meter names / dimension values. */
    public static final String METER_CONVERSIONS = "edi_conversions_total";
    public static final String METER_DURATION    = "edi_conversion_duration_seconds";
    public static final String METER_MAPS_LOADED = "edi_maps_loaded";

    public static final String RESULT_SUCCESS           = "success";
    public static final String RESULT_PARSE_FAILED      = "parse_failed";
    public static final String RESULT_MAP_NOT_FOUND     = "map_not_found";
    public static final String RESULT_CONVERSION_FAILED = "conversion_failed";
    public static final String RESULT_VALIDATION_FAILED = "validation_failed";

    public static final String CATEGORY_STANDARD = "standard";
    public static final String CATEGORY_TRAINED  = "trained";
    public static final String CATEGORY_PARTNER  = "partner";
    public static final String CATEGORY_NONE     = "none";

    private final MeterRegistry registry;
    private final MapResolver mapResolver;

    @PostConstruct
    void registerGauges() {
        registry.gauge(METER_MAPS_LOADED,
                io.micrometer.core.instrument.Tags.of("category", CATEGORY_STANDARD),
                mapResolver, r -> r.getStandardMapCount());
        log.info("[R135a][ConversionMetrics] registered — meters={} initial standardMaps={}",
                METER_MAPS_LOADED + "|" + METER_CONVERSIONS + "|" + METER_DURATION,
                mapResolver.getStandardMapCount());
    }

    /**
     * Record a successful conversion. Dimensions: result=success + the
     * observed source/target types + the map category that resolved.
     * Latency in nanoseconds (caller uses {@link System#nanoTime()}).
     */
    public void recordSuccess(String sourceType, String targetType, String mapCategory, long nanos) {
        count(RESULT_SUCCESS, sourceType, targetType, mapCategory);
        time(sourceType, targetType, RESULT_SUCCESS, nanos);
    }

    /**
     * Record a failed conversion. The {@code result} tag identifies the
     * failure class — parse / map-not-found / conversion / validation.
     */
    public void recordFailure(String result, String sourceType, String targetType,
                              String mapCategory, long nanos) {
        count(result, sourceType, targetType, mapCategory);
        time(sourceType, targetType, result, nanos);
    }

    private void count(String result, String sourceType, String targetType, String mapCategory) {
        Counter.builder(METER_CONVERSIONS)
                .description("EDI conversion attempts by outcome, source, target, and resolved map category")
                .tag("result", safe(result))
                .tag("source_type", safe(sourceType))
                .tag("target_type", safe(targetType))
                .tag("map_category", safe(mapCategory))
                .register(registry)
                .increment();
    }

    private void time(String sourceType, String targetType, String result, long nanos) {
        Timer.builder(METER_DURATION)
                .description("Wall-clock EDI conversion duration")
                .tag("source_type", safe(sourceType))
                .tag("target_type", safe(targetType))
                .tag("result", safe(result))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Keep dimension values from becoming the high-cardinality kiss of death. */
    private static String safe(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }
}
