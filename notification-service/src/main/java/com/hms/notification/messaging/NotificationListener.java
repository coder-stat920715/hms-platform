package com.hms.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.common.events.AppointmentConfirmed;
import com.hms.common.events.AppointmentFailed;
import com.hms.common.events.InvoiceCreated;
import com.hms.common.events.LowStock;
import com.hms.common.events.Topics;
import com.hms.common.messaging.Deduper;
import com.hms.common.messaging.EventHeaders;
import com.hms.notification.service.NotificationRouter;
import com.hms.notification.service.Template;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * If sending throws, the transaction (including the dedupe row) rolls back, so the retry/DLT path
 * re-attempts the notification rather than silently dropping it.
 */
@Component
public class NotificationListener {

  private static final String CONSUMER = "notification";
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

  private final NotificationRouter router;
  private final Deduper deduper;
  private final ObjectMapper mapper;

  public NotificationListener(NotificationRouter router, Deduper deduper, ObjectMapper mapper) {
    this.router = router;
    this.deduper = deduper;
    this.mapper = mapper;
  }

  @KafkaListener(topics = {Topics.APPOINTMENT, Topics.BILLING, Topics.PHARMACY}, groupId = CONSUMER, concurrency = "3")
  @Transactional
  public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
    String type = EventHeaders.eventType(record);
    if (!isRelevant(type)) return;
    if (!deduper.firstTime(record, CONSUMER)) return;

    switch (type) {
      case "AppointmentConfirmed" -> {
        var e = mapper.readValue(record.value(), AppointmentConfirmed.class);
        router.sendToPatient(e.patientId(), Template.APPT_CONFIRMED, WHEN.format(e.slot()));
      }
      case "AppointmentFailed" -> {
        var e = mapper.readValue(record.value(), AppointmentFailed.class);
        router.sendToPatient(e.patientId(), Template.APPT_FAILED);
      }
      case "InvoiceCreated" -> {
        var e = mapper.readValue(record.value(), InvoiceCreated.class);
        router.sendToPatient(e.patientId(), Template.INVOICE_READY);
      }
      case "LowStockDetected" -> {
        var e = mapper.readValue(record.value(), LowStock.class);
        router.sendToOperations(Template.LOW_STOCK, e.name(), e.available());
      }
      default -> { }
    }
  }

  private static boolean isRelevant(String type) {
    return type.equals("AppointmentConfirmed") || type.equals("AppointmentFailed")
        || type.equals("InvoiceCreated") || type.equals("LowStockDetected");
  }
}
