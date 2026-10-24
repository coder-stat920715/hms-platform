package com.hms.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Idempotent-consumer ledger. Key = "<consumer>:<messageId>". */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

  @Id
  @Column(name = "dedupe_key", length = 200)
  private String dedupeKey;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt = Instant.now();

  protected ProcessedMessage() {}

  public String getDedupeKey() { return dedupeKey; }
  public Instant getProcessedAt() { return processedAt; }
}
