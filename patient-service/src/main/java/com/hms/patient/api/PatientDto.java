package com.hms.patient.api;

import com.hms.common.domain.Sensitivity;
import com.hms.patient.domain.Patient;
import java.time.LocalDate;
import java.util.UUID;

public record PatientDto(UUID id, String fullName, LocalDate dateOfBirth, String nationalIdMasked,
                         String department, Sensitivity sensitivity, String email, String phone) {

  public static PatientDto from(Patient p) {
    String nid = p.getNationalId();
    String masked = nid == null ? null : (nid.length() <= 4 ? "****" : "****" + nid.substring(nid.length() - 4));
    return new PatientDto(p.getId(), p.getFullName(), p.getDateOfBirth(), masked, p.getDepartment(),
        p.getSensitivity(), p.getEmail(), p.getPhone());
  }
}
