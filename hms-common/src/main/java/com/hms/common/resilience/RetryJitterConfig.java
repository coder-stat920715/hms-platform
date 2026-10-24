package com.hms.common.resilience;

import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalFunction;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Full-jitter exponential backoff: delay = random(0, 500ms * 2^(attempt-1)).
 * Resilience4j properties only offer the "randomized wait factor" variant, so install the function here.
 * A customizer is applied only to instances that exist in application.yml.
 */
@Configuration
public class RetryJitterConfig {

  public static IntervalFunction fullJitter(long initialMs, double multiplier) {
    return attempt -> {
      long cap = (long) (initialMs * Math.pow(multiplier, attempt - 1));
      return ThreadLocalRandom.current().nextLong(0, cap + 1);
    };
  }

  @Bean public RetryConfigCustomizer insuranceRetryJitter() {
    return RetryConfigCustomizer.of("insurance", b -> b.intervalFunction(fullJitter(500, 2.0)));
  }

  @Bean public RetryConfigCustomizer sesRetryJitter() {
    return RetryConfigCustomizer.of("ses", b -> b.intervalFunction(fullJitter(500, 2.0)));
  }

  @Bean public RetryConfigCustomizer snsRetryJitter() {
    return RetryConfigCustomizer.of("sns", b -> b.intervalFunction(fullJitter(500, 2.0)));
  }
}
