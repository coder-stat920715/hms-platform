package com.hms.common.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/** Call from inside the consumer's transaction so the ledger row and the business effects commit together. */
@Component
public class Deduper {

  private final ProcessedMessageRepository repository;

  public Deduper(ProcessedMessageRepository repository) {
    this.repository = repository;
  }

  public boolean firstTime(ConsumerRecord<?, ?> record, String consumer) {
    return repository.insertIfAbsent(consumer + ":" + EventHeaders.messageId(record)) == 1;
  }
}
