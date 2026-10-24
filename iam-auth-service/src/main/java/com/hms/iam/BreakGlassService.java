package com.hms.iam;

import com.hms.common.events.BreakGlassUsed;
import com.hms.common.events.Topics;
import com.hms.common.outbox.DomainEvent;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BreakGlassService {

  private final ApplicationEventPublisher events;

  public BreakGlassService(ApplicationEventPublisher events) {
    this.events = events;
  }

  /** Records the declaration (with justification) as an audit event for compliance review. */
  @Transactional
  public void declare(String userId, BreakGlassRequest request) {
    events.publishEvent(new DomainEvent(Topics.AGG_IAM, userId, "BreakGlassUsed",
        new BreakGlassUsed(userId, request.patientId(), request.reason(), Instant.now())));
  }
}
