package com.hms.notification.channel;

import com.hms.notification.config.NotificationProperties;
import com.hms.notification.domain.ChannelType;
import com.hms.notification.domain.Recipient;
import com.hms.notification.service.RenderedMessage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Component
@ConditionalOnProperty(name = "hms.notification.mode", havingValue = "aws")
public class EmailChannel implements NotificationChannel {

  private final SesV2Client ses;
  private final NotificationProperties props;

  public EmailChannel(SesV2Client ses, NotificationProperties props) {
    this.ses = ses;
    this.props = props;
  }

  @Override public ChannelType type() { return ChannelType.EMAIL; }

  @Override
  @Retry(name = "ses")
  @CircuitBreaker(name = "ses")
  public void send(Recipient to, RenderedMessage m) {
    if (to.getEmail() == null || to.getEmail().isBlank()) throw new IllegalStateException("no email address");
    sendTo(to.getEmail(), m);
  }

  /** Used for operational alerts that are not tied to a patient. */
  @Retry(name = "ses")
  @CircuitBreaker(name = "ses")
  public void sendTo(String address, RenderedMessage m) {
    ses.sendEmail(b -> b
        .fromEmailAddress(props.fromEmail())
        .destination(d -> d.toAddresses(address))
        .content(c -> c.simple(s -> s
            .subject(x -> x.data(m.subject()))
            .body(x -> x.text(t -> t.data(m.body()))))));
  }
}
