package com.hms.appointment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slot")
public class Slot {

  @Id
  private UUID id;

  @Column(name = "doctor_id", nullable = false)
  private UUID doctorId;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SlotStatus status = SlotStatus.FREE;

  @Column(name = "appointment_id")
  private UUID appointmentId;

  @Version
  private Long version;

  protected Slot() {}

  public Slot(UUID doctorId, Instant startTime, Instant endTime) {
    this.id = UUID.randomUUID();
    this.doctorId = doctorId;
    this.startTime = startTime;
    this.endTime = endTime;
  }

  public void reserve(UUID appointment) {
    this.status = SlotStatus.RESERVED;
    this.appointmentId = appointment;
  }

  public UUID getId() { return id; }
  public UUID getDoctorId() { return doctorId; }
  public Instant getStartTime() { return startTime; }
  public Instant getEndTime() { return endTime; }
  public SlotStatus getStatus() { return status; }
  public UUID getAppointmentId() { return appointmentId; }
}
