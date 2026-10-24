package com.hms.billing.api;

import com.hms.billing.service.InvoiceService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

  private final InvoiceService service;

  public InvoiceController(InvoiceService service) {
    this.service = service;
  }

  @PreAuthorize("hasAnyRole('BILLING_CLERK','ADMIN')")
  @GetMapping("/{id}")
  public InvoiceDto get(@PathVariable UUID id) {
    return InvoiceDto.from(service.get(id));
  }

  @PreAuthorize("hasAnyRole('BILLING_CLERK','ADMIN')")
  @GetMapping("/by-appointment/{appointmentId}")
  public InvoiceDto byAppointment(@PathVariable UUID appointmentId) {
    return InvoiceDto.from(service.getByAppointment(appointmentId));
  }

  @PreAuthorize("hasRole('BILLING_CLERK')")
  @PostMapping("/{id}/pay")
  public InvoiceDto pay(@PathVariable UUID id) {
    return InvoiceDto.from(service.pay(id));
  }
}
