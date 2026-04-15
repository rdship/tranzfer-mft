package com.filetransfer.ftp;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
    basePackages = {"com.filetransfer.ftp", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(compliance|scheduler)\\..*"
    )
)
@EntityScan(basePackages = {
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer",
    "com.filetransfer.shared.entity.vfs"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer",
    "com.filetransfer.shared.repository.vfs"
})
@EnableScheduling
public class FtpServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FtpServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
