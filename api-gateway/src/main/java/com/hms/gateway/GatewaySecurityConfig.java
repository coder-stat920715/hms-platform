package com.hms.gateway;

import java.util.Objects;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The gateway is an OAuth2 resource server: it validates the JWT signature (JWKS) and expiry, then forwards the
 * Authorization header unchanged so every downstream service re-validates it (zero trust).
 * Default security headers (HSTS, nosniff, frame DENY, no-cache) are left on.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

  @Bean
  public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(a -> a
            .pathMatchers("/actuator/health/**", "/actuator/prometheus", "/fallback/**").permitAll()
            .anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> { }))
        .build();
  }

  /** Token-bucket key: the authenticated subject, falling back to the client address. */
  @Bean
  public KeyResolver userKeyResolver() {
    return exchange -> exchange.getPrincipal()
        .map(p -> p.getName())
        .switchIfEmpty(Mono.fromSupplier(() -> {
          var addr = exchange.getRequest().getRemoteAddress();
          return addr == null ? "anonymous" : Objects.requireNonNull(addr.getAddress()).getHostAddress();
        }));
  }
}
