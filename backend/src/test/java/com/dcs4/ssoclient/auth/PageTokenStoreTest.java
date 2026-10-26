package com.dcs4.ssoclient.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dcs4.ssoclient.sso.SsoModels.StoredToken;
import com.dcs4.ssoclient.sso.SsoModels.TokenData;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class PageTokenStoreTest {
  @Test
  void tokensAreSeparatedByPageCodeInsideServerSession() {
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

  private StoredToken token(String access, String refresh, String page) {
    TokenData data = new TokenData();
    data.setAccessToken(access);
    data.setRefreshToken(refresh);
    data.setPageCode(page);
    data.setOpenid("user-1");
    data.setExpiresIn(60L);
    data.setRefreshExpiresIn(120L);
    return new StoredToken(data, 1000L);
  }
}
