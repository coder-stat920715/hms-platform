package com.hms.appointment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.appointment.domain.Appointment;
import com.hms.appointment.domain.AppointmentRepository;
import com.hms.appointment.domain.AppointmentSaga;
import com.hms.appointment.domain.AppointmentSagaRepository;
import com.hms.appointment.domain.AppointmentStatus;
import com.hms.appointment.domain.SagaState;
import com.hms.appointment.domain.SlotRepository;
import com.hms.common.events.AllocateInventory;
import com.hms.common.events.AppointmentConfirmed;
import com.hms.common.events.AppointmentFailed;
import com.hms.common.events.CreateInvoice;
import com.hms.common.events.DrugItem;
import com.hms.common.events.InvoiceCreated;
import com.hms.common.events.InvoiceFailed;
import com.hms.common.events.ReleaseInventory;
import com.hms.common.events.Topics;
import com.hms.common.events.VoidInvoice;
import com.hms.common.outbox.DomainEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrated saga: reserve slot -> create invoice (billing) -> allocate inventory (pharmacy, if prescribed)
 * -> confirm. Any failure compensates in reverse order: void the invoice, release the slot.
 * Commands and events leave through the transactional outbox, so state and messages never diverge.
 * Every method must run inside the caller's transaction (Propagation.MANDATORY).
 */
