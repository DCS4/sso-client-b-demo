package org.jeecg.modules.system.sso;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** 门户到 SSO 的固定回环客户端，不使用 Feign/Nacos 或公开 10089 端口。 */
@Component
@ConditionalOnProperty(prefix = "sso.integration", name = "enabled", havingValue = "true")
public class PortalSsoInnerClient {
    private final RestTemplate restTemplate;
    @Value("${sso.portal.inner-url:http://127.0.0.1:19089}") private String innerUrl;
    @Value("${sso.portal.public-base-url:http://192.9.230.21:10089}") private String publicBaseUrl;
    @Value("${sso.portal.inner-token:}") private String innerToken;

    public PortalSsoInnerClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restTemplate = new RestTemplate(factory);
    }

    public BeginSessionResult beginSession(String sub, String username, String pending, String oldSid) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("sub", sub); body.put("username", username);
        if (pending != null) body.put("pending", pending);
        if (oldSid != null) body.put("old_sid", oldSid);
        Map<?, ?> result = post("/internal/sso/beginSession", body);
        Object expires = result.get("expires_at");
        return new BeginSessionResult(String.valueOf(result.get("sid")),
                expires instanceof Number ? ((Number) expires).longValue() : Long.parseLong(String.valueOf(expires)),
                result.get("redirect_url") == null ? null : String.valueOf(result.get("redirect_url")));
    }

    public String logout(String sid) {
        Map<String, String> body = new HashMap<String, String>(); body.put("sid", sid);
        Map<?, ?> result = post("/internal/sso/logout", body);
        return String.valueOf(result.get("logout_request"));
    }

    public String browserLogoutUrl(String logoutRequest) {
        return publicBaseUrl + "/sso/v1/logout?request=" + logoutRequest;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> post(String path, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-INNER-TOKEN", innerToken);
        ResponseEntity<Map> response = restTemplate.postForEntity(innerUrl + path,
                new HttpEntity<Map<String, String>>(body, headers), Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("SSO回环调用失败: " + response.getStatusCodeValue());
        }
        return response.getBody();
    }

    public static final class BeginSessionResult {
        private final String sid;
        private final long expiresAt;
        private final String redirectUrl;
        private BeginSessionResult(String sid, long expiresAt, String redirectUrl) {
            this.sid = sid; this.expiresAt = expiresAt; this.redirectUrl = redirectUrl;
        }
        public String getSid() { return sid; }
        public long getExpiresAt() { return expiresAt; }
        public String getRedirectUrl() { return redirectUrl; }
    }
}
