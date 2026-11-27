package io.graphspindle.api.rest;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata and the API key schemes, so Swagger UI's "Authorize" sends the key (ADR-033). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "GraphSpindle API", version = "v1"),
        security = {@SecurityRequirement(name = "apiKey"), @SecurityRequirement(name = "bearer")})
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = "X-API-Key")
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer")
public class OpenApiConfig {
}
