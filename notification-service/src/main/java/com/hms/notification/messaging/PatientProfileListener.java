package com.hms.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.common.events.PatientProfileChanged;
import com.hms.common.events.Topics;
import com.hms.common.messaging.EventHeaders;
import com.hms.notification.domain.Recipient;
import com.hms.notification.domain.RecipientRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent upsert of the contact directory from patient events. */
@Component
public class PatientProfileListener {

  private final RecipientRepository recipients;
  private final ObjectMapper mapper;

  public PatientProfileListener(RecipientRepository recipients, ObjectMapper mapper) {
    this.recipients = recipients;
    this.mapper = mapper;
  }

  @KafkaListener(topics = Topics.PATIENT, groupId = "notification-profile")
  @Transactional
  public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
    if (!"PatientProfileChanged".equals(EventHeaders.eventType(record))) return;
    var e = mapper.readValue(record.value(), PatientProfileChanged.class);
    var r = recipients.findById(e.patientId()).orElseGet(() -> new Recipient(e.patientId()));
    r.setFullName(e.fullName());
    r.setEmail(e.email());
    r.setPhone(e.phone());
    if (e.locale() != null) r.setLocale(e.locale());
    if (e.channels() != null && !e.channels().isEmpty()) r.setChannels(String.join(",", e.channels()));
    r.setPushEndpointArn(e.pushEndpointArn());
    recipients.save(r);
  }
}
