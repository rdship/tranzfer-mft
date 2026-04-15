package com.filetransfer.edi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = {"com.filetransfer.edi", "com.filetransfer.shared"},
        exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class}
)
@EnableScheduling
public class EdiConverterApplication {
    public static void main(String[] args) { SpringApplication app = new SpringApplication(EdiConverterApplication.class); app.setBanner((env, src, out) -> { out.println("TranzFer MFT v" + env.getProperty("platform.version", "UNKNOWN") + " — " + env.getProperty("cluster.service-type", "UNKNOWN") + " [" + env.getProperty("platform.build-timestamp", "UNKNOWN") + "]"); }); app.run(args); }
}
