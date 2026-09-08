package com.dcs4.ssoclient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Protocol {
  private Protocol() {}

  public static String random() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return url(b);
  }

  public static String url(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String challenge(String verifier) throws Exception {
    return url(
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  public static String sign(
      String secret, String path, String client, String timestamp, String nonce, byte[] body)
      throws Exception {
    StringBuilder hex = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(body))
      hex.append(String.format("%02x", b & 255));
    String canonical =
        "POST\n" + path + "\n" + client + "\n" + timestamp + "\n" + nonce + "\n" + hex;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return url(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }
}
