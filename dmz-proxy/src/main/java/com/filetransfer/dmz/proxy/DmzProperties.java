package com.filetransfer.dmz.proxy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "dmz")
public class DmzProperties {
    private List<PortMapping> mappings;
}
