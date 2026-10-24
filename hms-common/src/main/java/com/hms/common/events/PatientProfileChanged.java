package com.hms.common.events;

import java.util.List;
import java.util.UUID;

/** Contact profile projected to notification-service. Contains no clinical data. */
public record PatientProfileChanged(UUID patientId, String fullName, String email, String phone,
                                    String locale, List<String> channels, String pushEndpointArn) {}
