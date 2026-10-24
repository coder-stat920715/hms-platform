package com.hms.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Thin facade next to the identity provider (Keycloak / Cognito): the IdP issues tokens; this service records
 * break-glass declarations as audit events. It does not mint tokens.
 */
@SpringBootApplication(scanBasePackages = {"com.hms.common", "com.hms.iam"})
@EntityScan(basePackages = {"com.hms.common"})
@EnableJpaRepositories(basePackages = {"com.hms.common"})
public class IamApplication {
  public static void main(String[] args) {
    SpringApplication.run(IamApplication.class, args);
  }
}
