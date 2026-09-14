package org.jeecg.modules.sso.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** 协议 DTO 集中定义，所有身份主键使用 sub，不使用可变的 username。 */
public final class SsoDtos {
    private SsoDtos() {
    }

    public static class SessionData {
        private String sid;
        private String sub;
        private String username;
        private long authTime;
        private long absoluteExpiresAt;
        private long lastActiveAt;
        public String getSid() { return sid; }
        public void setSid(String value) { sid = value; }
        public String getSub() { return sub; }
        public void setSub(String value) { sub = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public long getAuthTime() { return authTime; }
        public void setAuthTime(long value) { authTime = value; }
        public long getAbsoluteExpiresAt() { return absoluteExpiresAt; }
        public void setAbsoluteExpiresAt(long value) { absoluteExpiresAt = value; }
        public long getLastActiveAt() { return lastActiveAt; }
        public void setLastActiveAt(long value) { lastActiveAt = value; }
    }

    public static class BeginSessionRequest {
        private String sub;
        private String username;
        @JsonProperty("old_sid") private String oldSid;
        private String pending;
        public String getSub() { return sub; }
        public void setSub(String value) { sub = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public String getOldSid() { return oldSid; }
        public void setOldSid(String value) { oldSid = value; }
        public String getPending() { return pending; }
        public void setPending(String value) { pending = value; }
    }

    public static class BeginSessionResponse {
        private String sid;
        @JsonProperty("expires_at") private long expiresAt;
        @JsonProperty("redirect_url") private String redirectUrl;
        public BeginSessionResponse() { }
        public BeginSessionResponse(String sid, long expiresAt, String redirectUrl) {
            this.sid = sid; this.expiresAt = expiresAt; this.redirectUrl = redirectUrl;
        }
        public String getSid() { return sid; }
        public void setSid(String value) { sid = value; }
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long value) { expiresAt = value; }
        public String getRedirectUrl() { return redirectUrl; }
        public void setRedirectUrl(String value) { redirectUrl = value; }
    }

    public static class PendingPayload {
        private String clientId;
        private String redirectUri;
        private String state;
        private String codeChallenge;
        private long createdAt;
        public String getClientId() { return clientId; }
        public void setClientId(String value) { clientId = value; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String value) { redirectUri = value; }
        public String getState() { return state; }
        public void setState(String value) { state = value; }
        public String getCodeChallenge() { return codeChallenge; }
        public void setCodeChallenge(String value) { codeChallenge = value; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long value) { createdAt = value; }
    }

    public static class CodePayload extends PendingPayload {
        private String sid;
        private String sub;
        private long expiresAt;
        public String getSid() { return sid; }
        public void setSid(String value) { sid = value; }
        public String getSub() { return sub; }
        public void setSub(String value) { sub = value; }
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long value) { expiresAt = value; }
    }

    public static class ExchangeRequest {
        private String code;
        @JsonProperty("redirect_uri") private String redirectUri;
        @JsonProperty("code_verifier") private String codeVerifier;
        public String getCode() { return code; }
        public void setCode(String value) { code = value; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String value) { redirectUri = value; }
        public String getCodeVerifier() { return codeVerifier; }
        public void setCodeVerifier(String value) { codeVerifier = value; }
    }

    public static class ExchangeResponse {
        @JsonProperty("identity_assertion") private String identityAssertion;
        public ExchangeResponse() { }
        public ExchangeResponse(String identityAssertion) { this.identityAssertion = identityAssertion; }
        public String getIdentityAssertion() { return identityAssertion; }
        public void setIdentityAssertion(String value) { identityAssertion = value; }
    }

    public static class LogoutRequest {
        private String sid;
        @JsonProperty("post_logout_redirect_uri") private String postLogoutRedirectUri;
        public String getSid() { return sid; }
        public void setSid(String value) { sid = value; }
        public String getPostLogoutRedirectUri() { return postLogoutRedirectUri; }
        public void setPostLogoutRedirectUri(String value) { postLogoutRedirectUri = value; }
    }

    public static class LogoutPayload {
        private String sid;
        private String clientId;
        private String postLogoutRedirectUri;
        public String getSid() { return sid; }
        public void setSid(String value) { sid = value; }
        public String getClientId() { return clientId; }
        public void setClientId(String value) { clientId = value; }
        public String getPostLogoutRedirectUri() { return postLogoutRedirectUri; }
        public void setPostLogoutRedirectUri(String value) { postLogoutRedirectUri = value; }
    }

    public static class RevokeUserRequest {
        private String sub;
        @JsonProperty("event_type") private String eventType;
        public String getSub() { return sub; }
        public void setSub(String value) { sub = value; }
        public String getEventType() { return eventType; }
        public void setEventType(String value) { eventType = value; }
    }

    public static class ErrorResponse {
        private String error;
        private String errorDescription;
        public ErrorResponse() { }
        public ErrorResponse(String error, String errorDescription) {
            this.error = error; this.errorDescription = errorDescription;
        }
        public String getError() { return error; }
        public void setError(String value) { error = value; }
        @JsonProperty("error_description") public String getErrorDescription() { return errorDescription; }
        @JsonProperty("error_description") public void setErrorDescription(String value) { errorDescription = value; }
    }

    public static Map<String, Object> status(boolean active, String sid) {
        java.util.Map<String, Object> result = new java.util.HashMap<String, Object>();
        result.put("sid", sid);
        result.put("active", Boolean.valueOf(active));
        return result;
    }
}
