package com.hms.pharmacy.service;

import java.util.UUID;

/** Stock changed between the availability check and the reservation. The listener retries; the next pass re-checks. */
public class ConcurrentStockChangeException extends RuntimeException {
  public ConcurrentStockChangeException(UUID drugId) {
    super("Stock changed concurrently for drug " + drugId);
  }
}
