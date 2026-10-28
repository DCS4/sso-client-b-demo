package com.dcs4.ssoclient.sso;

import com.dcs4.ssoclient.config.SsoConfig;
import com.dcs4.ssoclient.sso.SsoGateway.SsoClientException;
import com.dcs4.ssoclient.sso.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.sso.SsoModels.ApiEnvelope;
import com.dcs4.ssoclient.sso.SsoModels.LogoutData;
import com.dcs4.ssoclient.sso.SsoModels.TokenData;
import com.dcs4.ssoclient.sso.SsoModels.UserInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * B 后端访问远端 SSO 的 HTTP 实现。请求字段集中在这里，业务流程只依赖 SsoGateway。
 * 接口地址、表单和 JSON 字段均对应 docs/SSO协议摘要.md。
 */
@Service
public class SsoClient implements SsoGateway {
  private static final Logger log = LoggerFactory.getLogger(SsoClient.class);

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

  /** 【授权入口】固定回调、state 和（受控模式下）page_code 均须由后端决定；URL 不含密钥。 */
  @Override
  public String authorizeUrl(String state, String pageCode) {
    // 1. 使用后端配置生成授权地址，不把密钥放入 URL。
    config.requireBackendCredentials();
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromHttpUrl(config.endpoint("/authorize"))
            .queryParam("response_type", "code")
            .queryParam("client_id", config.getClientId())
            .queryParam("redirect_uri", config.getCallbackUrl())
            .queryParam("state", state);
    // 2. 受控模式携带页面编码，普通 SSO 省略该参数。
    if (config.isPageControlled()) {
      uri.queryParam("page_code", pageCode);
    }
    String result = uri.build().encode().toUriString();
    log.debug("[SsoClient] 已生成授权地址, pageCode={}", pageCode);
    return result;
  }

  /** 【固定回调】一次性 code 只由后端兑换；必须带登记的同一 redirect_uri。 */
  @Override
  public TokenData exchangeCode(String code) {
    log.debug("[SsoClient] 正在兑换一次性授权码");
    MultiValueMap<String, String> form = baseTokenForm("authorization_code");
    form.add("code", code);
    form.add("redirect_uri", config.getCallbackUrl());
    ApiEnvelope response = postForm("/token", form);
    return requireData(response, TokenData.class, "SSO 未返回 Token");
  }

  /** 【刷新】Token family 的旧值只消费一次；调用方负责串行刷新和成对替换。 */
  @Override
  public TokenData refresh(String refreshToken) {
    log.info("[SsoClient] 正在调用 SSO /token 刷新 Token");
    MultiValueMap<String, String> form = baseTokenForm("refresh_token");
    form.add("refresh_token", refreshToken);
    ApiEnvelope response = postForm("/token", form);
    return requireData(response, TokenData.class, "SSO 未返回刷新 Token");
  }

  /** 【每次进入页面】后端实时校验；业务外壳 success 与 result.active 必须同时有效。 */
  @Override
  public ActiveTokenData checkAccessToken(String accessToken, String pageCode) {
    log.debug("[SsoClient] 正在调用 SSO /checkAccessToken: pageCode={}", pageCode);
    config.requireBackendCredentials();
    // 1. 组装后端校验请求，按模式决定是否加入 pageCode。
    Map<String, String> body = new LinkedHashMap<String, String>();
    body.put("clientId", config.getClientId());
    body.put("clientSecret", config.getClientSecret());
    body.put("accessToken", accessToken);
    if (config.isPageControlled()) {
      body.put("pageCode", pageCode);
    }
    // 2. 先检查业务外壳；业务 code=500 在此接口表示 Token 无效。
    ApiEnvelope envelope = postJson("/checkAccessToken", body);
    if (!envelope.isSuccess() || envelope.getCode() != 200) {
      // 无效 Token 返回 HTTP 200 + 业务失败，不得按响应体 active=true 绕过失败状态。
      if (envelope.getCode() == 500) {
        return new ActiveTokenData();
      }
      throw new SsoClientException("SSO Token 校验失败", false);
    }
    // 3. 外壳通过后，再解析 result 中的 active 和绑定字段。
    JsonNode data = envelope.getResult();
    if (data == null || data.isNull()) {
      log.warn("[SsoClient] /checkAccessToken 返回空数据 (Token已失效或无权访问)");
      return new ActiveTokenData();
    }
    try {
      // checkAccessToken 的业务 code=500 是“无效”而不是网络异常，按 active=false 处理。
      ActiveTokenData result = json.treeToValue(data, ActiveTokenData.class);
      log.info("[SsoClient] /checkAccessToken 校验结果: active={}, uid={}, pageCode={}",
          result.isActive(), result.getUid(), result.getPageCode());
      return result;
    } catch (Exception e) {
      log.error("[SsoClient] SSO Token 校验响应解析异常", e);
      throw new SsoClientException("SSO Token 校验响应格式错误", false, e);
    }
  }

