package com.hms.appointment.api;

import com.hms.appointment.domain.Appointment;
import com.hms.appointment.domain.AppointmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AppointmentDto(UUID id, UUID patientId, UUID doctorId, Instant slotStart, BigDecimal fee,
                             AppointmentStatus status, String failureReason) {

  public static AppointmentDto from(Appointment a) {
    return new AppointmentDto(a.getId(), a.getPatientId(), a.getDoctorId(), a.getSlotStart(), a.getFee(),
        a.getStatus(), a.getFailureReason());
  }
}
