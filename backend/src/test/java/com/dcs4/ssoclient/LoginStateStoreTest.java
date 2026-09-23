package com.dcs4.ssoclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class LoginStateStoreTest {
  @Test
  void stateIsRandomAndCanOnlyBeConsumedOnce() {
    LoginStateStore store = new LoginStateStore();
    MockHttpSession session = new MockHttpSession();
    long before = System.currentTimeMillis();
    String state = store.begin(session, "B_PAGE_01", "/pages/orders");
    long after = System.currentTimeMillis();

    assertTrue(state.length() >= 16);
    LoginStateStore.LoginTransaction transaction = store.consume(session, state);
    assertEquals("B_PAGE_01", transaction.getPageCode());
    assertTrue(transaction.getStartedAtMillis() >= before);
    assertTrue(transaction.getStartedAtMillis() <= after);
    assertEquals(10L * 60L * 1000L,
        transaction.getExpiresAt() - transaction.getStartedAtMillis());
    assertNull(store.consume(session, state));
  }
}
