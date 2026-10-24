package com.hms.common.events;

import java.time.Instant;
import java.util.UUID;

public record BreakGlassUsed(String userId, UUID patientId, String reason, Instant at) {}
