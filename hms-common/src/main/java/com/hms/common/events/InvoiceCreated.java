package com.hms.common.events;

import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceCreated(UUID invoiceId, UUID appointmentId, UUID patientId, BigDecimal amount) {}
