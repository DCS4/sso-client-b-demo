package com.dcs4.ssoclient;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TokenVerifierTest {
  @TempDir Path dir;
  RSAKey key;
  TokenVerifier verifier;

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test").generate();
    Path path = dir.resolve("public.json");
    Files.write(path, new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8));
    SsoConfig c = new SsoConfig();
    c.trustedJwks = path.toString();
    c.issuer = "issuer";
    c.clientId = "b";
    verifier = new TokenVerifier(c);
  }

  JWTClaimsSet.Builder claims() {
    long now = System.currentTimeMillis();
    return new JWTClaimsSet.Builder()
        .issuer("issuer")
        .audience("b")
        .subject("user")
        .claim("sid", "sid")
        .claim("username", "name")
        .claim("auth_time", now / 1000)
        .claim("session_expires_at", now / 1000 + 3600)
        .claim("request_nonce", "nonce")
        .issueTime(new Date(now))
        .expirationTime(new Date(now + 60000))
        .jwtID("id");
  }

  String sign(JWTClaimsSet c, RSAKey k) throws Exception {
    SignedJWT j =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.getKeyID()).build(), c);
    j.sign(new RSASSASigner(k));
    return j.serialize();
  }

  @Test
  void validIdentity() throws Exception {
    assertEquals(
        "user",
        verifier.verify(sign(claims().build(), key), "identity", "nonce", null).getSubject());
  }

  @Test
  void rejectWrongAudienceNonceExpiredAndKey() throws Exception {
    assertThrows(
        Exception.class,
        () ->
            verifier.verify(
                sign(claims().audience("other").build(), key), "identity", "nonce", null));
    assertThrows(
        Exception.class,
        () -> verifier.verify(sign(claims().build(), key), "identity", "wrong", null));
    assertThrows(
        Exception.class,
        () ->
            verifier.verify(
                sign(claims().expirationTime(new Date(0)).build(), key),
                "identity",
                "nonce",
                null));
    RSAKey other = new RSAKeyGenerator(2048).keyID("unknown").generate();
    assertThrows(
        Exception.class,
        () -> verifier.verify(sign(claims().build(), other), "identity", "nonce", null));
  }

  @Test
  void logoutRequiresEventsAndForbidsNonce() throws Exception {
    assertThrows(
        Exception.class, () -> verifier.verify(sign(claims().build(), key), "logout", null, null));
    JWTClaimsSet.Builder b =
        claims()
            .claim(
                "events",
                Collections.singletonMap(
                    "http://schemas.openid.net/event/backchannel-logout", Collections.emptyMap()));
    assertEquals(
        "sid", verifier.verify(sign(b.build(), key), "logout", null, null).getStringClaim("sid"));
    assertThrows(
        Exception.class,
        () -> verifier.verify(sign(b.claim("nonce", "x").build(), key), "logout", null, null));
  }

  @Test
  void statusBoundToSession() throws Exception {
    String t = sign(claims().claim("client_id", "b").claim("active", true).build(), key);
    assertTrue(verifier.verify(t, "status", "nonce", "sid").getBooleanClaim("active"));
    assertThrows(Exception.class, () -> verifier.verify(t, "status", "nonce", "other"));
  }
}
