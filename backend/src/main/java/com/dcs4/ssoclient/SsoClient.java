package com.dcs4.ssoclient;

import com.dcs4.ssoclient.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.SsoModels.ApiEnvelope;
import com.dcs4.ssoclient.SsoModels.LogoutData;
import com.dcs4.ssoclient.SsoModels.TokenData;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 外部系统后端对 SSO V2 的唯一 HTTP 封装。
 *
 * <p>业务 Controller 不应自行拼接 client_secret 或 Token 请求；集中封装可以保证
 * 密钥永远不进入浏览器，并统一执行错误处理。</p>
 */
@Service
public class SsoClient {
  private final SsoConfig config;
  private final ObjectMapper json;
  private final RestTemplate http;

  public SsoClient(SsoConfig config, ObjectMapper json, RestTemplateBuilder builder) {
    this.config = config;
    this.json = json;
    this.http =
        builder
            .setConnectTimeout(config.getConnectTimeout())
            .setReadTimeout(config.getReadTimeout())
            .build();
  }

  /** 授权地址只包含公开参数，不包含 client_secret 或任何 Token。 */
  public String authorizeUrl(String state, String pageCode) {
    config.requireBackendCredentials();
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromHttpUrl(config.endpoint("/authorize"))
            .queryParam("response_type", "code")
            .queryParam("client_id", config.getClientId())
            .queryParam("redirect_uri", config.getCallbackUrl())
            .queryParam("state", state);
    if (config.isPageControlled()) {
      uri.queryParam("page_code", pageCode);
    }
    return uri.build().encode().toUriString();
  }

  public TokenData exchangeCode(String code) {
    MultiValueMap<String, String> form = baseTokenForm("authorization_code");
    form.add("code", code);
    form.add("redirect_uri", config.getCallbackUrl());
    return requireData(postForm("/token", form), TokenData.class, "SSO 未返回 Token");
  }

  public TokenData refresh(String refreshToken) {
    MultiValueMap<String, String> form = baseTokenForm("refresh_token");
    form.add("refresh_token", refreshToken);
    return requireData(postForm("/token", form), TokenData.class, "SSO 未返回刷新 Token");
  }

  public ActiveTokenData checkAccessToken(String accessToken, String pageCode) {
    config.requireBackendCredentials();
    Map<String, String> body = new LinkedHashMap<String, String>();
    body.put("clientId", config.getClientId());
    body.put("clientSecret", config.getClientSecret());
    body.put("accessToken", accessToken);
    if (config.isPageControlled()) {
      body.put("pageCode", pageCode);
    }
    ApiEnvelope envelope = postJson("/checkAccessToken", body);
    if (envelope.getData() == null || envelope.getData().isNull()) {
      return new ActiveTokenData();
    }
    try {
      // checkAccessToken 的业务 code=500 是“无效”而不是网络异常，按 active=false 处理。
      return json.treeToValue(envelope.getData(), ActiveTokenData.class);
    } catch (Exception e) {
      throw new SsoClientException("SSO Token 校验响应格式错误", false, e);
    }
  }

  public UserInfo getUserInfo(String accessToken) {
    config.requireBackendCredentials();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", config.getClientId());
    form.add("client_secret", config.getClientSecret());
    form.add("tokenValue", accessToken);
    return requireData(
        postForm("/getUserInfoByOauth2", form), UserInfo.class, "SSO 未返回用户信息");
  }

  /** 注销的是当前页面 Token family，不会退出 Portal 全局 SID。 */
  public boolean logout(String accessToken) {
    config.requireBackendCredentials();
    Map<String, String> body = new LinkedHashMap<String, String>();
    body.put("clientId", config.getClientId());
    body.put("clientSecret", config.getClientSecret());
    body.put("accessToken", accessToken);
    LogoutData data = requireData(postJson("/logout", body), LogoutData.class, "SSO 未返回注销结果");
    return data.isRevoked();
  }

  private MultiValueMap<String, String> baseTokenForm(String grantType) {
    config.requireBackendCredentials();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", grantType);
    form.add("client_id", config.getClientId());
    form.add("client_secret", config.getClientSecret());
    return form;
  }

  private ApiEnvelope postForm(String endpoint, MultiValueMap<String, String> form) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    return exchange(endpoint, new HttpEntity<MultiValueMap<String, String>>(form, headers));
  }

  private ApiEnvelope postJson(String endpoint, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return exchange(endpoint, new HttpEntity<Map<String, String>>(body, headers));
  }

  private ApiEnvelope exchange(String endpoint, HttpEntity<?> entity) {
    try {
      URI uri = URI.create(config.endpoint(endpoint));
      ResponseEntity<String> response = http.exchange(uri, HttpMethod.POST, entity, String.class);
      ApiEnvelope envelope = json.readValue(response.getBody(), ApiEnvelope.class);
      if (envelope == null) {
        throw new SsoClientException("SSO 返回空响应", false);
      }
      return envelope;
    } catch (RestClientResponseException e) {
      boolean unavailable = e.getRawStatusCode() >= 500;
      throw new SsoClientException(
          unavailable ? "SSO 服务暂不可用" : responseMessage(e.getResponseBodyAsString()),
          unavailable,
          e);
    } catch (ResourceAccessException e) {
      throw new SsoClientException("无法连接 SSO 服务", true, e);
    } catch (SsoClientException e) {
      throw e;
    } catch (Exception e) {
      throw new SsoClientException("无法解析 SSO 响应", false, e);
    }
  }

  private <T> T requireData(ApiEnvelope envelope, Class<T> type, String emptyMessage) {
    if (envelope.getCode() != 200) {
      throw new SsoClientException(
          StringUtils.hasText(envelope.getMsg()) ? envelope.getMsg() : "SSO 请求失败", false);
    }
    JsonNode data = envelope.getData();
    if (data == null || data.isNull()) {
      throw new SsoClientException(emptyMessage, false);
    }
    try {
      return json.treeToValue(data, type);
    } catch (Exception e) {
      throw new SsoClientException("SSO 响应字段不完整", false, e);
    }
  }

  private String responseMessage(String body) {
    try {
      ApiEnvelope envelope = json.readValue(body, ApiEnvelope.class);
      if (envelope != null && StringUtils.hasText(envelope.getMsg())) {
        return envelope.getMsg();
      }
      JsonNode node = json.readTree(body);
      if (node != null && node.hasNonNull("error_description")) {
        return node.get("error_description").asText();
      }
    } catch (Exception ignored) {
      // 不把可能包含协议数据的原始响应直接写入异常或日志。
    }
    return "SSO 拒绝请求";
  }

  public static class SsoClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean unavailable;

    public SsoClientException(String message, boolean unavailable) {
      super(message);
      this.unavailable = unavailable;
    }

    public SsoClientException(String message, boolean unavailable, Throwable cause) {
      super(message, cause);
      this.unavailable = unavailable;
    }

    public boolean isUnavailable() {
      return unavailable;
    }
  }
}