@Service
public class SagaOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

  private final AppointmentSagaRepository sagas;
  private final AppointmentRepository appointments;
  private final SlotRepository slots;
  private final ApplicationEventPublisher events;
  private final ObjectMapper mapper;

  public SagaOrchestrator(AppointmentSagaRepository sagas, AppointmentRepository appointments,
                          SlotRepository slots, ApplicationEventPublisher events, ObjectMapper mapper) {
    this.sagas = sagas;
    this.appointments = appointments;
    this.slots = slots;
    this.events = events;
    this.mapper = mapper;
  }

  // ------------------------------------------------------------------ start
  @Transactional(propagation = Propagation.MANDATORY)
  public void start(Appointment a, List<DrugItem> items) {
    List<DrugItem> safeItems = items == null ? List.of() : items;
    var saga = new AppointmentSaga(a.getId(), !safeItems.isEmpty(), write(safeItems));

    var slot = slots.lockFree(a.getDoctorId(), a.getSlotStart()).orElse(null);
    if (slot == null) {
      saga.setState(SagaState.FAILED);
      saga.setFailureReason("slot unavailable");
      sagas.save(saga);
      failAppointment(a, "slot unavailable");
      return;
    }
    slot.reserve(a.getId());
    saga.setState(SagaState.SLOT_RESERVED);
    sagas.save(saga);
    command(Topics.AGG_BILLING_COMMANDS, a.getId(), "CreateInvoice",
        new CreateInvoice(a.getId(), a.getPatientId(), a.getFee()));
  }

  // ------------------------------------------------------------------ billing replies
  @Transactional(propagation = Propagation.MANDATORY)
  public void handleBilling(UUID appointmentId, String eventType, String payload) {
    var saga = sagas.findById(appointmentId).orElse(null);
    if (saga == null) return;

    switch (eventType) {
      case "InvoiceCreated" -> {
        var ev = read(payload, InvoiceCreated.class);
        if (saga.getState() == SagaState.FAILED) {
          // Late reply after timeout/compensation: the invoice must not be left orphaned.
          command(Topics.AGG_BILLING_COMMANDS, appointmentId, "VoidInvoice", new VoidInvoice(ev.invoiceId()));
          return;
        }
        if (saga.getState() != SagaState.SLOT_RESERVED) return;      // duplicate or out of order
        saga.setInvoiceId(ev.invoiceId());
        if (saga.isNeedsPharmacy()) {
          saga.setState(SagaState.INVOICE_CREATED);
          command(Topics.AGG_PHARMACY_COMMANDS, appointmentId, "AllocateInventory",
              new AllocateInventory(appointmentId, items(saga)));
        } else {
          confirm(saga);
        }
      }
      case "InvoiceFailed" -> {
        if (saga.getState() != SagaState.SLOT_RESERVED) return;
        compensate(saga, read(payload, InvoiceFailed.class).reason());
      }
      default -> { /* InvoicePaid etc.: not relevant to the saga */ }
    }
  }

  // ------------------------------------------------------------------ pharmacy replies
  @Transactional(propagation = Propagation.MANDATORY)
  public void handlePharmacy(UUID appointmentId, String eventType, String payload) {
    var saga = sagas.findById(appointmentId).orElse(null);
    if (saga == null) return;

    switch (eventType) {
      case "InventoryAllocated" -> {
        if (saga.getState() == SagaState.FAILED) {
          command(Topics.AGG_PHARMACY_COMMANDS, appointmentId, "ReleaseInventory",
              new ReleaseInventory(appointmentId));                   // late allocation after failure
          return;
        }
        if (saga.getState() == SagaState.INVOICE_CREATED) confirm(saga);
      }
      case "AllocationFailed" -> {
        if (saga.getState() != SagaState.INVOICE_CREATED) return;
        compensate(saga, "inventory unavailable");
      }
      default -> { /* LowStockDetected etc. */ }
    }
  }

  // ------------------------------------------------------------------ timeout (called by the sweeper)
  @Transactional(propagation = Propagation.MANDATORY)
  public void timeout(AppointmentSaga saga) {
    log.warn("Saga {} timed out in state {}", saga.getAppointmentId(), saga.getState());
    if (saga.getState() == SagaState.INVOICE_CREATED) {
      command(Topics.AGG_PHARMACY_COMMANDS, saga.getAppointmentId(), "ReleaseInventory",
          new ReleaseInventory(saga.getAppointmentId()));             // allocation may have happened, reply lost
    }
    compensate(saga, "timed out");
  }

  // ------------------------------------------------------------------ transitions
  private void confirm(AppointmentSaga saga) {
    saga.setState(SagaState.CONFIRMED);
    Appointment a = appointments.findById(saga.getAppointmentId()).orElseThrow();
    a.setStatus(AppointmentStatus.CONFIRMED);
    events.publishEvent(new DomainEvent(Topics.AGG_APPOINTMENT, a.getId().toString(), "AppointmentConfirmed",
        new AppointmentConfirmed(a.getId(), a.getPatientId(), a.getDoctorId(), a.getSlotStart())));
  }

  /** Reverse order of completed steps: void invoice (if any), release slot, fail the appointment. */
  private void compensate(AppointmentSaga saga, String reason) {
    if (saga.getInvoiceId() != null) {
      command(Topics.AGG_BILLING_COMMANDS, saga.getAppointmentId(), "VoidInvoice",
          new VoidInvoice(saga.getInvoiceId()));
    }
    slots.release(saga.getAppointmentId());
    saga.setState(SagaState.FAILED);
    saga.setFailureReason(reason);
    appointments.findById(saga.getAppointmentId()).ifPresent(a -> failAppointment(a, reason));
  }

  private void failAppointment(Appointment a, String reason) {
    a.setStatus(AppointmentStatus.FAILED);
    a.setFailureReason(reason);
    events.publishEvent(new DomainEvent(Topics.AGG_APPOINTMENT, a.getId().toString(), "AppointmentFailed",
        new AppointmentFailed(a.getId(), a.getPatientId(), reason)));
  }

  /** Commands travel through the same outbox; the aggregate type routes them to the target service's topic. */
  private void command(String aggregateType, UUID appointmentId, String type, Object payload) {
    events.publishEvent(new DomainEvent(aggregateType, appointmentId.toString(), type, payload));
  }

  // ------------------------------------------------------------------ json helpers
  private String write(Object o) {
    try {
      return mapper.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Bad " + type.getSimpleName() + " payload", e);   // not retried -> DLT
    }
  }

  private List<DrugItem> items(AppointmentSaga saga) {
    try {
      return mapper.readValue(saga.getItemsJson(), new TypeReference<List<DrugItem>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
