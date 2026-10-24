package com.hms.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Column names match Debezium's outbox EventRouter defaults. */
@Entity
@Table(name = "outbox")
public class OutboxEvent implements Persistable<UUID> {

  @Id
  private UUID id;

  @Column(name = "aggregatetype", nullable = false)
  private String aggregateType;

  @Column(name = "aggregateid", nullable = false)
  private String aggregateId;

  @Column(name = "type", nullable = false)
  private String type;

  @Column(name = "payload", nullable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Transient
  private boolean isNew = true;

  protected OutboxEvent() {}

  public OutboxEvent(String aggregateType, String aggregateId, String type, String payload) {
    this.id = UUID.randomUUID();
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.type = type;
    this.payload = payload;
  }

  @Override public UUID getId() { return id; }
  @Override public boolean isNew() { return isNew; }

  @PostLoad
  @PostPersist
  void markNotNew() { this.isNew = false; }

  public String getAggregateType() { return aggregateType; }
  public String getAggregateId() { return aggregateId; }
  public String getType() { return type; }
  public String getPayload() { return payload; }
  public Instant getCreatedAt() { return createdAt; }
}
