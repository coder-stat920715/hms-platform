package com.hms.patient.api;

import com.hms.patient.api.PatientRequests.RegisterPatient;
import com.hms.patient.api.PatientRequests.SensitivityRequest;
import com.hms.patient.api.PatientRequests.UpdatePatient;
import com.hms.patient.service.PatientService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

  private final PatientService service;

  public PatientController(PatientService service) {
    this.service = service;
  }

  /** Registration is an administrative (demographics) action. */
  @PreAuthorize("hasAnyRole('RECEPTIONIST')")
  @PostMapping
  public ResponseEntity<PatientDto> register(@Valid @RequestBody RegisterPatient request) {
    var p = service.register(request);
    return ResponseEntity.created(URI.create("/api/patients/" + p.getId())).body(PatientDto.from(p));
  }

  @PreAuthorize("hasPermission(#id, 'Patient', 'READ')")
  @GetMapping("/{id}")
  public PatientDto get(@PathVariable UUID id) {
    return PatientDto.from(service.get(id));
  }

  @PreAuthorize("hasPermission(#id, 'Patient', 'WRITE')")
  @PutMapping("/{id}")
  public PatientDto update(@PathVariable UUID id, @Valid @RequestBody UpdatePatient request) {
    return PatientDto.from(service.update(id, request));
  }

  @PreAuthorize("hasPermission(#id, 'Patient', 'WRITE')")
  @PutMapping("/{id}/sensitivity")
  public PatientDto changeSensitivity(@PathVariable UUID id, @Valid @RequestBody SensitivityRequest request) {
    return PatientDto.from(service.changeSensitivity(id, request.sensitivity()));
  }

  @PreAuthorize("hasPermission(#id, 'Patient', 'WRITE')")
  @PutMapping("/{id}/care-team/{userId}")
  public ResponseEntity<Void> addToCareTeam(@PathVariable UUID id, @PathVariable UUID userId) {
    service.addToCareTeam(id, userId);
    return ResponseEntity.noContent().build();
  }

  @PreAuthorize("hasPermission(#id, 'Patient', 'WRITE')")
  @DeleteMapping("/{id}/care-team/{userId}")
  public ResponseEntity<Void> removeFromCareTeam(@PathVariable UUID id, @PathVariable UUID userId) {
    service.removeFromCareTeam(id, userId);
    return ResponseEntity.noContent().build();
  }
}
