package com.hms.common.events;

import java.util.UUID;

public record AppointmentFailed(UUID appointmentId, UUID patientId, String reason) {}
