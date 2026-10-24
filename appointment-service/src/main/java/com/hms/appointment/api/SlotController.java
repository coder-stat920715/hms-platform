package com.hms.appointment.api;

import com.hms.appointment.api.AppointmentRequests.PublishSlots;
import com.hms.appointment.domain.Slot;
import com.hms.appointment.service.SlotService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appointments/slots")
public class SlotController {

  public record SlotDto(UUID id, UUID doctorId, Instant start, Instant end) {
    static SlotDto from(Slot s) { return new SlotDto(s.getId(), s.getDoctorId(), s.getStartTime(), s.getEndTime()); }
  }

  private final SlotService service;

  public SlotController(SlotService service) {
    this.service = service;
  }

  @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public List<SlotDto> publish(@Valid @RequestBody PublishSlots r) {
    return service.publish(r.doctorId(), r.start(), r.slotMinutes(), r.count()).stream().map(SlotDto::from).toList();
  }

  @PreAuthorize("hasAnyRole('RECEPTIONIST','DOCTOR','NURSE','PATIENT','ADMIN')")
  @GetMapping
  public List<SlotDto> free(@RequestParam UUID doctorId, @RequestParam Instant from, @RequestParam Instant to) {
    return service.free(doctorId, from, to).stream().map(SlotDto::from).toList();
  }
}
