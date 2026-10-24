package com.hms.patient.domain;

import com.hms.common.domain.Sensitivity;
import com.hms.patient.config.EncryptedStringConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "patient")
public class Patient {

  @Id
  private UUID id;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "national_id", length = 512)
  private String nationalId;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Column(name = "date_of_birth", nullable = false)
  private LocalDate dateOfBirth;

  private String department;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Sensitivity sensitivity = Sensitivity.NORMAL;

  private String email;
  private String phone;

  @Column(nullable = false)
  private String locale = "en";

  /** Comma-separated: EMAIL,SMS,PUSH. */
  @Column(nullable = false)
  private String channels = "EMAIL";

  @Column(name = "push_endpoint_arn")
  private String pushEndpointArn;

  @ElementCollection
  @CollectionTable(name = "patient_care_team", joinColumns = @JoinColumn(name = "patient_id"))
  @Column(name = "user_id", nullable = false)
  private Set<UUID> careTeam = new HashSet<>();

  @Version
  private Long version;

  protected Patient() {}

  public Patient(UUID id, String fullName, LocalDate dateOfBirth) {
    this.id = id;
    this.fullName = fullName;
    this.dateOfBirth = dateOfBirth;
  }

  public UUID getId() { return id; }
  public String getNationalId() { return nationalId; }
  public void setNationalId(String nationalId) { this.nationalId = nationalId; }
  public String getFullName() { return fullName; }
  public void setFullName(String fullName) { this.fullName = fullName; }
  public LocalDate getDateOfBirth() { return dateOfBirth; }
  public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
  public String getDepartment() { return department; }
  public void setDepartment(String department) { this.department = department; }
  public Sensitivity getSensitivity() { return sensitivity; }
  public void setSensitivity(Sensitivity sensitivity) { this.sensitivity = sensitivity; }
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
  public Set<UUID> getCareTeam() { return careTeam; }
  public Long getVersion() { return version; }
}
