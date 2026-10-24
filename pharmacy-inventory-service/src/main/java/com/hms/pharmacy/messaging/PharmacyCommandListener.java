package com.hms.pharmacy.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.common.events.AllocateInventory;
import com.hms.common.events.ReleaseInventory;
import com.hms.common.events.Topics;
import com.hms.common.messaging.Deduper;
import com.hms.common.messaging.EventHeaders;
import com.hms.pharmacy.service.AllocationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PharmacyCommandListener {

  private static final String CONSUMER = "pharmacy";

  private final AllocationService service;
  private final Deduper deduper;
  private final ObjectMapper mapper;

  public PharmacyCommandListener(AllocationService service, Deduper deduper, ObjectMapper mapper) {
    this.service = service;
    this.deduper = deduper;
    this.mapper = mapper;
  }

  /** ConcurrentStockChangeException is retried by the shared error handler (then dead-lettered). */
  @KafkaListener(topics = Topics.PHARMACY_COMMANDS, groupId = CONSUMER)
  @Transactional
  public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
    if (!deduper.firstTime(record, CONSUMER)) return;
    switch (EventHeaders.eventType(record)) {
      case "AllocateInventory" -> service.allocate(mapper.readValue(record.value(), AllocateInventory.class));
      case "ReleaseInventory" -> service.release(mapper.readValue(record.value(), ReleaseInventory.class).appointmentId());
      default -> { }
    }
  }
}
