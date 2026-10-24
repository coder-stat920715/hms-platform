package com.hms.common.error;

public class BusinessValidationException extends RuntimeException {
  public BusinessValidationException(String message) { super(message); }
}
