package com.filetransfer.ai;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ai", "com.filetransfer.shared"})
@EntityScan(basePackages = {
    "com.filetransfer.ai.entity",
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer",
    "com.filetransfer.shared.entity.integration",
    "com.filetransfer.shared.entity.security",
    "com.filetransfer.shared.entity.vfs"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.ai.repository",
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer",
    "com.filetransfer.shared.repository.integration",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.shared.repository.vfs"
})
@EnableScheduling
// R102: proxyTargetClass=true forces CGLIB subclass proxies for @Async beans
// regardless of declared interfaces. Without this, AOT's build-time proxy
// generator falls back to JDK dynamic proxy when a bean has no interfaces
// (e.g. @Configuration AgentRegistrar with @Async @EventListener
// registerAllAgents) — and the JDK proxy can't dispatch to methods that
// only exist on the concrete class, crashing context refresh the moment
// Spring tries to wire the @EventListener. Reflection-mode didn't hit
// this because Spring picks CGLIB opportunistically at runtime; AOT picks
// statically at build time and needs this hint. Tester flagged it in the
// R97 first-cold-boot acceptance report.
@EnableAsync(proxyTargetClass = true)
public class AiEngineApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AiEngineApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
