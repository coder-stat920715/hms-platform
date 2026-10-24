package com.hms.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Transactional outbox: the row commits atomically with the business state change. */
@Component
public class OutboxEventListener {

  private final OutboxRepository repository;
  private final ObjectMapper mapper;

  public OutboxEventListener(OutboxRepository repository, ObjectMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void on(DomainEvent event) {
    try {
      repository.save(new OutboxEvent(event.aggregateType(), event.aggregateId(), event.type(),
          mapper.writeValueAsString(event.payload())));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise outbox payload for " + event.type(), e);
    }
  }
}
