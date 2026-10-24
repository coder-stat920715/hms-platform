package com.hms.patient.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Field-level AES-256-GCM encryption for identifiers (e.g. national ID). Layout: IV(12) || ciphertext+tag, Base64.
 * The key (32 bytes, Base64) comes from hms.crypto.key. In production, inject it from KMS/Secrets Manager
 * (envelope encryption with a data key) rather than a static property.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public EncryptedStringConverter(@Value("${hms.crypto.key}") String base64Key) {
    byte[] raw = Base64.getDecoder().decode(base64Key);
    if (raw.length != 32) throw new IllegalStateException("hms.crypto.key must be 32 bytes (Base64)");
    this.key = new SecretKeySpec(raw, "AES");
  }

  @Override
  public String convertToDatabaseColumn(String plain) {
    if (plain == null) return null;
    try {
      byte[] iv = new byte[IV_LEN];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
    } catch (Exception e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  @Override
  public String convertToEntityAttribute(String stored) {
    if (stored == null) return null;
    try {
      byte[] all = Base64.getDecoder().decode(stored);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
      return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Decryption failed", e);
    }
  }
}
