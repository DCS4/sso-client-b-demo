package com.dcs4.ssoclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dcs4.ssoclient.SsoModels.StoredToken;
import com.dcs4.ssoclient.SsoModels.TokenData;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class PageTokenStoreTest {
  @Test
  void tokensAreSeparatedByPageCodeInsideServerSession() throws Exception {
    PageTokenStore store = new PageTokenStore();
    MockHttpSession session = new MockHttpSession();
    StoredToken first = token("access-1", "refresh-1", "B_PAGE_01");
    StoredToken second = token("access-2", "refresh-2", "B_PAGE_02");

    store.put(session, "B_PAGE_01", first);
    store.put(session, "B_PAGE_02", second);

    assertEquals("access-1", store.get(session, "B_PAGE_01").getAccessToken());
    assertEquals("access-2", store.get(session, "B_PAGE_02").getAccessToken());
    store.remove(session, "B_PAGE_01");
    assertNull(store.get(session, "B_PAGE_01"));
  }

  private StoredToken token(String access, String refresh, String page) throws Exception {
    TokenData data = new TokenData();
    set(data, "accessToken", access);
    set(data, "refreshToken", refresh);
    set(data, "pageCode", page);
    set(data, "openid", "user-1");
    set(data, "expiresIn", Long.valueOf(60));
    set(data, "refreshExpiresIn", Long.valueOf(120));
    return new StoredToken(data, 1000L);
  }

  private void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
