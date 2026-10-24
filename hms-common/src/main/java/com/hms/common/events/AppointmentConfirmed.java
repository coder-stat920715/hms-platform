package com.hms.common.events;

import java.time.Instant;
import java.util.UUID;

public record AppointmentConfirmed(UUID appointmentId, UUID patientId, UUID doctorId, Instant slot) {}
