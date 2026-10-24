package com.hms.patient.security;

import com.hms.common.domain.Sensitivity;
import java.util.Set;
import java.util.UUID;

/** Resource attributes the ABAC policy needs. */
public record PatientResource(UUID patientId, Sensitivity sensitivity, String department, Set<UUID> careTeam) {}
