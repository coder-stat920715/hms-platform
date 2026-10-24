package com.hms.pharmacy.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface DrugStockRepository extends JpaRepository<DrugStock, UUID> {

  /** Single atomic statement: reserves only if enough free stock exists, so concurrent allocations cannot oversell. */
  @Modifying
  @Query("update DrugStock s set s.reserved = s.reserved + :qty, s.version = s.version + 1 "
       + "where s.drugId = :drugId and (s.onHand - s.reserved) >= :qty")
  int tryReserve(@Param("drugId") UUID drugId, @Param("qty") int qty);

  @Modifying
  @Query("update DrugStock s set s.reserved = s.reserved - :qty, s.version = s.version + 1 "
       + "where s.drugId = :drugId and s.reserved >= :qty")
  int release(@Param("drugId") UUID drugId, @Param("qty") int qty);

  /** Free stock; 0 for an unknown drug. */
  @Query("select coalesce(max(s.onHand - s.reserved), 0) from DrugStock s where s.drugId = :drugId")
  int available(@Param("drugId") UUID drugId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select s from DrugStock s where (s.onHand - s.reserved) <= s.reorderAt and s.lowStockAlerted = false")
  List<DrugStock> lockLowStockNotAlerted();
}
