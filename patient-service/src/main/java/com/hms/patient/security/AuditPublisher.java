package com.hms.patient.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every access decision (permit or deny) goes to the dedicated "hms.audit" logger as one structured line.
 * Ship that logger to an immutable store (CloudWatch -> S3 Object Lock). Logging is used instead of the outbox
 * because @PreAuthorize runs before the service transaction starts.
 */
@Component
public class AuditPublisher {

  private static final Logger AUDIT = LoggerFactory.getLogger("hms.audit");

  public void record(UUID userId, UUID patientId, String action, boolean permit, boolean flagged, String reason) {
    AUDIT.info("access user={} patient={} action={} decision={} flagged={} reason=\"{}\"",
        userId, patientId, action, permit ? "PERMIT" : "DENY", flagged, reason);
  }
}
