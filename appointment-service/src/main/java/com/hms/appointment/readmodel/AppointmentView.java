package com.hms.appointment.readmodel;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Denormalised, query-optimised projection of an appointment (the CQRS read side). */
@Document("appointment_view")
public class AppointmentView {

  @Id
  private String id;
  @Indexed
  private String patientId;
  @Indexed
  private String doctorId;
  private Instant slot;
  @Indexed
  private String status;
  private String failureReason;
  private Instant updatedAt;

  public AppointmentView() {}

  public AppointmentView(String id, String patientId, String doctorId, Instant slot, String status) {
    this.id = id;
    this.patientId = patientId;
    this.doctorId = doctorId;
    this.slot = slot;
    this.status = status;
    this.updatedAt = Instant.now();
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getPatientId() { return patientId; }
  public void setPatientId(String patientId) { this.patientId = patientId; }
  public String getDoctorId() { return doctorId; }
  public void setDoctorId(String doctorId) { this.doctorId = doctorId; }
  public Instant getSlot() { return slot; }
  public void setSlot(Instant slot) { this.slot = slot; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; this.updatedAt = Instant.now(); }
  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
  public Instant getUpdatedAt() { return updatedAt; }
}
