package com.hms.appointment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Orchestration state for one booking. Correlation id == appointment id == Kafka message key. */
@Entity
@Table(name = "appointment_saga")
public class AppointmentSaga {

  @Id
  @Column(name = "appointment_id")
  private UUID appointmentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SagaState state = SagaState.STARTED;

  @Column(name = "invoice_id")
  private UUID invoiceId;

  @Column(name = "needs_pharmacy", nullable = false)
  private boolean needsPharmacy;

  @Column(name = "items_json", nullable = false, columnDefinition = "text")
  private String itemsJson;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  @Version
  private Long version;

  protected AppointmentSaga() {}

  public AppointmentSaga(UUID appointmentId, boolean needsPharmacy, String itemsJson) {
    this.appointmentId = appointmentId;
    this.needsPharmacy = needsPharmacy;
    this.itemsJson = itemsJson;
  }

  @PreUpdate
  void touch() { this.updatedAt = Instant.now(); }

  public UUID getAppointmentId() { return appointmentId; }
  public SagaState getState() { return state; }
  public void setState(SagaState state) { this.state = state; }
  public UUID getInvoiceId() { return invoiceId; }
  public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }
  public boolean isNeedsPharmacy() { return needsPharmacy; }
  public String getItemsJson() { return itemsJson; }
  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
  public Instant getUpdatedAt() { return updatedAt; }
}
