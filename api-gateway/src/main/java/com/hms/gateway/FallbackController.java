package com.hms.gateway;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

  @RequestMapping("/fallback/{service}")
  public ResponseEntity<Map<String, String>> fallback(@org.springframework.web.bind.annotation.PathVariable String service) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("error", "service_unavailable", "service", service,
            "message", "The service is temporarily unavailable. Please retry shortly."));
  }
}
