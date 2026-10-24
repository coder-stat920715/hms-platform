package com.hms.common.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CommonConfig {

  @Bean
  @ConditionalOnMissingBean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
