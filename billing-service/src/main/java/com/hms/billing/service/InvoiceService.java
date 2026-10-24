package com.hms.billing.service;

import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.InvoiceRepository;
import com.hms.common.error.DownstreamUnavailableException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.CreateInvoice;
import com.hms.common.events.InvoiceCreated;
import com.hms.common.events.InvoiceFailed;
import com.hms.common.events.InvoicePaid;
import com.hms.common.events.Topics;
import com.hms.common.outbox.DomainEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Replies are keyed by appointment id so the saga can correlate them. */
@Service
public class InvoiceService {

  private final InvoiceRepository invoices;
  private final InsuranceClient insurance;
  private final ApplicationEventPublisher events;

  public InvoiceService(InvoiceRepository invoices, InsuranceClient insurance, ApplicationEventPublisher events) {
    this.invoices = invoices;
    this.insurance = insurance;
    this.events = events;
  }

  @Transactional
  public void create(CreateInvoice cmd) {
    var existing = invoices.findByAppointmentId(cmd.appointmentId()).orElse(null);
    if (existing != null) {                                         // redelivered command: repeat the reply
      publishCreated(existing);
      return;
    }
    boolean approved;
    try {
      approved = insurance.verify(cmd.patientId(), cmd.fee());
    } catch (DownstreamUnavailableException e) {
      publishFailed(cmd, "insurance verification unavailable");
      return;
    }
    if (!approved) {
      publishFailed(cmd, "insurance rejected the claim");
      return;
    }
    var invoice = invoices.save(new Invoice(cmd.appointmentId(), cmd.patientId(), cmd.fee()));
    publishCreated(invoice);
  }

  @Transactional
  public void voidInvoice(UUID invoiceId) {
    invoices.findById(invoiceId).ifPresent(Invoice::voidIt);      // unknown or already void: no-op
  }

  @Transactional
  public Invoice pay(UUID invoiceId) {
    var invoice = get(invoiceId);
    if (invoice.markPaid()) {
      events.publishEvent(new DomainEvent(Topics.AGG_BILLING, invoice.getAppointmentId().toString(), "InvoicePaid",
          new InvoicePaid(invoice.getId(), invoice.getAppointmentId())));
    }
    return invoice;
  }

  @Transactional(readOnly = true)
  public Invoice get(UUID id) {
    return invoices.findById(id).orElseThrow(() -> new NotFoundException("Invoice not found"));
  }

  @Transactional(readOnly = true)
  public Invoice getByAppointment(UUID appointmentId) {
    return invoices.findByAppointmentId(appointmentId).orElseThrow(() -> new NotFoundException("Invoice not found"));
  }

  private void publishCreated(Invoice i) {
    events.publishEvent(new DomainEvent(Topics.AGG_BILLING, i.getAppointmentId().toString(), "InvoiceCreated",
        new InvoiceCreated(i.getId(), i.getAppointmentId(), i.getPatientId(), i.getAmount())));
  }

  private void publishFailed(CreateInvoice cmd, String reason) {
    events.publishEvent(new DomainEvent(Topics.AGG_BILLING, cmd.appointmentId().toString(), "InvoiceFailed",
        new InvoiceFailed(cmd.appointmentId(), reason)));
  }
}
