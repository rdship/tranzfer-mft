package com.filetransfer.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared OpenAPI configuration for all TranzFer services.
 *
 * Each service gets a Swagger UI at /swagger-ui.html
 * and OpenAPI spec at /v3/api-docs.
 *
 * The service name and description are pulled from Spring Boot properties,
 * so each service shows its own info without extra config.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI platformOpenAPI(
            @Value("${spring.application.name:TranzFer Service}") String appName,
            @Value("${springdoc.description:TranzFer MFT Platform API}") String description) {
        return new OpenAPI()
                .info(new Info()
                        .title(appName + " API")
                        .description(description)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("TranzFer MFT")
                                .url("https://github.com/rdship/tranzfer-mft"))
                        .license(new License()
                                .name("Business Source License 1.1")
                                .url("https://github.com/rdship/tranzfer-mft/blob/main/LICENSE")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("internalKey"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT token from /api/auth/login"))
                        .addSecuritySchemes("internalKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Key")
                                .description("Internal service-to-service API key")));
    }
}
