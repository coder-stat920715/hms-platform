package com.hms.patient.security;

import java.util.Optional;
import java.util.UUID;

public interface PatientResourceLookup {
  Optional<PatientResource> find(UUID patientId);
}
