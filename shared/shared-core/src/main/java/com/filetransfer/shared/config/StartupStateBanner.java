package com.filetransfer.shared.config;

import com.filetransfer.shared.spiffe.SpiffeProperties;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * R122: one-screen startup banner logged at INFO after Spring context refresh
 * finishes. Every Java service prints a line that captures the key identity +
 * feature-flag state for that run. Compresses future debug loops from multiple
 * releases to one — the banner puts the most diagnostic-relevant config into
 * the first ~50 lines of every log.
 *
 * <p><b>Observed pain it addresses</b>: R111→R116 SPIFFE arc took 6 releases to
 * diagnose because on each cold boot the tester had to grep through hundreds
 * of logs to find SPIFFE state per service. R118 self-identity was wrong
 * because {@code spring.application.name} was unset, which a banner line
 * would have flagged on the first look.
 *
 * <p>The banner is intentionally boring text — not ASCII art. Targets
 * grep-ability. Six lines, always the same format, so any future diagnostic
 * script can parse it with a single awk.
 *
 * <p>Disabled with {@code platform.banner.enabled=false}. Default on; zero
 * runtime cost once emitted.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "platform.banner.enabled", havingValue = "true", matchIfMissing = true)
public class StartupStateBanner {

    @Value("${spring.application.name:UNKNOWN}")
    private String appName;

    @Value("${spring.aot.enabled:false}")
    private boolean aotEnabled;

    @Value("${flow.rules.enabled:false}")
    private boolean flowRulesEnabled;

    @Value("${platform.connectors.enabled:false}")
    private boolean connectorsEnabled;

    @Value("${platform.permissions.enabled:true}")
    private boolean permissionsEnabled;

    @Autowired(required = false)
    @Nullable
    private SpiffeWorkloadClient spiffeClient;

    @Autowired(required = false)
    @Nullable
    private SpiffeProperties spiffeProps;

    @EventListener(ApplicationReadyEvent.class)
    public void logBanner() {
        String spiffeSelf = "disabled";
        String spiffeAvail = "n/a";
        if (spiffeProps != null && spiffeProps.isEnabled()) {
            spiffeSelf = spiffeProps.selfSpiffeId();
            spiffeAvail = (spiffeClient != null && spiffeClient.isAvailable()) ? "connected" : "waiting";
        }

        log.info("═══════════════════════════════════════════════════════════════════");
        log.info("  service           : {}", appName);
        log.info("  spring.aot.enabled: {}", aotEnabled);
        log.info("  spiffe.identity   : {}  (workload api: {})", spiffeSelf, spiffeAvail);
        log.info("  flow.rules        : {}", flowRulesEnabled);
        log.info("  connectors        : {}", connectorsEnabled);
        log.info("  permissions.aspect: {}", permissionsEnabled);
        log.info("═══════════════════════════════════════════════════════════════════");
    }
}
