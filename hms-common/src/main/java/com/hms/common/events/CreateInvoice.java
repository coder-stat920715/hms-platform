package com.hms.common.events;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateInvoice(UUID appointmentId, UUID patientId, BigDecimal fee) {}
