package com.hms.billing.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hms.billing.service.InvoiceService;
import com.hms.common.events.CreateInvoice;
import com.hms.common.events.Topics;
import com.hms.common.events.VoidInvoice;
import com.hms.common.messaging.Deduper;
import com.hms.common.messaging.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BillingCommandListener {

  private static final String CONSUMER = "billing";

  private final InvoiceService invoices;
  private final Deduper deduper;
  private final ObjectMapper mapper;

  public BillingCommandListener(InvoiceService invoices, Deduper deduper, ObjectMapper mapper) {
    this.invoices = invoices;
    this.deduper = deduper;
    this.mapper = mapper;
  }

  @KafkaListener(topics = Topics.BILLING_COMMANDS, groupId = CONSUMER)
  @Transactional
  public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
    if (!deduper.firstTime(record, CONSUMER)) return;
    switch (EventHeaders.eventType(record)) {
      case "CreateInvoice" -> invoices.create(mapper.readValue(record.value(), CreateInvoice.class));
      case "VoidInvoice" -> invoices.voidInvoice(mapper.readValue(record.value(), VoidInvoice.class).invoiceId());
      default -> { }
    }
  }
}
