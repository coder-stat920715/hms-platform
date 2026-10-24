package com.hms.appointment.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AppointmentSagaRepository extends JpaRepository<AppointmentSaga, UUID> {

  /** Locked with SKIP LOCKED so several pods can run the timeout sweeper without double-compensating. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select s from AppointmentSaga s where s.state in :states and s.updatedAt < :cutoff")
  List<AppointmentSaga> findStuck(@Param("states") Collection<SagaState> states, @Param("cutoff") Instant cutoff);
}
