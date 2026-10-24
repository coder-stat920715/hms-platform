package com.hms.common.messaging;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Reads headers added by Debezium's EventRouter (see connector config: eventType, messageId). */
public final class EventHeaders {
  private EventHeaders() {}

  public static String header(ConsumerRecord<?, ?> record, String name) {
    Header h = record.headers().lastHeader(name);
    return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  public static String eventType(ConsumerRecord<?, ?> record) {
    String t = header(record, "eventType");
    if (t == null) throw new IllegalArgumentException("Missing eventType header on " + record.topic());
    return t;
  }

  /** Falls back to topic-partition-offset when the producer did not set a messageId header. */
  public static String messageId(ConsumerRecord<?, ?> record) {
    String id = header(record, "messageId");
    return id != null ? id : record.topic() + "-" + record.partition() + "-" + record.offset();
  }
}
