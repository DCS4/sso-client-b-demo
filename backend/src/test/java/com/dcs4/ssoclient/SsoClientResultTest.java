package com.dcs4.ssoclient;

import com.dcs4.ssoclient.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.SsoModels.TokenData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 客户端 V2 响应采用 Result.success/message/result，禁止从失败外壳中放行 Token。 */
class SsoClientResultTest {
  private SsoClient sso;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    SsoConfig config = mock(SsoConfig.class);
    when(config.getConnectTimeout()).thenReturn(Duration.ofSeconds(2));
    when(config.getReadTimeout()).thenReturn(Duration.ofSeconds(2));
    when(config.getClientId()).thenReturn("client-b");
    when(config.getClientSecret()).thenReturn("secret");
    when(config.getCallbackUrl()).thenReturn("http://127.0.0.1:18080/api/auth/callback");
    when(config.endpoint("/token")).thenReturn("http://127.0.0.1:19089/oauth2Server/oauth2/token");
    when(config.endpoint("/checkAccessToken")).thenReturn("http://127.0.0.1:19089/oauth2Server/oauth2/checkAccessToken");
    sso = new SsoClient(config, new ObjectMapper(), new RestTemplateBuilder());
    server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(sso, "http")).build();
  }

  @Test
  void tokenExchangeReadsResultInsteadOfLegacyData() {
    server.expect(requestTo("http://127.0.0.1:19089/oauth2Server/oauth2/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"success\":true,\"code\":200,\"message\":\"\","
            + "\"result\":{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\","
            + "\"client_id\":\"client-b\",\"openid\":\"1001\"}}", MediaType.APPLICATION_JSON));
    TokenData token = sso.exchangeCode("one-time-code");
    assertEquals("access-1", token.getAccessToken());
    assertEquals("refresh-1", token.getRefreshToken());
    server.verify();
  }

  @Test
  void rejectsBusinessFailureEvenIfResponseClaimsActiveTrue() {
    server.expect(requestTo("http://127.0.0.1:19089/oauth2Server/oauth2/checkAccessToken"))
        .andRespond(withSuccess("{\"success\":false,\"code\":500,\"message\":\"Token无效\","
            + "\"result\":{\"active\":true,\"uid\":\"1001\"}}", MediaType.APPLICATION_JSON));
    ActiveTokenData checked = sso.checkAccessToken("invalid", "B_PAGE_01");
    assertFalse(checked.isActive());
    server.verify();
  }

  @Test
  void doesNotAcceptLegacyEnvelopeWithoutSuccessFlag() {
    server.expect(requestTo("http://127.0.0.1:19089/oauth2Server/oauth2/checkAccessToken"))
        .andRespond(withSuccess("{\"code\":200,\"msg\":\"ok\","
            + "\"data\":{\"active\":true}}", MediaType.APPLICATION_JSON));
    assertThrows(SsoClient.SsoClientException.class, () -> sso.checkAccessToken("invalid", "B_PAGE_01"));
    server.verify();
  }
}
