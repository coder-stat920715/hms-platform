package com.hms.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AppointmentRequested(UUID appointmentId, UUID patientId, UUID doctorId, Instant slot,
                                   BigDecimal fee, List<DrugItem> items) {}
