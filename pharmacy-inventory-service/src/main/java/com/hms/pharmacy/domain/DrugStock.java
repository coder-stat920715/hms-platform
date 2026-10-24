package com.hms.pharmacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "drug_stock")
public class DrugStock {

  @Id
  @Column(name = "drug_id")
  private UUID drugId;

  @Column(nullable = false)
  private String name;

  @Column(name = "on_hand", nullable = false)
  private int onHand;

  @Column(nullable = false)
  private int reserved;

  @Column(name = "reorder_at", nullable = false)
  private int reorderAt = 20;

  @Column(name = "low_stock_alerted", nullable = false)
  private boolean lowStockAlerted;

  @Version
  private Long version;

  protected DrugStock() {}

  public DrugStock(UUID drugId, String name, int onHand, int reorderAt) {
    this.drugId = drugId;
    this.name = name;
    this.onHand = onHand;
    this.reorderAt = reorderAt;
  }

  public int available() { return onHand - reserved; }

  public UUID getDrugId() { return drugId; }
  public String getName() { return name; }
  public int getOnHand() { return onHand; }
  public void setOnHand(int onHand) { this.onHand = onHand; }
  public int getReserved() { return reserved; }
  public int getReorderAt() { return reorderAt; }
  public boolean isLowStockAlerted() { return lowStockAlerted; }
  public void setLowStockAlerted(boolean lowStockAlerted) { this.lowStockAlerted = lowStockAlerted; }
}
