package com.hms.appointment.domain;

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
@Table(name = "appointment")
public class Appointment {

  @Id
  private UUID id;

  @Column(name = "patient_id", nullable = false)
  private UUID patientId;

  @Column(name = "doctor_id", nullable = false)
  private UUID doctorId;

  @Column(name = "slot_start", nullable = false)
  private Instant slotStart;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal fee;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AppointmentStatus status = AppointmentStatus.PENDING;

  @Column(name = "failure_reason")
  private String failureReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Version
  private Long version;

  protected Appointment() {}

  public Appointment(UUID id, UUID patientId, UUID doctorId, Instant slotStart, BigDecimal fee) {
    this.id = id;
    this.patientId = patientId;
    this.doctorId = doctorId;
    this.slotStart = slotStart;
    this.fee = fee;
  }

  public UUID getId() { return id; }
  public UUID getPatientId() { return patientId; }
  public UUID getDoctorId() { return doctorId; }
  public Instant getSlotStart() { return slotStart; }
  public BigDecimal getFee() { return fee; }
  public AppointmentStatus getStatus() { return status; }
  public void setStatus(AppointmentStatus status) { this.status = status; }
  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
  public Instant getCreatedAt() { return createdAt; }
}
