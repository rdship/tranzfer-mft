package com.filetransfer.dmz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Exclude DB auto-config: DMZ proxy has NO database access by design.
// Redis is used ONLY for lightweight group self-registration — no entities, no JPA.
@SpringBootApplication(
    scanBasePackages = {"com.filetransfer.dmz"},
    exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class}
)
@EnableScheduling
public class DmzProxyApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DmzProxyApplication.class); app.setBanner((env, src, out) -> { out.println("TranzFer MFT v" + env.getProperty("platform.version", "UNKNOWN") + " — " + env.getProperty("cluster.service-type", "UNKNOWN") + " [" + env.getProperty("platform.build-timestamp", "UNKNOWN") + "]"); }); app.run(args);
    }
}
