package com.hms.common.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  @Transactional
  @Modifying
  @Query("delete from OutboxEvent o where o.createdAt < :cutoff")
  int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
