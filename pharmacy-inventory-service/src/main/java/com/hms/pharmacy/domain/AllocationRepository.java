package com.hms.pharmacy.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationRepository extends JpaRepository<Allocation, UUID> {
  boolean existsByAppointmentId(UUID appointmentId);
  List<Allocation> findByAppointmentIdAndStatus(UUID appointmentId, String status);
}