  /** 【固定回调】用已校验的 AccessToken 获取最小用户信息，不信任浏览器自报身份。 */
  @Override
  public UserInfo getUserInfo(String accessToken) {
    log.info("[SsoClient] 正在调用 SSO /getUserInfoByOauth2 获取用户信息");
    config.requireBackendCredentials();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", config.getClientId());
    form.add("client_secret", config.getClientSecret());
    form.add("tokenValue", accessToken);
    ApiEnvelope response = postForm("/getUserInfoByOauth2", form);
    UserInfo user = requireData(response, UserInfo.class, "SSO 未返回用户信息");
    log.info("[SsoClient] 获取用户信息成功: id={}, name={}, userCode={}", user.getId(), user.getName(), user.getUserCode());
    return user;
  }

  /** 注销的是当前页面 Token family，不会退出 Portal 全局 SID。 */
  /** 生成客户端发起的 SSO 全局退出地址，供浏览器跳转销毁 SSO 全局 Cookie 并重定向回业务系统。 */
  @Override
  public String globalLogoutUrl(String postLogoutRedirectUri) {
    config.requireBackendCredentials();
    return UriComponentsBuilder.fromHttpUrl(config.endpoint("/logout"))
        .queryParam("client_id", config.getClientId())
        .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
        .build().encode().toUriString();
  }

  @Override
  public boolean logout(String accessToken) {
    log.info("[SsoClient] 正在调用 SSO /logout 注销页面 Token");
    config.requireBackendCredentials();
    Map<String, String> body = new LinkedHashMap<String, String>();
    body.put("clientId", config.getClientId());
    body.put("clientSecret", config.getClientSecret());
    body.put("accessToken", accessToken);
    ApiEnvelope response = postJson("/logout", body);
    LogoutData data = requireData(response, LogoutData.class, "SSO 未返回注销结果");
    log.info("[SsoClient] /logout 注销响应: revoked={}", data.isRevoked());
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
    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<MultiValueMap<String, String>>(form, headers);
    return exchange(endpoint, request);
  }

  private ApiEnvelope postJson(String endpoint, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, String>> request = new HttpEntity<Map<String, String>>(body, headers);
    return exchange(endpoint, request);
  }

  /** 【协议错误边界】HTTP 网络错误与 V2 Result 业务失败不能混淆，更不能降级放行。 */
  private ApiEnvelope exchange(String endpoint, HttpEntity<?> entity) {
    // nanoTime 只测本次 B -> SSO 的 HTTP 往返，不含浏览器跳转和用户输入密码时间。
    long startNanos = System.nanoTime();
    try {
      // 1. 发送一次 HTTP 请求，并记录本次后端往返耗时。
      URI uri = URI.create(config.endpoint(endpoint));
      log.info("[SsoClient] 向 SSO 发送 HTTP 请求: endpoint={}", endpoint);
      ResponseEntity<String> response = http.exchange(uri, HttpMethod.POST, entity, String.class);
      log.info("[SSO-Timing] HTTP 往返: endpoint={}, status={}, elapsedMs={}",
          endpoint, response.getStatusCodeValue(), elapsedMs(startNanos));
      // 2. 把响应读成统一业务外壳；具体结果由调用方法解析。
      ApiEnvelope envelope = json.readValue(response.getBody(), ApiEnvelope.class);
      if (envelope == null) {
        log.error("[SsoClient] SSO 返回空响应体: endpoint={}", endpoint);
        throw new SsoClientException("SSO 返回空响应", false);
      }
      return envelope;
    } catch (RestClientResponseException e) {
      log.warn("[SSO-Timing] HTTP 错误: endpoint={}, status={}, elapsedMs={}",
          endpoint, e.getRawStatusCode(), elapsedMs(startNanos));
      boolean unavailable = e.getRawStatusCode() >= 500;
      String message = "SSO 服务暂不可用";
      if (!unavailable) {
        message = responseMessage(e.getResponseBodyAsString());
      }
      throw new SsoClientException(message, unavailable, e);
    } catch (ResourceAccessException e) {
      log.error("[SSO-Timing] SSO 连接失败: endpoint={}, elapsedMs={}, error={}",
          endpoint, elapsedMs(startNanos), e.getMessage());
      throw new SsoClientException("无法连接 SSO 服务", true, e);
    } catch (SsoClientException e) {
      throw e;
    } catch (Exception e) {
      log.error("[SsoClient] 解析 SSO 响应异常: endpoint={}", endpoint, e);
      throw new SsoClientException("无法解析 SSO 响应", false, e);
    }
  }

  private <T> T requireData(ApiEnvelope envelope, Class<T> type, String emptyMessage) {
    // 1. 确认业务成功且 result 不为空。
    if (!envelope.isSuccess() || envelope.getCode() != 200) {
      String message = envelope.getMessage();
      if (!StringUtils.hasText(message)) {
        message = "SSO 请求失败";
      }
      throw new SsoClientException(message, false);
    }
    JsonNode data = envelope.getResult();
    if (data == null || data.isNull()) {
      throw new SsoClientException(emptyMessage, false);
    }
    // 2. 将 result 转成当前接口需要的数据类型。
    try {
      return json.treeToValue(data, type);
    } catch (Exception e) {
      throw new SsoClientException("SSO 响应字段不完整", false, e);
    }
  }

  private long elapsedMs(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  private String responseMessage(String body) {
    try {
      ApiEnvelope envelope = json.readValue(body, ApiEnvelope.class);
      if (envelope != null && StringUtils.hasText(envelope.getMessage())) {
        return envelope.getMessage();
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
}
