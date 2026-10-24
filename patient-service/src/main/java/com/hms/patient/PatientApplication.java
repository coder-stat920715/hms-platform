package com.hms.patient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.hms.common", "com.hms.patient"})
@EntityScan(basePackages = {"com.hms.common", "com.hms.patient"})
@EnableJpaRepositories(basePackages = {"com.hms.common", "com.hms.patient"})
@ConfigurationPropertiesScan("com.hms.patient")
@EnableCaching
public class PatientApplication {
  public static void main(String[] args) {
    SpringApplication.run(PatientApplication.class, args);
  }
}
