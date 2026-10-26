package com.dcs4.ssoclient.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 从 application.yml 和后端环境配置读取 SSO 参数；client_secret 不交给前端。 */
@Component
public class SsoConfig {
  public static final String PAGE_CONTROLLED = "PAGE_CONTROLLED";
  public static final String SSO_ONLY = "SSO_ONLY";

  @Value("${sso.base-url}")
  private String baseUrl;

  @Value("${sso.client-id}")
  private String clientId;

  @Value("${sso.client-secret:}")
  private String clientSecret;

  @Value("${sso.callback-url}")
  private String callbackUrl;

  @Value("${sso.auth-mode:PAGE_CONTROLLED}")
  private String authMode;

  @Value("${sso.connect-timeout:2s}")
  private Duration connectTimeout;

  @Value("${sso.read-timeout:3s}")
  private Duration readTimeout;

  /** SSO V2 公共协议前缀；不是内部 /internal/sso 地址，也不是 Portal 前端地址。 */
  public String endpoint(String path) {
    String value = require(baseUrl, "SSO_BASE_URL");
    if (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value + path;
  }

  public void requireBackendCredentials() {
    require(clientId, "SSO_CLIENT_ID");
    require(clientSecret, "SSO_CLIENT_SECRET");
    require(callbackUrl, "B_CALLBACK_URL");
    if (!PAGE_CONTROLLED.equals(authMode) && !SSO_ONLY.equals(authMode)) {
      throw new IllegalStateException("SSO_AUTH_MODE 只能是 PAGE_CONTROLLED 或 SSO_ONLY");
    }
  }

  public boolean isPageControlled() {
    return PAGE_CONTROLLED.equals(authMode);
  }

  /** 【模式差异】PAGE_CONTROLLED 按 pageCode 隔离 Token；SSO_ONLY 按系统共用一组。 */
  public String tokenKey(String pageCode) {
    if (isPageControlled()) {
      return pageCode;
    }
    return "__SSO_ONLY__";
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public String getCallbackUrl() {
    return callbackUrl;
  }

  public String getAuthMode() {
    return authMode;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  private String require(String value, String environmentName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException("请配置 " + environmentName);
    }
    return value.trim();
  }
}
