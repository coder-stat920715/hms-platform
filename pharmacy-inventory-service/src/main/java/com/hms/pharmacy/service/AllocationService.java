package com.hms.pharmacy.service;

import com.hms.common.events.AllocateInventory;
import com.hms.common.events.AllocationFailed;
import com.hms.common.events.InventoryAllocated;
import com.hms.common.events.Topics;
import com.hms.common.outbox.DomainEvent;
import com.hms.pharmacy.domain.Allocation;
import com.hms.pharmacy.domain.AllocationRepository;
import com.hms.pharmacy.domain.DrugStockRepository;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllocationService {

  private final DrugStockRepository stock;
  private final AllocationRepository allocations;
  private final ApplicationEventPublisher events;

  public AllocationService(DrugStockRepository stock, AllocationRepository allocations,
                           ApplicationEventPublisher events) {
    this.stock = stock;
    this.allocations = allocations;
    this.events = events;
  }

  @Transactional
  public void allocate(AllocateInventory cmd) {
    if (allocations.existsByAppointmentId(cmd.appointmentId())) {   // redelivered command: repeat the reply
      publishAllocated(cmd.appointmentId());
      return;
    }
    var items = cmd.items() == null ? java.util.List.<com.hms.common.events.DrugItem>of() : cmd.items();

    for (var it : items) {                                          // 1. check everything first
      if (stock.available(it.drugId()) < it.quantity()) {
        events.publishEvent(new DomainEvent(Topics.AGG_PHARMACY, cmd.appointmentId().toString(), "AllocationFailed",
            new AllocationFailed(cmd.appointmentId(), "insufficient stock: " + it.drugId())));
        return;                                                     // nothing reserved yet; commits normally
      }
    }
    for (var it : items) {                                          // 2. reserve; the atomic guard still protects races
      if (stock.tryReserve(it.drugId(), it.quantity()) == 0) {
        throw new ConcurrentStockChangeException(it.drugId());      // rolls back partial reservations
      }
      allocations.save(new Allocation(cmd.appointmentId(), it.drugId(), it.quantity(), "ALLOCATED"));
    }
    publishAllocated(cmd.appointmentId());
  }

  /** Compensation: give reserved stock back. Safe to call repeatedly. */
  @Transactional
  public void release(UUID appointmentId) {
    for (var a : allocations.findByAppointmentIdAndStatus(appointmentId, "ALLOCATED")) {
      stock.release(a.getDrugId(), a.getQuantity());
      a.setStatus("RELEASED");
    }
  }

  private void publishAllocated(UUID appointmentId) {
    events.publishEvent(new DomainEvent(Topics.AGG_PHARMACY, appointmentId.toString(), "InventoryAllocated",
        new InventoryAllocated(appointmentId)));
  }
}
