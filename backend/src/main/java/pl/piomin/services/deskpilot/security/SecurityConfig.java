package pl.piomin.services.deskpilot.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import static pl.piomin.services.deskpilot.security.CurrentUser.ADMIN;
import static pl.piomin.services.deskpilot.security.CurrentUser.REVIEWER;
import static pl.piomin.services.deskpilot.security.CurrentUser.USER;

/**
 * Stateless OAuth2 resource server: every API call carries a JWT issued by Keycloak. The user id is the token's
 * {@code preferred_username}; realm roles ({@code realm_access.roles}) decide who may approve and who may change settings.
 */
@Configuration
public class SecurityConfig {

    static final String ROLE_PREFIX = "ROLE_";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        // Streamed responses (SSE) finish in an async dispatch of a request already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/runs/*/approval", "/api/runs/*/approval/stream").hasRole(REVIEWER)
                        .requestMatchers(HttpMethod.GET, "/api/runs", "/api/feedback").hasAnyRole(REVIEWER, ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/settings").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/settings").hasRole(ADMIN)
                        .requestMatchers("/api/evaluations", "/api/evaluations/**").hasRole(ADMIN)
                        .requestMatchers("/api/knowledge-base/documents", "/api/knowledge-base/documents/**").hasRole(ADMIN)
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        .requestMatchers("/api/**").hasRole(USER)
                        // MCP server (Streamable HTTP): read-only knowledge-base tools for other agents and IDEs.
                        .requestMatchers("/mcp", "/mcp/**").hasRole(USER)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens in a header are not sent automatically by the browser, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("preferred_username");
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoles);
        return converter;
    }

    private static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
