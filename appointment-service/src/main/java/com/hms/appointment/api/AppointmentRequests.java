package com.hms.appointment.api;

import com.hms.common.events.DrugItem;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AppointmentRequests {
  private AppointmentRequests() {}

  public record BookAppointment(
      @NotNull UUID patientId,
      @NotNull UUID doctorId,
      @NotNull Instant slotStart,
      @NotNull @DecimalMin("0.00") BigDecimal fee,
      List<DrugItem> items) {}

  public record PublishSlots(
      @NotNull UUID doctorId,
      @NotNull Instant start,
      @Min(5) @Max(240) int slotMinutes,
      @Min(1) @Max(100) int count) {}
}
