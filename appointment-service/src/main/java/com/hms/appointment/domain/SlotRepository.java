package com.hms.appointment.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

  /** SELECT ... FOR UPDATE SKIP LOCKED (timeout -2): concurrent bookings of one slot never block each other. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select s from Slot s where s.doctorId = :doctorId and s.startTime = :start "
       + "and s.status = com.hms.appointment.domain.SlotStatus.FREE")
  Optional<Slot> lockFree(@Param("doctorId") UUID doctorId, @Param("start") Instant start);

  /** Compensation: frees whatever slot the appointment holds (no-op if none). */
  @Modifying(flushAutomatically = true)
  @Query("update Slot s set s.status = com.hms.appointment.domain.SlotStatus.FREE, s.appointmentId = null "
       + "where s.appointmentId = :appointmentId")
  int release(@Param("appointmentId") UUID appointmentId);

  @Query("select s from Slot s where s.doctorId = :doctorId and s.startTime >= :from and s.startTime < :to "
       + "and s.status = com.hms.appointment.domain.SlotStatus.FREE order by s.startTime")
  List<Slot> findFree(@Param("doctorId") UUID doctorId, @Param("from") Instant from, @Param("to") Instant to);
}
