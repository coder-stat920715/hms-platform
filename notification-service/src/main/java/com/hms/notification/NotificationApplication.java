package com.hms.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.hms.common", "com.hms.notification"})
@EntityScan(basePackages = {"com.hms.common", "com.hms.notification.domain"})
@EnableJpaRepositories(basePackages = {"com.hms.common", "com.hms.notification.domain"})
@ConfigurationPropertiesScan("com.hms.notification")
public class NotificationApplication {
  public static void main(String[] args) {
    SpringApplication.run(NotificationApplication.class, args);
  }
}
