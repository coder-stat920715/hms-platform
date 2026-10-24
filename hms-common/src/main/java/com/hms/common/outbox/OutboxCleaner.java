package com.hms.common.outbox;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Debezium reads the WAL, not the table, so rows are only kept briefly for debugging.
 * Deleting is idempotent, so every pod can run this.
 */
@Component
public class OutboxCleaner {

  private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);
  private final OutboxRepository repository;
  private final Duration retention;

  public OutboxCleaner(OutboxRepository repository,
                       @Value("${hms.outbox.retention:PT24H}") Duration retention) {
    this.repository = repository;
    this.retention = retention;
  }

  @Scheduled(fixedDelayString = "${hms.outbox.cleanup-interval-ms:600000}")
  public void clean() {
    int n = repository.deleteOlderThan(Instant.now().minus(retention));
    if (n > 0) log.debug("Removed {} old outbox rows", n);
  }
}
