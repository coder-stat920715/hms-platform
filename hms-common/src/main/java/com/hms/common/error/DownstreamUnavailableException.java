package com.hms.common.error;

public class DownstreamUnavailableException extends RuntimeException {
  public DownstreamUnavailableException(String downstream, Throwable cause) {
    super(downstream + " unavailable", cause);
  }
}
