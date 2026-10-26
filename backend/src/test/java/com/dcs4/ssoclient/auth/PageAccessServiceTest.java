package com.dcs4.ssoclient.auth;

import com.dcs4.ssoclient.config.SsoConfig;
import com.dcs4.ssoclient.sso.SsoGateway;
import com.dcs4.ssoclient.auth.PageCatalog.Page;
import com.dcs4.ssoclient.sso.SsoGateway.SsoClientException;
import com.dcs4.ssoclient.sso.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.sso.SsoModels.StoredToken;
import com.dcs4.ssoclient.sso.SsoModels.TokenData;
import com.dcs4.ssoclient.sso.SsoModels.UserInfo;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
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
    tokens.put(request.getSession(), page.getCode(), new StoredToken(token("old", "refresh-old"), System.currentTimeMillis() / 1000L));
    when(gateway.checkAccessToken("old", page.getCode())).thenReturn(active("user-1"));

    assertTrue(access.enter(request, page).isAllowed());
    assertTrue(access.enter(request, page).isAllowed());
    verify(gateway, times(2)).checkAccessToken("old", page.getCode());
    verify(gateway, never()).authorizeUrl(anyString(), anyString());
  }

  @Test
  void expiredAccessUsesRefreshOnceAndReplacesBothTokens() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    tokens.put(request.getSession(), page.getCode(), new StoredToken(token("old", "refresh-old"), System.currentTimeMillis() / 1000L));
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
    UserInfo user = enabledUser("user-1");
    when(gateway.getUserInfo("access-1")).thenReturn(user);

    assertEquals(URI.create(page.getPath()), access.completeCallback(request, "one-time-code", state, null));
    assertEquals("access-1", tokens.get(request.getSession(), page.getCode()).getAccessToken());
    assertSame(user, access.currentUser(request.getSession()));
    assertThrows(ResponseStatusException.class,
        () -> access.completeCallback(request, "one-time-code", state, null));
    verify(gateway, times(1)).exchangeCode("one-time-code");
  }

  /** SSO 没有返回完整 Token 时，回调必须按协议失败，而不是抛出空指针。 */
  @Test
  void callbackWithMissingTokenReturnsBadGateway() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    String state = states.begin(request.getSession(), page.getCode(), page.getPath());
    when(gateway.exchangeCode("empty-token-code")).thenReturn(null);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> access.completeCallback(request, "empty-token-code", state, null));

    assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
  }

  /** 同一 B 会话换账号后，不能同时保留上一个账号的页面 Token。 */
  @Test
  void callbackForDifferentUserClearsEarlierPageTokens() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    Page secondPage = new PageCatalog().requireByCode("B_PAGE_02");

    String firstState = states.begin(request.getSession(), page.getCode(), page.getPath());
    when(gateway.exchangeCode("code-user-1")).thenReturn(token("access-1", "refresh-1"));
    when(gateway.checkAccessToken("access-1", page.getCode())).thenReturn(active("user-1"));
    when(gateway.getUserInfo("access-1")).thenReturn(enabledUser("user-1"));
    access.completeCallback(request, "code-user-1", firstState, null);

    String secondState = states.begin(request.getSession(), secondPage.getCode(), secondPage.getPath());
    TokenData secondIssued = token("access-2", "refresh-2");
    secondIssued.setPageCode(secondPage.getCode());
    secondIssued.setOpenid("user-2");
    ActiveTokenData secondChecked = active("user-2");
    secondChecked.setPageCode(secondPage.getCode());
    UserInfo secondUser = enabledUser("user-2");
    when(gateway.exchangeCode("code-user-2")).thenReturn(secondIssued);
    when(gateway.checkAccessToken("access-2", secondPage.getCode())).thenReturn(secondChecked);
    when(gateway.getUserInfo("access-2")).thenReturn(secondUser);

    assertEquals(URI.create(secondPage.getPath()),
        access.completeCallback(request, "code-user-2", secondState, null));
    assertNull(tokens.get(request.getSession(), page.getCode()));
    assertEquals("access-2", tokens.get(request.getSession(), secondPage.getCode()).getAccessToken());
    assertSame(secondUser, access.currentUser(request.getSession()));
    verify(gateway, never()).logout(anyString());
  }

  @Test
  void gatewayUnavailableMustNotAuthorizeOrRedirectAsIfTokenExpired() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    tokens.put(request.getSession(), page.getCode(), new StoredToken(token("old", "refresh-old"), System.currentTimeMillis() / 1000L));
    when(gateway.checkAccessToken("old", page.getCode()))
        .thenThrow(new SsoClientException("SSO unavailable", true));

    ResponseStatusException error =
        assertThrows(ResponseStatusException.class, () -> access.enter(request, page));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    verify(gateway, never()).authorizeUrl(anyString(), anyString());
    verify(gateway, never()).refresh(anyString());
  }

  @Test
  void ssoOnlyReusesOneTokenAndStillChecksEachPage() {
    when(config.isPageControlled()).thenReturn(false);
    when(config.tokenKey(anyString())).thenReturn("__SSO_ONLY__");
    MockHttpServletRequest request = new MockHttpServletRequest();
    TokenData shared = token("shared-access", "shared-refresh");
    shared.setPageCode(null);
    tokens.put(request.getSession(), "__SSO_ONLY__", new StoredToken(shared, System.currentTimeMillis() / 1000L));
    ActiveTokenData checked = active("user-1");
    checked.setPageCode(null);
    when(gateway.checkAccessToken(eq("shared-access"), anyString())).thenReturn(checked);

    Page otherPage = new PageCatalog().requireByCode("B_PAGE_02");
    assertTrue(access.enter(request, page).isAllowed());
    assertTrue(access.enter(request, otherPage).isAllowed());
    assertEquals(1, tokens.size(request.getSession()));
    verify(gateway).checkAccessToken("shared-access", "B_PAGE_01");
    verify(gateway).checkAccessToken("shared-access", "B_PAGE_02");
    verify(gateway, never()).authorizeUrl(anyString(), anyString());
  }

  @Test
  void systemLogoutSkipsEmptyEntriesAndRevokesEachTokenOnce() {
    MockHttpSession session = new MockHttpSession();
    StoredToken shared = new StoredToken(token("same-access", "same-refresh"), System.currentTimeMillis() / 1000L);
    tokens.put(session, "B_PAGE_01", shared);
    tokens.put(session, "B_PAGE_02", shared);
    tokens.put(session, "B_PAGE_03", null);
    when(gateway.logout("same-access")).thenReturn(true);

    PageAccessService.LogoutSummary summary = access.logoutSystem(session);

    assertEquals(1, summary.getAttempted());
    assertEquals(1, summary.getConfirmed());
    assertTrue(session.isInvalid());
    verify(gateway, times(1)).logout("same-access");
  }

  /** 刷新请求超时后，不确定远端是否已消费旧值；下一次必须重新授权。 */
  @Test
  void refreshTimeoutClearsLocalTokenAndDoesNotReplayOldRefreshToken() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    StoredToken old = new StoredToken(token("old", "refresh-old"), System.currentTimeMillis() / 1000L);
    tokens.put(request.getSession(), page.getCode(), old);
    when(gateway.checkAccessToken("old", page.getCode())).thenReturn(new ActiveTokenData());
    when(gateway.refresh("refresh-old")).thenThrow(new SsoClientException("refresh timeout", true));
    when(gateway.authorizeUrl(anyString(), eq(page.getCode())))
        .thenReturn("http://sso.example.test/authorize");

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> access.enter(request, page));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    assertNull(tokens.get(request.getSession(), page.getCode()));
    verify(gateway, never()).authorizeUrl(anyString(), anyString());

    PageAccessService.AccessResult next = access.enter(request, page);
    assertFalse(next.isAllowed());
    assertEquals(URI.create("http://sso.example.test/authorize"), next.getRedirect());
    verify(gateway, times(1)).refresh("refresh-old");
  }

  /** 已轮换成功后，新 AccessToken 校验超时也不能让旧 RefreshToken 留给下一次请求。 */
  @Test
  void newTokenCheckTimeoutClearsLocalTokenAndDoesNotReplayOldRefreshToken() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    StoredToken old = new StoredToken(token("old", "refresh-old"), System.currentTimeMillis() / 1000L);
    tokens.put(request.getSession(), page.getCode(), old);
    when(gateway.checkAccessToken("old", page.getCode())).thenReturn(new ActiveTokenData());
    when(gateway.refresh("refresh-old")).thenReturn(token("new", "refresh-new"));
    when(gateway.checkAccessToken("new", page.getCode()))
        .thenThrow(new SsoClientException("check timeout", true));
    when(gateway.authorizeUrl(anyString(), eq(page.getCode())))
        .thenReturn("http://sso.example.test/authorize");

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> access.enter(request, page));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    assertNull(tokens.get(request.getSession(), page.getCode()));
    verify(gateway, never()).authorizeUrl(anyString(), anyString());

    PageAccessService.AccessResult next = access.enter(request, page);
    assertFalse(next.isAllowed());
    assertEquals(URI.create("http://sso.example.test/authorize"), next.getRedirect());
    verify(gateway, times(1)).refresh("refresh-old");
    verify(gateway, times(1)).checkAccessToken("new", page.getCode());
  }

  /** 刷新响应绑定异常时，旧 RefreshToken 也可能已消费，下一次必须重新授权。 */
  @Test
  void malformedRefreshResponseClearsLocalTokenAndDoesNotReplayOldRefreshToken() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    StoredToken old = new StoredToken(token("old", "refresh-old"), System.currentTimeMillis() / 1000L);
    tokens.put(request.getSession(), page.getCode(), old);
    TokenData malformed = token("new", "refresh-new");
    malformed.setClientId("wrong-client");
    when(gateway.checkAccessToken("old", page.getCode())).thenReturn(new ActiveTokenData());
    when(gateway.refresh("refresh-old")).thenReturn(malformed);
    when(gateway.authorizeUrl(anyString(), eq(page.getCode())))
        .thenReturn("http://sso.example.test/authorize");

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> access.enter(request, page));

    assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
    assertNull(tokens.get(request.getSession(), page.getCode()));

    PageAccessService.AccessResult next = access.enter(request, page);
    assertFalse(next.isAllowed());
    assertEquals(URI.create("http://sso.example.test/authorize"), next.getRedirect());
    verify(gateway, times(1)).refresh("refresh-old");
    verify(gateway, never()).checkAccessToken("new", page.getCode());
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

  private UserInfo enabledUser(String id) {
    UserInfo user = new UserInfo();
    user.setId(id);
    user.setEnableStatus(1);
    return user;
  }
}
