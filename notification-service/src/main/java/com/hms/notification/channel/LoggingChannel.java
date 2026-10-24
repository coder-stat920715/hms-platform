package com.hms.notification.channel;

import com.hms.notification.domain.ChannelType;
import com.hms.notification.domain.Recipient;
import com.hms.notification.service.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development channel: writes the message to the log instead of sending it. */
public class LoggingChannel implements NotificationChannel {

  private static final Logger log = LoggerFactory.getLogger(LoggingChannel.class);
  private final ChannelType type;

  public LoggingChannel(ChannelType type) { this.type = type; }

  @Override public ChannelType type() { return type; }

  @Override
  public void send(Recipient to, RenderedMessage m) {
    log.info("[{}] to={} subject=\"{}\" body=\"{}\"", type, to.getPatientId(), m.subject(), m.body());
  }
}
