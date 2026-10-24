package com.hms.pharmacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.hms.common", "com.hms.pharmacy"})
@EntityScan(basePackages = {"com.hms.common", "com.hms.pharmacy"})
@EnableJpaRepositories(basePackages = {"com.hms.common", "com.hms.pharmacy"})
public class PharmacyApplication {
  public static void main(String[] args) {
    SpringApplication.run(PharmacyApplication.class, args);
  }
}
