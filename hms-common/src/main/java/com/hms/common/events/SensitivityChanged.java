package com.hms.common.events;

import com.hms.common.domain.Sensitivity;
import java.util.UUID;

public record SensitivityChanged(UUID patientId, Sensitivity sensitivity) {}
