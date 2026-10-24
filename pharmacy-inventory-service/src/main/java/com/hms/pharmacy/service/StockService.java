package com.hms.pharmacy.service;

import com.hms.common.error.BusinessValidationException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.LowStock;
import com.hms.common.events.Topics;
import com.hms.common.outbox.DomainEvent;
import com.hms.pharmacy.domain.DrugStock;
import com.hms.pharmacy.domain.DrugStockRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {

  private final DrugStockRepository stock;
  private final ApplicationEventPublisher events;

  public StockService(DrugStockRepository stock, ApplicationEventPublisher events) {
    this.stock = stock;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<DrugStock> list() { return stock.findAll(); }

  @Transactional(readOnly = true)
  public DrugStock get(UUID id) {
    return stock.findById(id).orElseThrow(() -> new NotFoundException("Drug not found"));
  }

  @Transactional
  public DrugStock create(String name, int onHand, int reorderAt) {
    return stock.save(new DrugStock(UUID.randomUUID(), name, onHand, reorderAt));
  }

  @Transactional
  public DrugStock restock(UUID id, int quantity) {
    if (quantity <= 0) throw new BusinessValidationException("quantity must be positive");
    var s = get(id);
    s.setOnHand(s.getOnHand() + quantity);
    if (s.available() > s.getReorderAt()) s.setLowStockAlerted(false);    // re-arm the alert
    return s;
  }

  /** One alert per low-stock episode; SKIP LOCKED lets several pods run this without duplicate alerts. */
  @Scheduled(fixedDelayString = "${hms.pharmacy.low-stock-scan-ms:300000}")
  @Transactional
  public void scanLowStock() {
    for (var s : stock.lockLowStockNotAlerted()) {
      s.setLowStockAlerted(true);
      events.publishEvent(new DomainEvent(Topics.AGG_PHARMACY, s.getDrugId().toString(), "LowStockDetected",
          new LowStock(s.getDrugId(), s.getName(), s.available())));
    }
  }
}
