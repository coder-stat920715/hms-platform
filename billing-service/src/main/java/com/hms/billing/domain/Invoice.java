package com.hms.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoice")
public class Invoice {

  @Id
  private UUID id;

  @Column(name = "appointment_id", nullable = false, unique = true)
  private UUID appointmentId;

  @Column(name = "patient_id", nullable = false)
  private UUID patientId;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private InvoiceStatus status = InvoiceStatus.ISSUED;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Version
  private Long version;

  protected Invoice() {}

  public Invoice(UUID appointmentId, UUID patientId, BigDecimal amount) {
    this.id = UUID.randomUUID();
    this.appointmentId = appointmentId;
    this.patientId = patientId;
    this.amount = amount;
  }

  /** Idempotent: voiding a void invoice is a no-op; a paid invoice cannot be voided here (needs a refund flow). */
  public void voidIt() {
    if (status == InvoiceStatus.ISSUED) status = InvoiceStatus.VOID;
  }

  public boolean markPaid() {
    if (status != InvoiceStatus.ISSUED) return false;
    status = InvoiceStatus.PAID;
    return true;
  }

  public UUID getId() { return id; }
  public UUID getAppointmentId() { return appointmentId; }
  public UUID getPatientId() { return patientId; }
  public BigDecimal getAmount() { return amount; }
  public InvoiceStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
}
