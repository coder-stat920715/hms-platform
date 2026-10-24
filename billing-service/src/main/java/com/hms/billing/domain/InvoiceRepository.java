package com.hms.billing.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
  Optional<Invoice> findByAppointmentId(UUID appointmentId);
}
