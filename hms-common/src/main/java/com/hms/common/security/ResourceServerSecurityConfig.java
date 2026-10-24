package com.hms.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless OAuth2 resource server; maps Keycloak realm roles / Cognito groups to ROLE_* authorities. */
@Configuration
@EnableMethodSecurity
public class ResourceServerSecurityConfig {

  @Bean
  public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .build();
  }

  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      List<String> names = new ArrayList<>();
      Object realm = jwt.getClaim("realm_access");
      if (realm instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles) {
        roles.forEach(r -> names.add(String.valueOf(r)));
      }
      Object groups = jwt.getClaim("cognito:groups");
      if (groups instanceof Collection<?> g) g.forEach(x -> names.add(String.valueOf(x)));

      Collection<GrantedAuthority> out = new ArrayList<>();
      for (String n : names) out.add(new SimpleGrantedAuthority("ROLE_" + n.toUpperCase()));
      return out;
    });
    return converter;
  }
}
