package com.hms.billing.api;

import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.InvoiceStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceDto(UUID id, UUID appointmentId, UUID patientId, BigDecimal amount, InvoiceStatus status) {
  public static InvoiceDto from(Invoice i) {
    return new InvoiceDto(i.getId(), i.getAppointmentId(), i.getPatientId(), i.getAmount(), i.getStatus());
  }
}
