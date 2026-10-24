package com.hms.patient.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * hms.security.break-glass-enabled: OFF by default. The break-glass signal currently arrives as a request header
 * that a client can set, so enable it only after replacing it with a signed, time-boxed claim (see README).
 * hms.security.trusted-cidrs: networks counted as "inside the hospital" for clinician access.
 */
@ConfigurationProperties(prefix = "hms.security")
public record SecurityPolicyProperties(
    @DefaultValue("false") boolean breakGlassEnabled,
    @DefaultValue({"10.0.0.0/8"}) List<String> trustedCidrs) {}
