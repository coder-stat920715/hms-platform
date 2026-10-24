package com.hms.appointment.messaging;

import com.hms.appointment.service.SagaOrchestrator;
import com.hms.common.events.Topics;
import com.hms.common.messaging.Deduper;
import com.hms.common.messaging.EventHeaders;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Debezium keys every outbox record by aggregateid, which for saga traffic is the appointment id. */
@Component
public class SagaEventListener {

  private static final String CONSUMER = "appointment-saga";

  private final SagaOrchestrator orchestrator;
  private final Deduper deduper;

  public SagaEventListener(SagaOrchestrator orchestrator, Deduper deduper) {
    this.orchestrator = orchestrator;
    this.deduper = deduper;
  }

  @KafkaListener(topics = Topics.BILLING, groupId = CONSUMER)
  @Transactional
  public void onBilling(ConsumerRecord<String, String> record) {
    if (!deduper.firstTime(record, CONSUMER)) return;
    orchestrator.handleBilling(UUID.fromString(record.key()), EventHeaders.eventType(record), record.value());
  }

  @KafkaListener(topics = Topics.PHARMACY, groupId = CONSUMER)
  @Transactional
  public void onPharmacy(ConsumerRecord<String, String> record) {
    if (!deduper.firstTime(record, CONSUMER)) return;
    orchestrator.handlePharmacy(UUID.fromString(record.key()), EventHeaders.eventType(record), record.value());
  }
}
