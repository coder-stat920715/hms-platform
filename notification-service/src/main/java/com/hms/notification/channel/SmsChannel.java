package com.hms.notification.channel;

import com.hms.notification.domain.ChannelType;
import com.hms.notification.domain.Recipient;
import com.hms.notification.service.RenderedMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;

@Component
@ConditionalOnProperty(name = "hms.notification.mode", havingValue = "aws")
public class SmsChannel implements NotificationChannel {

  private final SnsClient sns;

  public SmsChannel(SnsClient sns) { this.sns = sns; }

  @Override public ChannelType type() { return ChannelType.SMS; }

  @Override
  @Retry(name = "sns")
  @CircuitBreaker(name = "sns")
  public void send(Recipient to, RenderedMessage m) {
    if (to.getPhone() == null || to.getPhone().isBlank()) throw new IllegalStateException("no phone number");
    sns.publish(b -> b.phoneNumber(to.getPhone()).message(m.shortBody()));
  }
}
