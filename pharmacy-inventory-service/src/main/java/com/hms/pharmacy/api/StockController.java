package com.hms.pharmacy.api;

import com.hms.pharmacy.domain.DrugStock;
import com.hms.pharmacy.service.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ROLE_PHARMACIST is an addition to the six roles in the brief (pharmacy needs an owner role). */
@RestController
@RequestMapping("/api/pharmacy/stock")
public class StockController {

  public record StockDto(UUID drugId, String name, int onHand, int reserved, int available, int reorderAt) {
    static StockDto from(DrugStock s) {
      return new StockDto(s.getDrugId(), s.getName(), s.getOnHand(), s.getReserved(), s.available(), s.getReorderAt());
    }
  }

  public record CreateDrug(@NotBlank String name, @Min(0) int onHand, @Min(0) int reorderAt) {}

  public record Restock(@Min(1) int quantity) {}

  private final StockService service;

  public StockController(StockService service) {
    this.service = service;
  }

  @PreAuthorize("hasAnyRole('PHARMACIST','DOCTOR','NURSE','ADMIN')")
  @GetMapping
  public List<StockDto> list() {
    return service.list().stream().map(StockDto::from).toList();
  }

  @PreAuthorize("hasAnyRole('PHARMACIST','DOCTOR','NURSE','ADMIN')")
  @GetMapping("/{id}")
  public StockDto get(@PathVariable UUID id) {
    return StockDto.from(service.get(id));
  }

  @PreAuthorize("hasAnyRole('PHARMACIST','ADMIN')")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public StockDto create(@Valid @RequestBody CreateDrug r) {
    return StockDto.from(service.create(r.name(), r.onHand(), r.reorderAt()));
  }

  @PreAuthorize("hasRole('PHARMACIST')")
  @PostMapping("/{id}/restock")
  public StockDto restock(@PathVariable UUID id, @Valid @RequestBody Restock r) {
    return StockDto.from(service.restock(id, r.quantity()));
  }
}
