package com.hms.billing.service;

import com.hms.common.error.DownstreamUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Insurance eligibility check. With no hms.billing.insurance.base-url configured every claim is approved
 * (local development). Retry (outer) wraps CircuitBreaker (inner); the breaker's fallback converts failures into
 * DownstreamUnavailableException, which the retry policy then retries (see application.yml).
 */
@Component
public class InsuranceClient {

  private final RestClient http;

  public InsuranceClient(@Value("${hms.billing.insurance.base-url:}") String baseUrl, RestClient.Builder builder) {
    this.http = baseUrl == null || baseUrl.isBlank() ? null : builder.baseUrl(baseUrl).build();
  }

  @Retry(name = "insurance")
  @CircuitBreaker(name = "insurance", fallbackMethod = "unavailable")
  public boolean verify(UUID patientId, BigDecimal amount) {
    if (http == null) return true;
    try {
      Map<?, ?> response = http.post().uri("/verify")
          .body(Map.of("patientId", patientId.toString(), "amount", amount))
          .retrieve().body(Map.class);
      return response != null && Boolean.TRUE.equals(response.get("approved"));
    } catch (HttpClientErrorException e) {
      return false;                                   // 4xx: the insurer said no; not an availability problem
    }
  }

  @SuppressWarnings("unused")
  public boolean unavailable(UUID patientId, BigDecimal amount, Throwable t) {
    throw new DownstreamUnavailableException("insurance", t);
  }
}
