package com.dcs4.ssoclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class SsoClient {
  private final SsoConfig config;
  private final TokenVerifier verifier;
  private final ObjectMapper json;
  private final RestTemplate http =
      new RestTemplateBuilder()
          .setConnectTimeout(Duration.ofSeconds(2))
          .setReadTimeout(Duration.ofSeconds(2))
          .build();

  public SsoClient(SsoConfig c, TokenVerifier v, ObjectMapper j) {
    config = c;
    verifier = v;
    json = j;
  }

  public JWTClaimsSet signed(
      String endpoint, Map<String, String> body, String field, String kind, String sid)
      throws Exception {
    String nonce = Protocol.random();
    return verifier.verify(post(endpoint, body, nonce).get(field), kind, nonce, sid);
  }

  @SuppressWarnings("unchecked")
  public Map<String, String> post(String endpoint, Map<String, String> body, String nonce)
      throws Exception {
    if (config.secret.isEmpty()) throw new IllegalStateException("请配置 SSO_CLIENT_SECRET");
    URI uri = URI.create(config.baseUrl + endpoint);
    byte[] bytes = json.writeValueAsBytes(body);
    String timestamp = Long.toString(System.currentTimeMillis() / 1000);
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-SSO-Client", config.clientId);
    h.set("X-SSO-Timestamp", timestamp);
    h.set("X-SSO-Nonce", nonce);
    h.set(
        "X-SSO-Signature",
        Protocol.sign(config.secret, uri.getRawPath(), config.clientId, timestamp, nonce, bytes));
    ResponseEntity<String> result =
        http.exchange(uri, HttpMethod.POST, new HttpEntity<byte[]>(bytes, h), String.class);
    if (!result.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("SSO 未返回成功状态");
    return json.readValue(result.getBody(), Map.class);
  }
}
