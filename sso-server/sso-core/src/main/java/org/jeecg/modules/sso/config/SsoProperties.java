package org.jeecg.modules.sso.config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SSO 运行配置。内部接口只支持同机回环，不提供额外监听端口。 */
@Component
@ConfigurationProperties(prefix = "sso")
public class SsoProperties {
    private String publicBaseUrl = "http://192.9.230.21:10089";
    private String portalLoginUrl = "http://192.9.230.21:10088/login";
    private String portalPostLogoutUrl = "http://192.9.230.21:10088/login";
    private String issuer = "http://192.9.230.21:10089";
    private String innerToken;
    private String keyStorePath = "./data/sso-keys";
    private String currentKid = "2026-01";
    /** 除当前 kid 外可发布到 JWKS 的密钥及其截止时刻；目录内其它 JWK 绝不自动发布。 */
    private Map<String, Instant> jwksAdditionalKids = Collections.emptyMap();
    private String masterKeyEnv = "SSO_MASTER_KEY";
    private Duration sessionAbsoluteTimeout = Duration.ofHours(4);
    private Duration sessionIdleTimeout = Duration.ofMinutes(30);
    private Duration codeTtl = Duration.ofSeconds(90);
    private Duration pendingTtl = Duration.ofMinutes(10);
    private int pendingMaxRetry = 3;
    private Duration assertionTtl = Duration.ofSeconds(60);
    private Duration logoutRequestTtl = Duration.ofSeconds(30);
    private Duration logoutTokenTtl = Duration.ofMinutes(10);
    /** Outbox 落库失败时保留撤销客户端快照，供 SSO 恢复任务重试。 */
    private Duration revocationTombstoneTtl = Duration.ofHours(24);
    /** Backchannel 仅允许显式登记的企业网段；空列表表示拒绝所有出站回调。 */
    private List<String> backchannelAllowedCidrs = Collections.emptyList();
    private Duration nonceTtl = Duration.ofMinutes(5);
    private Duration timestampWindow = Duration.ofSeconds(120);

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public String getPortalLoginUrl() { return portalLoginUrl; }
    public void setPortalLoginUrl(String portalLoginUrl) { this.portalLoginUrl = portalLoginUrl; }
    public String getPortalPostLogoutUrl() { return portalPostLogoutUrl; }
    public void setPortalPostLogoutUrl(String portalPostLogoutUrl) { this.portalPostLogoutUrl = portalPostLogoutUrl; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getInnerToken() { return innerToken; }
    public void setInnerToken(String innerToken) { this.innerToken = innerToken; }
    public String getKeyStorePath() { return keyStorePath; }
    public void setKeyStorePath(String keyStorePath) { this.keyStorePath = keyStorePath; }
    public String getCurrentKid() { return currentKid; }
    public void setCurrentKid(String currentKid) { this.currentKid = currentKid; }
    public Map<String, Instant> getJwksAdditionalKids() { return jwksAdditionalKids; }
    public void setJwksAdditionalKids(Map<String, Instant> value) {
        jwksAdditionalKids = value == null ? Collections.<String, Instant>emptyMap()
                : new LinkedHashMap<String, Instant>(value);
    }
    public String getMasterKeyEnv() { return masterKeyEnv; }
    public void setMasterKeyEnv(String masterKeyEnv) { this.masterKeyEnv = masterKeyEnv; }
    public Duration getSessionAbsoluteTimeout() { return sessionAbsoluteTimeout; }
    public void setSessionAbsoluteTimeout(Duration value) { this.sessionAbsoluteTimeout = value; }
    public Duration getSessionIdleTimeout() { return sessionIdleTimeout; }
    public void setSessionIdleTimeout(Duration value) { this.sessionIdleTimeout = value; }
    public Duration getCodeTtl() { return codeTtl; }
    public void setCodeTtl(Duration value) { this.codeTtl = value; }
    public Duration getPendingTtl() { return pendingTtl; }
    public void setPendingTtl(Duration value) { this.pendingTtl = value; }
    public int getPendingMaxRetry() { return pendingMaxRetry; }
    public void setPendingMaxRetry(int value) { this.pendingMaxRetry = value; }
    public Duration getAssertionTtl() { return assertionTtl; }
    public void setAssertionTtl(Duration value) { this.assertionTtl = value; }
    public Duration getLogoutRequestTtl() { return logoutRequestTtl; }
    public void setLogoutRequestTtl(Duration value) { this.logoutRequestTtl = value; }
    public Duration getLogoutTokenTtl() { return logoutTokenTtl; }
    public void setLogoutTokenTtl(Duration value) { this.logoutTokenTtl = value; }
    public Duration getRevocationTombstoneTtl() { return revocationTombstoneTtl; }
    public void setRevocationTombstoneTtl(Duration value) { revocationTombstoneTtl = value; }
    public List<String> getBackchannelAllowedCidrs() { return backchannelAllowedCidrs; }
    public void setBackchannelAllowedCidrs(List<String> value) {
        backchannelAllowedCidrs = value == null ? Collections.<String>emptyList() : new ArrayList<String>(value);
    }
    public Duration getNonceTtl() { return nonceTtl; }
    public void setNonceTtl(Duration value) { this.nonceTtl = value; }
    public Duration getTimestampWindow() { return timestampWindow; }
    public void setTimestampWindow(Duration value) { this.timestampWindow = value; }
}
