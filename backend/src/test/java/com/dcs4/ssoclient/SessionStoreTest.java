package com.dcs4.ssoclient;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SessionStoreTest {
  @Test
  void revokeAllAndRejectLateCallback() {
    SessionStore store = new SessionStore();
    MockHttpSession a = new MockHttpSession(), b = new MockHttpSession();
    store.register("sid", a);
    store.register("sid", b);
    store.revoke("sid");
    store.revoke("sid");
    assertTrue(a.isInvalid());
    assertTrue(b.isInvalid());
    assertThrows(
        IllegalArgumentException.class, () -> store.register("sid", new MockHttpSession()));
  }

  @Test
  void rejectReplay() {
    SessionStore s = new SessionStore();
    long exp = System.currentTimeMillis() / 1000 + 60;
    s.once("a", exp);
    assertThrows(IllegalArgumentException.class, () -> s.once("a", exp));
  }
}
