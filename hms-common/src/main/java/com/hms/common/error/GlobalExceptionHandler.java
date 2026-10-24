package com.hms.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Deliberately no catch-all: AccessDeniedException must reach Spring Security so it becomes a 403. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessValidationException.class)
  public ProblemDetail validation(BusinessValidationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }

  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail notFound(NotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(DownstreamUnavailableException.class)
  public ProblemDetail downstream(DownstreamUnavailableException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
  }
}
