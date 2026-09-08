package com.dcs4.ssoclient;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProtocolTest {
  @Test
  void rfc7636Vector() throws Exception {
    assertEquals(
        "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        Protocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
  }

  @Test
  void bodyAndPathAreBound() throws Exception {
    String a = Protocol.sign("secret", "/sso/v1/exchange", "b", "123", "n", new byte[0]);
    assertNotEquals(a, Protocol.sign("secret", "/sso/v1/logout", "b", "123", "n", new byte[0]));
    assertNotEquals(
        a, Protocol.sign("secret", "/sso/v1/exchange", "b", "123", "n", new byte[] {1}));
  }
}
