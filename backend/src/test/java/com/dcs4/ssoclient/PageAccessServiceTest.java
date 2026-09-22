package com.dcs4.ssoclient;

import com.dcs4.ssoclient.PageCatalog.Page;
import com.dcs4.ssoclient.SsoGateway.SsoClientException;
import com.dcs4.ssoclient.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.SsoModels.StoredToken;
import com.dcs4.ssoclient.SsoModels.TokenData;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 等价整理回归：固定回调、服务端状态、实时校验、刷新成对替换及失败关闭。 */
class PageAccessServiceTest {
  private SsoGateway gateway;
  private SsoConfig config;
  private LoginStateStore states;
  private PageTokenStore tokens;
  private PageAccessService access;
  private Page page;

  @BeforeEach
  void setUp() {
    gateway = mock(SsoGateway.class);
    config = mock(SsoConfig.class);
    when(config.isPageControlled()).thenReturn(true);
    when(config.getClientId()).thenReturn("client-b");
    when(config.tokenKey(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    states = new LoginStateStore();
    tokens = new PageTokenStore();
    access = new PageAccessService(config, gateway, states, tokens);
    page = new PageCatalog().requireByCode("B_PAGE_01");
  }

  @Test
  void noTokenRedirectsWithOneTimeStateAndServerRegisteredTarget() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    when(gateway.authorizeUrl(anyString(), eq(page.getCode())))
        .thenReturn("http://sso.example.test/authorize");

    PageAccessService.AccessResult decision = access.enter(request, page);

    assertFalse(decision.isAllowed());
    assertEquals(URI.create("http://sso.example.test/authorize"), decision.getRedirect());
    ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
    verify(gateway).authorizeUrl(state.capture(), eq(page.getCode()));
    assertEquals(page.getPath(), states.consume(request.getSession(), state.getValue()).getTargetPath());
    assertNull(states.consume(request.getSession(), state.getValue()));
  }

  @Test
  void cachedTokenIsCheckedOnEveryPageRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    tokens.put(request.getSession(), page.getCode(), new StoredToken(token("old", "refresh-old"), 1000L));
    when(gateway.checkAccessToken("old", page.getCode())).thenReturn(active("user-1"));

    assertTrue(access.enter(request, page).isAllowed());
    assertTrue(access.enter(request, page).isAllowed());
    verify(gateway, times(2)).checkAccessToken("old", page.getCode());
    verify(gateway, never()).authorizeUrl(anyString(), anyString());
  }

  @Test
  void expiredAccessUsesRefreshOnceAndReplacesBothTokens() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    tokens.put(request.getSession(), page.getCode(), new StoredToken(token("old", "refresh-old"), 1000L));
    when(gateway.checkAccessToken("old", page.getCode())).thenReturn(new ActiveTokenData());
    when(gateway.refresh("refresh-old")).thenReturn(token("new", "refresh-new"));
    when(gateway.checkAccessToken("new", page.getCode())).thenReturn(active("user-1"));

    assertTrue(access.enter(request, page).isAllowed());
    StoredToken replacement = tokens.get(request.getSession(), page.getCode());
    assertEquals("new", replacement.getAccessToken());
    assertEquals("refresh-new", replacement.getRefreshToken());
    verify(gateway, times(1)).refresh("refresh-old");
    verify(gateway).checkAccessToken("new", page.getCode());
  }

  @Test
  void callbackConsumesStateAndStoresOnlyServerSideToken() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    String state = states.begin(request.getSession(), page.getCode(), page.getPath());
    TokenData issued = token("access-1", "refresh-1");
    when(gateway.exchangeCode("one-time-code")).thenReturn(issued);
    when(gateway.checkAccessToken("access-1", page.getCode())).thenReturn(active("user-1"));
    UserInfo user = new UserInfo();
    user.setId("user-1");
    user.setEnableStatus(1);
    when(gateway.getUserInfo("access-1")).thenReturn(user);

    assertEquals(URI.create(page.getPath()), access.completeCallback(request, "one-time-code", state, null));
    assertEquals("access-1", tokens.get(request.getSession(), page.getCode()).getAccessToken());
    assertSame(user, access.currentUser(request.getSession()));
    assertThrows(ResponseStatusException.class,
        () -> access.completeCallback(request, "one-time-code", state, null));
    verify(gateway, times(1)).exchangeCode("one-time-code");
  }

  @Test
  void gatewayUnavailableMustNotAuthorizeOrRedirectAsIfTokenExpired() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    tokens.put(request.getSession(), page.getCode(), new StoredToken(token("old", "refresh-old"), 1000L));
    when(gateway.checkAccessToken("old", page.getCode()))
        .thenThrow(new SsoClientException("SSO unavailable", true));

    ResponseStatusException error =
        assertThrows(ResponseStatusException.class, () -> access.enter(request, page));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    verify(gateway, never()).authorizeUrl(anyString(), anyString());
    verify(gateway, never()).refresh(anyString());
  }

  private TokenData token(String accessToken, String refreshToken) {
    TokenData token = new TokenData();
    token.setAccessToken(accessToken);
    token.setRefreshToken(refreshToken);
    token.setClientId("client-b");
    token.setPageCode(page.getCode());
    token.setOpenid("user-1");
    token.setExpiresIn(3600L);
    token.setRefreshExpiresIn(7200L);
    return token;
  }

  private ActiveTokenData active(String uid) {
    ActiveTokenData token = new ActiveTokenData();
    token.setActive(true);
    token.setClientId("client-b");
    token.setPageCode(page.getCode());
    token.setUid(uid);
    return token;
  }
}
