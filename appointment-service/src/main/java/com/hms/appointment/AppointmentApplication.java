package com.hms.appointment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication(scanBasePackages = {"com.hms.common", "com.hms.appointment"})
@EntityScan(basePackages = {"com.hms.common", "com.hms.appointment.domain"})
@EnableJpaRepositories(basePackages = {"com.hms.common", "com.hms.appointment.domain"})
@EnableMongoRepositories(basePackages = "com.hms.appointment.readmodel")
public class AppointmentApplication {
  public static void main(String[] args) {
    SpringApplication.run(AppointmentApplication.class, args);
  }
}
