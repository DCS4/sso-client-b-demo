package com.dcs4.ssoclient;

import com.dcs4.ssoclient.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.SsoModels.ApiEnvelope;
import com.dcs4.ssoclient.SsoModels.LogoutData;
import com.dcs4.ssoclient.SsoModels.TokenData;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import com.dcs4.ssoclient.SsoGateway.SsoClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.TimeUnit;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 外部系统后端对 SSO V2 的唯一 HTTP 封装。
 *
 * <p>业务 Controller 不应自行拼接 client_secret 或 Token 请求；集中封装可以保证
 * 密钥永远不进入浏览器，并统一执行错误处理。
 *
 * <p>【接入必要项】本类是 SsoGateway 的 HTTP 实现；其它技术栈只需按 V2 接口文档
 * 改写这一层，业务页面、state 与本地 Token 流程无需绑定 RestTemplate。</p></p>
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
    String result = uri.build().encode().toUriString();
    log.debug("[SsoClient] 已生成授权地址, pageCode={}", pageCode);
    return result;
  }

  @Override
  /** 【固定回调】一次性 code 只由后端兑换；必须带登记的同一 redirect_uri。 */
  public TokenData exchangeCode(String code) {
    log.debug("[SsoClient] 正在兑换一次性授权码");
    MultiValueMap<String, String> form = baseTokenForm("authorization_code");
    form.add("code", code);
    form.add("redirect_uri", config.getCallbackUrl());
    return requireData(postForm("/token", form), TokenData.class, "SSO 未返回 Token");
  }

  @Override
  /** 【刷新】Token family 的旧值只消费一次；调用方负责串行刷新和成对替换。 */
  public TokenData refresh(String refreshToken) {
    log.info("[SsoClient] 正在调用 SSO /token 刷新 Token");
    MultiValueMap<String, String> form = baseTokenForm("refresh_token");
    form.add("refresh_token", refreshToken);
    return requireData(postForm("/token", form), TokenData.class, "SSO 未返回刷新 Token");
  }

  @Override
  /** 【每次进入页面】后端实时校验；业务外壳 success 与 result.active 必须同时有效。 */
  public ActiveTokenData checkAccessToken(String accessToken, String pageCode) {
    log.debug("[SsoClient] 正在调用 SSO /checkAccessToken: pageCode={}", pageCode);
    config.requireBackendCredentials();
    Map<String, String> body = new LinkedHashMap<String, String>();
    body.put("clientId", config.getClientId());
    body.put("clientSecret", config.getClientSecret());
    body.put("accessToken", accessToken);
    if (config.isPageControlled()) {
      body.put("pageCode", pageCode);
    }
    ApiEnvelope envelope = postJson("/checkAccessToken", body);
    if (!envelope.isSuccess() || envelope.getCode() != 200) {
      // 无效 Token 返回 HTTP 200 + 业务失败，不得按响应体 active=true 绕过失败状态。
      if (envelope.getCode() != 500) {
        throw new SsoClientException("SSO Token 校验失败", false);
      }
      return new ActiveTokenData();
    }
    if (envelope.getResult() == null || envelope.getResult().isNull()) {
      log.warn("[SsoClient] /checkAccessToken 返回空数据 (Token已失效或无权访问)");
      return new ActiveTokenData();
    }
    try {
      // checkAccessToken 的业务 code=500 是“无效”而不是网络异常，按 active=false 处理。
      ActiveTokenData result = json.treeToValue(envelope.getResult(), ActiveTokenData.class);
      log.info("[SsoClient] /checkAccessToken 校验结果: active={}, uid={}, pageCode={}",
          result.isActive(), result.getUid(), result.getPageCode());
      return result;
    } catch (Exception e) {
      log.error("[SsoClient] SSO Token 校验响应解析异常", e);
      throw new SsoClientException("SSO Token 校验响应格式错误", false, e);
    }
  }

  @Override
  /** 【固定回调】用已校验的 AccessToken 获取最小用户信息，不信任浏览器自报身份。 */
  public UserInfo getUserInfo(String accessToken) {
    log.info("[SsoClient] 正在调用 SSO /getUserInfoByOauth2 获取用户信息");
    config.requireBackendCredentials();
    MultiValueMap<String, String> form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", config.getClientId());
    form.add("client_secret", config.getClientSecret());
    form.add("tokenValue", accessToken);
    UserInfo user = requireData(
        postForm("/getUserInfoByOauth2", form), UserInfo.class, "SSO 未返回用户信息");
    log.info("[SsoClient] 获取用户信息成功: id={}, name={}, userCode={}", user.getId(), user.getName(), user.getUserCode());
    return user;
  }

  /** 注销的是当前页面 Token family，不会退出 Portal 全局 SID。 */
  @Override
  public boolean logout(String accessToken) {
    log.info("[SsoClient] 正在调用 SSO /logout 注销页面 Token");
    config.requireBackendCredentials();
    Map<String, String> body = new LinkedHashMap<String, String>();
    body.put("clientId", config.getClientId());
    body.put("clientSecret", config.getClientSecret());
    body.put("accessToken", accessToken);
    LogoutData data = requireData(postJson("/logout", body), LogoutData.class, "SSO 未返回注销结果");
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
    return exchange(endpoint, new HttpEntity<MultiValueMap<String, String>>(form, headers));
  }

  private ApiEnvelope postJson(String endpoint, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return exchange(endpoint, new HttpEntity<Map<String, String>>(body, headers));
  }

  /** 【协议错误边界】HTTP 网络错误与 V2 Result 业务失败不能混淆，更不能降级放行。 */
  private ApiEnvelope exchange(String endpoint, HttpEntity<?> entity) {
    // nanoTime 只测本次 B -> SSO 的 HTTP 往返，不含浏览器跳转和用户输入密码时间。
    long startNanos = System.nanoTime();
    try {
      URI uri = URI.create(config.endpoint(endpoint));
      log.info("[SsoClient] 向 SSO 发送 HTTP 请求: endpoint={}", endpoint);
      ResponseEntity<String> response = http.exchange(uri, HttpMethod.POST, entity, String.class);
      log.info("[SSO-Timing] HTTP 往返: endpoint={}, status={}, elapsedMs={}",
          endpoint, response.getStatusCodeValue(), elapsedMs(startNanos));
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
      throw new SsoClientException(
          unavailable ? "SSO 服务暂不可用" : responseMessage(e.getResponseBodyAsString()),
          unavailable,
          e);
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
    if (!envelope.isSuccess() || envelope.getCode() != 200) {
      throw new SsoClientException(
          StringUtils.hasText(envelope.getMessage()) ? envelope.getMessage() : "SSO 请求失败", false);
    }
    JsonNode data = envelope.getResult();
    if (data == null || data.isNull()) {
      throw new SsoClientException(emptyMessage, false);
    }
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
