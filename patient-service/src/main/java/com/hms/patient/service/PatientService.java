package com.hms.patient.service;

import com.hms.common.domain.Sensitivity;
import com.hms.common.error.BusinessValidationException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.PatientProfileChanged;
import com.hms.common.events.SensitivityChanged;
import com.hms.common.events.Topics;
import com.hms.common.outbox.DomainEvent;
import com.hms.patient.api.PatientRequests.RegisterPatient;
import com.hms.patient.api.PatientRequests.UpdatePatient;
import com.hms.patient.domain.Patient;
import com.hms.patient.domain.PatientRepository;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {

  private final PatientRepository patients;
  private final ApplicationEventPublisher events;

  public PatientService(PatientRepository patients, ApplicationEventPublisher events) {
    this.patients = patients;
    this.events = events;
  }

  @Transactional
  public Patient register(RegisterPatient r) {
    UUID id = r.id() != null ? r.id() : UUID.randomUUID();
    if (patients.existsById(id)) throw new BusinessValidationException("Patient id already registered");
    var p = new Patient(id, r.fullName(), r.dateOfBirth());
    p.setNationalId(r.nationalId());
    p.setDepartment(r.department());
    p.setEmail(r.email());
    p.setPhone(r.phone());
    if (r.locale() != null) p.setLocale(r.locale());
    if (r.channels() != null && !r.channels().isEmpty()) p.setChannels(String.join(",", r.channels()));
    p.setPushEndpointArn(r.pushEndpointArn());
    patients.save(p);
    publishProfile(p);
    return p;
  }

  @Transactional(readOnly = true)
  public Patient get(UUID id) {
    return patients.findById(id).orElseThrow(() -> new NotFoundException("Patient not found"));
  }

  @Transactional
  @CacheEvict(cacheNames = "patientResource", key = "#id")
  public Patient update(UUID id, UpdatePatient u) {
    var p = get(id);
    if (u.fullName() != null) p.setFullName(u.fullName());
    if (u.department() != null) p.setDepartment(u.department());
    if (u.email() != null) p.setEmail(u.email());
    if (u.phone() != null) p.setPhone(u.phone());
    if (u.locale() != null) p.setLocale(u.locale());
    if (u.channels() != null && !u.channels().isEmpty()) p.setChannels(String.join(",", u.channels()));
    if (u.pushEndpointArn() != null) p.setPushEndpointArn(u.pushEndpointArn());
    publishProfile(p);
    return p;
  }

  @Transactional
  @CacheEvict(cacheNames = "patientResource", key = "#id")
  public Patient changeSensitivity(UUID id, Sensitivity sensitivity) {
    var p = get(id);
    p.setSensitivity(sensitivity);
    events.publishEvent(new DomainEvent(Topics.AGG_PATIENT, id.toString(), "PatientSensitivityChanged",
        new SensitivityChanged(id, sensitivity)));
    return p;
  }

  @Transactional
  @CacheEvict(cacheNames = "patientResource", key = "#id")
  public void addToCareTeam(UUID id, UUID userId) {
    get(id).getCareTeam().add(userId);
  }

  @Transactional
  @CacheEvict(cacheNames = "patientResource", key = "#id")
  public void removeFromCareTeam(UUID id, UUID userId) {
    get(id).getCareTeam().remove(userId);
  }

  private void publishProfile(Patient p) {
    List<String> channels = Arrays.asList(p.getChannels().split(","));
    events.publishEvent(new DomainEvent(Topics.AGG_PATIENT, p.getId().toString(), "PatientProfileChanged",
        new PatientProfileChanged(p.getId(), p.getFullName(), p.getEmail(), p.getPhone(), p.getLocale(),
            channels, p.getPushEndpointArn())));
  }
}
