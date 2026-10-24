package com.hms.appointment.service;

import com.hms.appointment.domain.Appointment;
import com.hms.appointment.domain.AppointmentRepository;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.AppointmentRequested;
import com.hms.common.events.DrugItem;
import com.hms.common.events.Topics;
import com.hms.common.outbox.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentService {

  public record BookCommand(UUID patientId, UUID doctorId, Instant slotStart, BigDecimal fee, List<DrugItem> items) {}

  private final AppointmentRepository appointments;
  private final SagaOrchestrator saga;
  private final ApplicationEventPublisher events;

  public AppointmentService(AppointmentRepository appointments, SagaOrchestrator saga,
                            ApplicationEventPublisher events) {
    this.appointments = appointments;
    this.saga = saga;
    this.events = events;
  }

  /** One local transaction: appointment row + saga row + slot reservation + outbox rows (event and command). */
  @Transactional
  public Appointment book(BookCommand c) {
    var a = new Appointment(UUID.randomUUID(), c.patientId(), c.doctorId(), c.slotStart(), c.fee());
    appointments.save(a);
    events.publishEvent(new DomainEvent(Topics.AGG_APPOINTMENT, a.getId().toString(), "AppointmentRequested",
        new AppointmentRequested(a.getId(), a.getPatientId(), a.getDoctorId(), a.getSlotStart(), a.getFee(),
            c.items() == null ? List.of() : c.items())));
    saga.start(a, c.items());
    return a;
  }

  @Transactional(readOnly = true)
  public Appointment get(UUID id) {
    return appointments.findById(id).orElseThrow(() -> new NotFoundException("Appointment not found"));
  }
}
