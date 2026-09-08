package com.dcs4.ssoclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SsoConfig {
  @Value("${sso.base-url}")
  public String baseUrl;

  @Value("${sso.issuer}")
  public String issuer;

  @Value("${sso.client-id}")
  public String clientId;

  @Value("${sso.client-secret:}")
  public String secret;

  @Value("${sso.callback-url}")
  public String callback;

  @Value("${sso.return-url}")
  public String returnUrl;

  @Value("${sso.trusted-jwks:}")
  public String trustedJwks;
}
