package com.hms.common.outbox;

/**
 * Published with ApplicationEventPublisher INSIDE a @Transactional method. The listener persists it to the
 * outbox table in the same transaction (BEFORE_COMMIT). Published outside a transaction it is dropped.
 */
public record DomainEvent(String aggregateType, String aggregateId, String type, Object payload) {}
