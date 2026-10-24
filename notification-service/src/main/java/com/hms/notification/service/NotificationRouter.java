package com.hms.notification.service;

import com.hms.notification.channel.EmailChannel;
import com.hms.notification.channel.NotificationChannel;
import com.hms.notification.config.NotificationProperties;
import com.hms.notification.domain.ChannelType;
import com.hms.notification.domain.Recipient;
import com.hms.notification.domain.RecipientRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Tries the recipient's preferred channels in order; the first success wins, the last failure is rethrown. */
@Service
public class NotificationRouter {

  private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

  private final Map<ChannelType, NotificationChannel> channels = new EnumMap<>(ChannelType.class);
  private final RecipientRepository recipients;
  private final NotificationProperties props;
  private final ObjectProvider<EmailChannel> awsEmail;

  public NotificationRouter(List<NotificationChannel> all, RecipientRepository recipients,
                            NotificationProperties props, ObjectProvider<EmailChannel> awsEmail) {
    all.forEach(c -> channels.put(c.type(), c));
    this.recipients = recipients;
    this.props = props;
    this.awsEmail = awsEmail;
  }

  public void sendToPatient(UUID patientId, Template template, Object... args) {
    Optional<Recipient> found = recipients.findById(patientId);
    if (found.isEmpty()) {
      log.warn("No contact profile for patient {}; skipping {}", patientId, template);
      return;
    }
    var to = found.get();
    var message = template.render(args);
    RuntimeException last = null;
    for (ChannelType type : to.preferredChannels()) {
      var channel = channels.get(type);
      if (channel == null) continue;
      try {
        channel.send(to, message);
        return;
      } catch (RuntimeException e) {
        last = e;
        log.warn("Channel {} failed for patient {}: {}", type, patientId, e.getMessage());
      }
    }
    throw new NotificationFailedException("All channels failed for patient " + patientId
        + (last == null ? "" : ": " + last.getMessage()));
  }

  /** Operational alert to a fixed address (not tied to a patient). */
  public void sendToOperations(Template template, Object... args) {
    var message = template.render(args);
    var email = awsEmail.getIfAvailable();
    if (email != null) email.sendTo(props.pharmacyAlertEmail(), message);
    else log.info("[ops-alert] to={} subject=\"{}\" body=\"{}\"", props.pharmacyAlertEmail(), message.subject(), message.body());
  }
}
