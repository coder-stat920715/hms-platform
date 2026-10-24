package com.hms.notification.channel;

import com.hms.notification.domain.ChannelType;
import com.hms.notification.domain.Recipient;
import com.hms.notification.service.RenderedMessage;

public interface NotificationChannel {
  ChannelType type();

  /** Throws on failure so the router can fall back to the next preferred channel. */
  void send(Recipient to, RenderedMessage message);
}
