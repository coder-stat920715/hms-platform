package com.hms.iam;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/iam/break-glass")
public class BreakGlassController {

  private final BreakGlassService service;

  public BreakGlassController(BreakGlassService service) {
    this.service = service;
  }

  /**
   * NOTE: declaring break-glass here does not by itself grant access. Honouring it is controlled by
   * hms.security.break-glass-enabled in patient-service (off by default). See README, "Known gaps".
   */
  @PreAuthorize("hasRole('DOCTOR')")
  @PostMapping
  public ResponseEntity<Void> declare(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BreakGlassRequest request) {
    service.declare(jwt.getSubject(), request);
    return ResponseEntity.accepted().build();
  }
}
