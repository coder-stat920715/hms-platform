package com.hms.pharmacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "allocation")
public class Allocation {

  @Id
  private UUID id;

  @Column(name = "appointment_id", nullable = false)
  private UUID appointmentId;

  @Column(name = "drug_id", nullable = false)
  private UUID drugId;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false)
  private String status;

  protected Allocation() {}

  public Allocation(UUID appointmentId, UUID drugId, int quantity, String status) {
    this.id = UUID.randomUUID();
    this.appointmentId = appointmentId;
    this.drugId = drugId;
    this.quantity = quantity;
    this.status = status;
  }

  public UUID getId() { return id; }
  public UUID getAppointmentId() { return appointmentId; }
  public UUID getDrugId() { return drugId; }
  public int getQuantity() { return quantity; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
}
