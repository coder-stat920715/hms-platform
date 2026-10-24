package com.hms.common.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

  /** Returns 1 if this is the first time the key is seen, 0 if it already existed (no exception, no tx abort). */
  @Transactional
  @Modifying
  @Query(value = "insert into processed_message (dedupe_key) values (:key) on conflict do nothing",
         nativeQuery = true)
  int insertIfAbsent(@Param("key") String key);
}
