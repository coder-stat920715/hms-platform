package com.hms.appointment.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.common.events.AppointmentConfirmed;
import com.hms.common.events.AppointmentFailed;
import com.hms.common.events.AppointmentRequested;
import com.hms.common.events.Topics;
import com.hms.common.messaging.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Projects appointment events onto the read store. Idempotent upserts keyed by appointment id, so replays and
 * duplicates are harmless; the whole view can be rebuilt by resetting this consumer group to offset 0.
 */
@Component
public class AppointmentProjector {

  private final AppointmentViewRepository views;
  private final ObjectMapper mapper;

  public AppointmentProjector(AppointmentViewRepository views, ObjectMapper mapper) {
    this.views = views;
    this.mapper = mapper;
  }

  @KafkaListener(topics = Topics.APPOINTMENT, groupId = "appointment-projector")
  public void project(ConsumerRecord<String, String> record) throws JsonProcessingException {
    switch (EventHeaders.eventType(record)) {
      case "AppointmentRequested" -> {
        var e = mapper.readValue(record.value(), AppointmentRequested.class);
        views.save(new AppointmentView(e.appointmentId().toString(), e.patientId().toString(),
            e.doctorId().toString(), e.slot(), "PENDING"));
      }
      case "AppointmentConfirmed" -> {
        var e = mapper.readValue(record.value(), AppointmentConfirmed.class);
        views.findById(e.appointmentId().toString()).ifPresent(v -> {
          v.setStatus("CONFIRMED");
          views.save(v);
        });
      }
      case "AppointmentFailed" -> {
        var e = mapper.readValue(record.value(), AppointmentFailed.class);
        views.findById(e.appointmentId().toString()).ifPresent(v -> {
          v.setStatus("FAILED");
          v.setFailureReason(e.reason());
          views.save(v);
        });
      }
      default -> { }
    }
  }
}
