package com.hms.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * mode = log (default: channels only write to the log) | aws (SES for email, SNS for SMS and push).
 */
@ConfigurationProperties(prefix = "hms.notification")
public record NotificationProperties(
    @DefaultValue("log") String mode,
    @DefaultValue("no-reply@hms.example.com") String fromEmail,
    @DefaultValue("pharmacy-alerts@hms.example.com") String pharmacyAlertEmail) {}
