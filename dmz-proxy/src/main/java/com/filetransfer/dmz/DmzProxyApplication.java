package com.filetransfer.dmz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

// Exclude DB auto-config: DMZ proxy has NO database access by design
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
public class DmzProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(DmzProxyApplication.class, args);
    }
}
