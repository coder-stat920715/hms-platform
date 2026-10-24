package com.hms.common.events;

/**
 * Aggregate types written to the outbox and the Kafka topics Debezium's EventRouter derives from them
 * (route.topic.replacement = "hms.${routedByValue}.events").
 */
public final class Topics {
  private Topics() {}

  public static final String AGG_APPOINTMENT = "appointment";
  public static final String AGG_BILLING = "billing";
  public static final String AGG_PHARMACY = "pharmacy";
  public static final String AGG_PATIENT = "patient";
  public static final String AGG_IAM = "iam";
  public static final String AGG_BILLING_COMMANDS = "billing-commands";
  public static final String AGG_PHARMACY_COMMANDS = "pharmacy-commands";

  public static final String APPOINTMENT = "hms.appointment.events";
  public static final String BILLING = "hms.billing.events";
  public static final String PHARMACY = "hms.pharmacy.events";
  public static final String PATIENT = "hms.patient.events";
  public static final String IAM = "hms.iam.events";
  public static final String BILLING_COMMANDS = "hms.billing-commands.events";
  public static final String PHARMACY_COMMANDS = "hms.pharmacy-commands.events";
}
