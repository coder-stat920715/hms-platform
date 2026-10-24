package com.hms.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record BreakGlassRequest(
    @NotNull UUID patientId,
    @NotBlank @Size(min = 10, max = 500) String reason) {}
