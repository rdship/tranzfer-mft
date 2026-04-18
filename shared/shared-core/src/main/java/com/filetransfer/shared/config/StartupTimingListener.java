package com.filetransfer.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * R124 Phase 1: surgical boot-time instrumentation — purely additive
 * observability, zero behaviour change.
 *
 * <p>Logs timestamped milestones at INFO during every service's cold boot so
 * the tester's acceptance report can pinpoint which phase is the 120s-mandate
 * hot path per service. Currently the platform boots 1/18 services under
 * 120s (edi-converter only); the other 17 are 165–220s. Without per-phase
 * timing we can't target the fix — the tester's R122 report identified the
 * regression but not the phase.
 *
 * <p>Lines emitted at INFO with prefix {@code [boot-timing]}:
 * <pre>
 *   [boot-timing] T+2145ms  ApplicationPreparedEvent  (context prepared, beans about to load)
 *   [boot-timing] T+8904ms  ContextRefreshedEvent     (bean graph fully loaded)
 *   [boot-timing] T+14_223ms ApplicationStartedEvent  (context started, web server up if any)
 *   [boot-timing] T+17_802ms ApplicationReadyEvent    (fully ready — total cold-boot)
 * </pre>
 *
 * <p>The delta between successive events tells us where time is spent:
 * <ul>
 *   <li>Prepared→Refreshed = bean definition + creation + CGLIB proxy + JPA entity model</li>
 *   <li>Refreshed→Started = web server init (Tomcat), Hibernate SessionFactory warm</li>
 *   <li>Started→Ready = @PostConstruct chains, async init (SPIRE workload API, Kafka admin)</li>
 * </ul>
 *
 * <p>Uses {@code ManagementFactory.getRuntimeMXBean().getUptime()} for the
 * millis-since-JVM-start baseline — same clock as the JVM's own boot log, so
 * numbers line up with GC, class-loading, and Spring startup traces.
 *
 * <p>Phase 1 only captures bean-based events (Prepared onwards). Earlier
 * events (Starting, EnvironmentPrepared) fire before beans exist; if we need
 * those later, a {@code SpringApplicationRunListener} registered via
 * {@code META-INF/spring/...imports} captures them, at the cost of touching
 * every service's classpath. Phase 1 defers that complexity — the ~1-2s
 * environment-prep window isn't where the 200s sink lives.
 *
 * <p>Output of this listener is informational only. Failure here is swallowed
 * silently (logging a timestamp can never fail the boot path). Zero risk to
 * Silver-locked R122 behaviour.
 */
@Slf4j
@Component
public class StartupTimingListener implements ApplicationListener<ApplicationPreparedEvent> {

    private static final String PREFIX = "[boot-timing]";

    /**
     * {@code ApplicationPreparedEvent} fires once, very early — bean factory
     * is populated but beans haven't been instantiated yet. We implement the
     * {@link ApplicationListener} interface directly (rather than using
     * {@code @EventListener}) because Spring only invokes annotation-based
     * listeners after the bean is fully initialised, which is later than the
     * event we care about here.
     */
    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        log.info("{} T+{}ms ApplicationPreparedEvent (context prepared, beans about to load)",
                PREFIX, uptimeMs());
    }

    /**
     * {@code ContextRefreshedEvent} is the gold-standard "bean graph built"
     * marker. For web apps, this fires before the servlet container starts
     * listening; for non-web (db-migrate) apps, this is effectively the end
     * of startup.
     */
    @EventListener
    public void onContextRefreshed(ContextRefreshedEvent event) {
        log.info("{} T+{}ms ContextRefreshedEvent (bean graph fully loaded, {} beans)",
                PREFIX, uptimeMs(),
                event.getApplicationContext().getBeanDefinitionCount());
    }

    /**
     * {@code ApplicationStartedEvent} — context refresh completed; for web
     * apps, Tomcat/Jetty is about to accept connections. Delta from
     * {@code ContextRefreshed} tells us how long the web server
     * initialisation took.
     */
    @EventListener
    public void onApplicationStarted(ApplicationStartedEvent event) {
        log.info("{} T+{}ms ApplicationStartedEvent (context started, server up)",
                PREFIX, uptimeMs());
    }

    /**
     * {@code ApplicationReadyEvent} — last event before the service is
     * considered "fully ready" to serve requests. Delta from
     * {@code ApplicationStarted} captures all {@code @PostConstruct} chains,
     * async init futures (SPIRE workload-API warm, Kafka admin pre-pull),
     * and any other deferred work.
     */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("{} T+{}ms ApplicationReadyEvent (fully ready — total cold-boot)",
                PREFIX, uptimeMs());
    }

    private static long uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
}
