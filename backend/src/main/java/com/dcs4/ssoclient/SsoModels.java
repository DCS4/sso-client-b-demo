package com.dcs4.ssoclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;

/** 只定义外部系统实际使用的 V2 响应字段，未知字段由 Jackson 忽略。 */
public final class SsoModels {
  private SsoModels() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ApiEnvelope {
    private int code;
    private String msg;
    private JsonNode data;

    public int getCode() {
      return code;
    }

    public void setCode(int code) {
      this.code = code;
    }

    public String getMsg() {
      return msg;
    }

    public void setMsg(String msg) {
      this.msg = msg;
    }

    public JsonNode getData() {
      return data;
    }

    public void setData(JsonNode data) {
      this.data = data;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TokenData {
    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("expires_in")
    private long expiresIn;

    @JsonProperty("refresh_expires_in")
    private long refreshExpiresIn;

    @JsonProperty("client_id")
    private String clientId;

    private String scope;
    private String openid;

    @JsonProperty("page_code")
    private String pageCode;

    public String getAccessToken() {
      return accessToken;
    }

    public void setAccessToken(String accessToken) {
      this.accessToken = accessToken;
    }

    public String getRefreshToken() {
      return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
      this.refreshToken = refreshToken;
    }

    public String getTokenType() {
      return tokenType;
    }

    public void setTokenType(String tokenType) {
      this.tokenType = tokenType;
    }

    public long getExpiresIn() {
      return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
      this.expiresIn = expiresIn;
    }

    public long getRefreshExpiresIn() {
      return refreshExpiresIn;
    }

    public void setRefreshExpiresIn(long refreshExpiresIn) {
      this.refreshExpiresIn = refreshExpiresIn;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getScope() {
      return scope;
    }

    public void setScope(String scope) {
      this.scope = scope;
    }

    public String getOpenid() {
      return openid;
    }

    public void setOpenid(String openid) {
      this.openid = openid;
    }

    public String getPageCode() {
      return pageCode;
    }

    public void setPageCode(String pageCode) {
      this.pageCode = pageCode;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ActiveTokenData {
    private boolean active;
    private String uid;

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("page_code")
    private String pageCode;

    @JsonProperty("expires_in")
    private long expiresIn;

    public boolean isActive() {
      return active;
    }

    public void setActive(boolean active) {
      this.active = active;
    }

    public String getUid() {
      return uid;
    }

    public void setUid(String uid) {
      this.uid = uid;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getPageCode() {
      return pageCode;
    }

    public void setPageCode(String pageCode) {
      this.pageCode = pageCode;
    }

    public long getExpiresIn() {
      return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
      this.expiresIn = expiresIn;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class UserInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String id;
    private String userCode;
    private String name;
    private String companyCode;
    private Integer enableStatus;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getUserCode() {
      return userCode;
    }

    public void setUserCode(String userCode) {
      this.userCode = userCode;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getCompanyCode() {
      return companyCode;
    }

    public void setCompanyCode(String companyCode) {
      this.companyCode = companyCode;
    }

    public Integer getEnableStatus() {
      return enableStatus;
    }

    public void setEnableStatus(Integer enableStatus) {
      this.enableStatus = enableStatus;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class LogoutData {
    private boolean revoked;

    public boolean isRevoked() {
      return revoked;
    }

    public void setRevoked(boolean revoked) {
      this.revoked = revoked;
    }
  }

  /** Token 只放在服务端 HttpSession；生产多实例应换成共享加密存储。 */
  public static class StoredToken implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String accessToken;
    private final String refreshToken;
    private final String pageCode;
    private final String subject;
    private final long accessExpiresAt;
    private final long refreshExpiresAt;

    public StoredToken(TokenData data, long nowSeconds) {
      this.accessToken = data.getAccessToken();
      this.refreshToken = data.getRefreshToken();
      this.pageCode = data.getPageCode();
      this.subject = data.getOpenid();
      this.accessExpiresAt = nowSeconds + Math.max(0L, data.getExpiresIn());
      this.refreshExpiresAt = nowSeconds + Math.max(0L, data.getRefreshExpiresIn());
    }

    public String getAccessToken() {
      return accessToken;
    }

    public String getRefreshToken() {
      return refreshToken;
    }

    public String getPageCode() {
      return pageCode;
    }

    public String getSubject() {
      return subject;
    }

    public long getAccessExpiresAt() {
      return accessExpiresAt;
    }

    public long getRefreshExpiresAt() {
      return refreshExpiresAt;
    }
  }
}
