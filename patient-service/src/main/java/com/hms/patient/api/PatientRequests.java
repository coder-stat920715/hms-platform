package com.hms.patient.api;

import com.hms.common.domain.Sensitivity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PatientRequests {
  private PatientRequests() {}

  public record RegisterPatient(
      /** Optional: the identity-provider subject id of the patient, so ROLE_PATIENT can read their own record. */
      UUID id,
      @NotBlank String fullName,
      @NotNull @Past LocalDate dateOfBirth,
      String nationalId,
      String department,
      @Email String email,
      String phone,
      String locale,
      List<String> channels,
      String pushEndpointArn) {}

  public record UpdatePatient(
      String fullName,
      String department,
      @Email String email,
      String phone,
      String locale,
      List<String> channels,
      String pushEndpointArn) {}

  public record SensitivityRequest(@NotNull Sensitivity sensitivity) {}
}
