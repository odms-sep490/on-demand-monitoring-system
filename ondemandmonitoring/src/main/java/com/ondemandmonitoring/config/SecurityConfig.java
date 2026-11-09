package com.ondemandmonitoring.config;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import lombok.extern.slf4j.Slf4j;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class SecurityConfig {

    private static final Set<String> BUSINESS_ROLE_GROUPS = Set.of(
            RoleCode.CUSTOMER.name(),
            RoleCode.MANAGER.name(),
            RoleCode.STAFF.name(),
            RoleCode.ADMIN.name());

    @Value("${cors.address:http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174}")
    private String allowedOrigins;

    static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/verify-otp",
            "/api/auth/resend-otp",
            "/api/auth/login",
            "/api/auth/first-login/change-password",
            "/api/auth/social/sync",
            "/api/auth/refresh",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/csrf",
            "/api/auth/logout",
            "/api/payments/vnpay/ipn",
            "/api/payments/vnpay/return",
            "/api/v1/auth/register",
            "/api/v1/auth/verify-otp",
            "/api/v1/auth/resend-otp",
            "/api/v1/auth/login",
            "/api/v1/auth/first-login/change-password",
            "/api/v1/auth/social/sync",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/csrf",
            "/api/v1/auth/logout",
            "/v3/api-docs/**",
            "/ws",
            "/ws/**",
            "/api/dev/ai/knowledge/index",
            "/api/planning/environment",
            "/api/planning/environment/**",
            "/api/geocoding/search",
            "/api/geocoding/reverse",
            "/api/internal/v1/drone-telemetry/*",
            "/swagger-ui/**",
            "/error",
            "//mcp",
            "/.well-known/**",
            "/swagger-ui.html"
    };

    @Bean
    RequestMatcher cookieAuthenticationCsrfMatcher() {
        var paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher cookieEndpoints = new OrRequestMatcher(
                paths.matcher("/api/auth/refresh"), paths.matcher("/api/auth/logout"),
                paths.matcher("/api/v1/auth/refresh"), paths.matcher("/api/v1/auth/logout"));
        // Business APIs authenticate only with Bearer tokens, never with cookies.
        return request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                && cookieEndpoints.matches(request);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("cognitoAccessTokenDecoder") JwtDecoder cognitoAccessTokenDecoder,
            ActiveAccountFilter activeAccountFilter) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .requireCsrfProtectionMatcher(cookieAuthenticationCsrfMatcher()))

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.disable())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                        "img-src 'self' data: blob: https://tile.openstreetmap.org https://*.tile.openstreetmap.org https://server.arcgisonline.com; " +
                                        "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdnjs.cloudflare.com; " +
                                        "style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com; " +
                                        "font-src 'self' data:; " +
                                        "connect-src 'self' http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:*; " +
                                        "frame-ancestors 'self';")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/simulation-viewer/simulation_map_top.png").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .jwt(jwt -> jwt
                                .decoder(cognitoAccessTokenDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterAfter(activeAccountFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> {
            log.warn(
                    "Authentication failed. method={}, path={}, reason={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    exception.getMessage()
            );
            response.sendError(401);
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Operator-Id", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<String> groups = jwt.getClaimAsStringList("cognito:groups");
            if (groups == null) {
                return List.of();
            }
            return groups.stream()
                    .filter(BUSINESS_ROLE_GROUPS::contains)
                    .map(group -> new SimpleGrantedAuthority("ROLE_" + group))
                    .map(authority -> (GrantedAuthority) authority)
                    .toList();
        });
        return converter;
    }
}
