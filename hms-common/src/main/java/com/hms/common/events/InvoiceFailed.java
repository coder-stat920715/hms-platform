package com.hms.common.events;

import java.util.UUID;

public record InvoiceFailed(UUID appointmentId, String reason) {}
