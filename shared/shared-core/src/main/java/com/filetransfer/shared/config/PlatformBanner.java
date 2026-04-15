package com.filetransfer.shared.config;

import org.springframework.boot.Banner;
import org.springframework.core.env.Environment;

import java.io.PrintStream;

/**
 * Prints the TranzFer MFT version banner as the FIRST line of every service's log.
 *
 * <p>This runs before any bean creation — the version is immediately visible.
 * If you see "UNKNOWN" for version, the Docker image is stale — rebuild.
 *
 * <p>Register in each service's main() or via SpringApplication.setBanner().
 * Auto-registered via spring.factories EnvironmentPostProcessor is not needed —
 * shared-core's auto-configuration imports handle it.
 */
public class PlatformBanner implements Banner {

    @Override
    public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
        String version = environment.getProperty("platform.version", "UNKNOWN");
        String build = environment.getProperty("platform.build-timestamp", "UNKNOWN");
        String service = environment.getProperty("cluster.service-type",
                sourceClass != null ? sourceClass.getSimpleName() : "UNKNOWN");
        String env = environment.getProperty("platform.environment", "DEV");

        out.println("╔══════════════════════════════════════════════════════════╗");
        out.println("║  TranzFer MFT v" + version + " — " + service);
        out.println("║  Built: " + build + "  Env: " + env);
        out.println("╚══════════════════════════════════════════════════════════╝");
    }
}
