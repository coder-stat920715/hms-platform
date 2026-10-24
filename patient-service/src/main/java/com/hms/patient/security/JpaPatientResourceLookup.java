package com.hms.patient.security;

import com.hms.patient.domain.PatientRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short-TTL cache (see application.yml); evicted by PatientService on sensitivity / care-team changes. */
@Component
public class JpaPatientResourceLookup implements PatientResourceLookup {

  private final PatientRepository repository;

  public JpaPatientResourceLookup(PatientRepository repository) {
    this.repository = repository;
  }

  @Override
  @Cacheable(cacheNames = "patientResource", key = "#patientId")
  @Transactional(readOnly = true)
  public Optional<PatientResource> find(UUID patientId) {
    return repository.findById(patientId).map(p -> new PatientResource(
        p.getId(), p.getSensitivity(), p.getDepartment(), Set.copyOf(p.getCareTeam())));
  }
}
