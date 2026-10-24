package com.hms.notification.service;

/** Message templates. They intentionally carry no clinical detail (no diagnosis, drug names, doctor specialty). */
public enum Template {
  APPT_CONFIRMED("Appointment confirmed", "Your appointment on %s is confirmed."),
  APPT_FAILED("Appointment could not be booked", "We could not book your appointment. Please choose another time."),
  INVOICE_READY("Your invoice is ready", "An invoice for your recent appointment is now available."),
  LOW_STOCK("Low stock alert", "Stock for %s is running low (%s remaining).");

  private final String subject;
  private final String body;

  Template(String subject, String body) {
    this.subject = subject;
    this.body = body;
  }

  public RenderedMessage render(Object... args) {
    String text = args.length == 0 ? body : String.format(body, args);
    return new RenderedMessage(subject, text, text.length() <= 160 ? text : text.substring(0, 157) + "...");
  }
}
