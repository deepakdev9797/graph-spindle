package io.graphspindle.security;

import io.graphspindle.common.ErrorCode;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * The one security filter chain for REST, Swagger, actuator and MCP (ADR-033). Stateless, no sessions,
 * no CSRF (no cookies are used), no basic or form login. {@code /actuator/health} is open (without
 * details); the API docs are open on loopback ({@code graphspindle.security.api-docs=AUTO}); everything
 * else needs an API key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    static final String[] API_DOCS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties, StartupSafety safety,
            ApiKeyService keys, RateLimiter limiter, ProblemWriter problems, HostOriginPolicy hostOrigin)
            throws Exception {
        // not a bean: a Filter bean would also be registered as a plain servlet filter outside the chain
        var filter = new ApiKeyAuthenticationFilter(keys, limiter, problems, properties.enabled());
        boolean docsPublic = switch (properties.apiDocs()) {
            case PUBLIC -> true;
            case AUTHENTICATED -> false;
            case AUTO -> safety.loopback();
        };
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .anonymous(a -> a.disable())
                .addFilterBefore(new HostOriginFilter(hostOrigin, problems), AnonymousAuthenticationFilter.class)
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problems.write(res, ErrorCode.UNAUTHENTICATED,
                                "authentication required: send an API key in X-API-Key or Authorization: Bearer",
                                null))
                        .accessDeniedHandler((req, res, ex) -> problems.write(res, ErrorCode.FORBIDDEN,
                                "access denied", null)))
                .authorizeHttpRequests(a -> {
                    // async/error re-dispatches of an already authorized request (INTEGRATION_NOTES pitfall 1)
                    a.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (docsPublic) {
                        a.requestMatchers(API_DOCS).permitAll();
                    }
                    a.anyRequest().authenticated();
                });
        return http.build();
    }
}
