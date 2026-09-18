package com.dcs4.ssoclient;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 外部系统持有的 SSO V2 配置。
 *
 * <p>client_secret 只能通过后端环境变量或密钥管理系统注入，不能打包进 Vue、
 * application.properties 的真实值或任何浏览器响应。</p>
 */
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

  public String endpoint(String path) {
    String value = require(baseUrl, "SSO_BASE_URL");
    return (value.endsWith("/") ? value.substring(0, value.length() - 1) : value) + path;
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

  /** 普通 SSO 模式下所有页面共用同一组本地 Token。 */
  public String tokenKey(String pageCode) {
    return isPageControlled() ? pageCode : "__SSO_ONLY__";
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
