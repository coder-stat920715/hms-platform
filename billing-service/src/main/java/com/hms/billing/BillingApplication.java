package com.hms.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.hms.common", "com.hms.billing"})
@EntityScan(basePackages = {"com.hms.common", "com.hms.billing"})
@EnableJpaRepositories(basePackages = {"com.hms.common", "com.hms.billing"})
public class BillingApplication {
  public static void main(String[] args) {
    SpringApplication.run(BillingApplication.class, args);
  }
}
