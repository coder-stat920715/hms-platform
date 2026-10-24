package com.hms.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/** Retries with exponential backoff, then publishes to "<topic>.DLT". Create DLT topics when auto-create is off. */
@Configuration
public class KafkaErrorConfig {

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
    var backOff = new ExponentialBackOff(500L, 2.0);
    backOff.setMaxInterval(10_000L);
    backOff.setMaxElapsedTime(30_000L);
    var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
    return handler;
  }
}
