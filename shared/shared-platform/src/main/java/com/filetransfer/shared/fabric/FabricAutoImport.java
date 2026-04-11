package com.filetransfer.shared.fabric;

import com.filetransfer.fabric.config.FabricConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Bridge @Configuration that lives inside the {@code com.filetransfer.shared}
 * component-scan root. Services only scan {@code com.filetransfer.shared} (and
 * their own package), so they don't directly pick up beans defined under
 * {@code com.filetransfer.fabric}. Importing {@link FabricConfig} here ensures
 * the {@link com.filetransfer.fabric.FabricClient} bean and
 * {@link com.filetransfer.fabric.config.FabricProperties} are available to
 * {@link FlowFabricBridge} and {@link FlowFabricConsumer} without every service
 * having to add {@code com.filetransfer.fabric} to its scan list.
 */
@Configuration
@Import(FabricConfig.class)
public class FabricAutoImport {
}
