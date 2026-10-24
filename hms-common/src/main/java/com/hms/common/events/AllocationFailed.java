package com.hms.common.events;

import java.util.UUID;

public record AllocationFailed(UUID appointmentId, String reason) {}
