package com.hms.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "recipient")
public class Recipient {

  @Id
  @Column(name = "patient_id")
  private UUID patientId;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  private String email;
  private String phone;

  @Column(nullable = false)
  private String locale = "en";

  @Column(nullable = false)
  private String channels = "EMAIL";

  @Column(name = "push_endpoint_arn")
  private String pushEndpointArn;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  protected Recipient() {}

  public Recipient(UUID patientId) { this.patientId = patientId; }

  public List<ChannelType> preferredChannels() {
    return Arrays.stream(channels.split(","))
        .map(String::trim).filter(s -> !s.isEmpty())
        .map(s -> { try { return ChannelType.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return null; } })
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  public UUID getPatientId() { return patientId; }
  public String getFullName() { return fullName; }
  public void setFullName(String fullName) { this.fullName = fullName; this.updatedAt = Instant.now(); }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getLocale() { return locale; }
  public void setLocale(String locale) { this.locale = locale; }
  public String getChannels() { return channels; }
  public void setChannels(String channels) { this.channels = channels; }
  public String getPushEndpointArn() { return pushEndpointArn; }
  public void setPushEndpointArn(String pushEndpointArn) { this.pushEndpointArn = pushEndpointArn; }
}
