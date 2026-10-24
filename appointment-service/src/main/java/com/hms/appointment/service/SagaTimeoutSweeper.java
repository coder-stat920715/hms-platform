package com.hms.appointment.service;

import com.hms.appointment.domain.AppointmentSagaRepository;
import com.hms.appointment.domain.SagaState;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Compensates sagas whose reply never arrived. Safe on many pods: rows are locked with SKIP LOCKED. */
@Component
public class SagaTimeoutSweeper {

  private final AppointmentSagaRepository sagas;
  private final SagaOrchestrator orchestrator;
  private final Clock clock;
  private final Duration timeout;

  public SagaTimeoutSweeper(AppointmentSagaRepository sagas, SagaOrchestrator orchestrator, Clock clock,
                            @Value("${hms.saga.timeout:PT2M}") Duration timeout) {
    this.sagas = sagas;
    this.orchestrator = orchestrator;
    this.clock = clock;
    this.timeout = timeout;
  }

  @Scheduled(fixedDelayString = "${hms.saga.sweep-interval-ms:30000}")
  @Transactional
  public void sweep() {
    var stuck = sagas.findStuck(Set.of(SagaState.SLOT_RESERVED, SagaState.INVOICE_CREATED),
        clock.instant().minus(timeout));
    stuck.forEach(orchestrator::timeout);
  }
}
