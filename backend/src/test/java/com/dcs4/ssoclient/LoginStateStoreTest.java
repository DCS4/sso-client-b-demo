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
    String state = store.begin(session, "B_PAGE_01", "/pages/orders");

    assertTrue(state.length() >= 16);
    assertEquals("B_PAGE_01", store.consume(session, state).getPageCode());
    assertNull(store.consume(session, state));
  }
}
