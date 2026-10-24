package com.hms.appointment.api;

import com.hms.appointment.api.AppointmentRequests.BookAppointment;
import com.hms.appointment.service.AppointmentService;
import com.hms.appointment.service.AppointmentService.BookCommand;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

  private final AppointmentService service;

  public AppointmentController(AppointmentService service) {
    this.service = service;
  }

  /** 202: the booking is asynchronous (saga). Poll GET /{id} or the search endpoint for the outcome. */
  @PreAuthorize("hasAnyRole('RECEPTIONIST','DOCTOR','PATIENT')")
  @PostMapping
  public ResponseEntity<AppointmentDto> book(@Valid @RequestBody BookAppointment request, Authentication auth) {
    if (isPatient(auth) && !auth.getName().equals(request.patientId().toString())) {
      throw new AccessDeniedException("Patients may only book for themselves");
    }
    var a = service.book(new BookCommand(request.patientId(), request.doctorId(), request.slotStart(),
        request.fee(), request.items()));
    return ResponseEntity.accepted().location(URI.create("/api/appointments/" + a.getId()))
        .body(AppointmentDto.from(a));
  }

  @PreAuthorize("hasAnyRole('RECEPTIONIST','DOCTOR','NURSE','PATIENT','ADMIN')")
  @GetMapping("/{id}")
  public AppointmentDto get(@PathVariable UUID id, Authentication auth) {
    var a = service.get(id);
    if (isPatient(auth) && !auth.getName().equals(a.getPatientId().toString())) {
      throw new AccessDeniedException("Not your appointment");
    }
    return AppointmentDto.from(a);
  }

  static boolean isPatient(Authentication auth) {
    return auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_PATIENT"));
  }
}
