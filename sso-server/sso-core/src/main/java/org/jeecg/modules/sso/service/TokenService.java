package org.jeecg.modules.sso.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final SsoProperties properties;
    private final SigningKeyService keyService;
    public TokenService(SsoProperties properties, SigningKeyService keyService) {
        this.properties = properties; this.keyService = keyService;
    }
    public String issueIdentityAssertion(SessionData session, String clientId, String requestNonce) {
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(properties.getIssuer()).audience(clientId).subject(session.getSub())
                    .claim("username", session.getUsername()).claim("sid", session.getSid())
                    .claim("auth_time", Long.valueOf(session.getAuthTime()))
                    .claim("session_expires_at", Long.valueOf(session.getAbsoluteExpiresAt()))
                    .claim("request_nonce", requestNonce).issueTime(new Date(now))
                    .expirationTime(new Date(now + properties.getAssertionTtl().toMillis()))
                    .jwtID(UUID.randomUUID().toString()).build();
            return sign(claims);
        } catch (Exception e) { throw new IllegalStateException("identity assertion签发失败", e); }
    }
    public String issueLogoutToken(String jti, String sub, String sid, String clientId) {
        try {
            Map<String, Object> events = new HashMap<String, Object>();
            events.put("http://schemas.openid.net/event/backchannel-logout", new HashMap<String, Object>());
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(properties.getIssuer()).audience(clientId).subject(sub).claim("sid", sid)
                    .claim("events", events).issueTime(new Date(now))
                    .expirationTime(new Date(now + properties.getLogoutTokenTtl().toMillis())).jwtID(jti).build();
            return sign(claims);
        } catch (Exception e) { throw new IllegalStateException("logout token签发失败", e); }
    }
    /** 敏感操作前的会话状态结果同样签名，避免 HTTP 链路中的 active 字段被篡改。 */
    public String issueSessionStatus(String sid, boolean active, String clientId, String requestNonce) {
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(properties.getIssuer()).audience(clientId).claim("client_id", clientId).claim("sid", sid)
                    .claim("active", Boolean.valueOf(active)).claim("request_nonce", requestNonce)
                    .issueTime(new Date(now))
                    .expirationTime(new Date(now + properties.getAssertionTtl().toMillis()))
                    .jwtID(UUID.randomUUID().toString()).build();
            return sign(claims);
        } catch (Exception e) { throw new IllegalStateException("session status签发失败", e); }
    }
    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyService.currentKid()).build(), claims);
        jwt.sign(new RSASSASigner(keyService.currentPrivateKey()));
        return jwt.serialize();
    }
}
