package com.hms.notification.channel;

import com.hms.notification.domain.ChannelType;
import com.hms.notification.domain.Recipient;
import com.hms.notification.service.RenderedMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;

/** Mobile push through an SNS platform endpoint (APNs / FCM) stored on the recipient. */
@Component
@ConditionalOnProperty(name = "hms.notification.mode", havingValue = "aws")
public class PushChannel implements NotificationChannel {

  private final SnsClient sns;

  public PushChannel(SnsClient sns) { this.sns = sns; }

  @Override public ChannelType type() { return ChannelType.PUSH; }

  @Override
  @Retry(name = "sns")
  @CircuitBreaker(name = "sns")
  public void send(Recipient to, RenderedMessage m) {
    if (to.getPushEndpointArn() == null || to.getPushEndpointArn().isBlank()) throw new IllegalStateException("no push endpoint");
    sns.publish(b -> b.targetArn(to.getPushEndpointArn()).message(m.shortBody()).subject(m.subject()));
  }
}
