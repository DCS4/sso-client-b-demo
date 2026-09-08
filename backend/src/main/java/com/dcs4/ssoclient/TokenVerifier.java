package com.dcs4.ssoclient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TokenVerifier {
  private final SsoConfig config;

  public TokenVerifier(SsoConfig config) {
    this.config = config;
  }

  // 公钥文件由可信渠道部署；不从 HTTP JWKS 自动建立信任。
  public JWTClaimsSet verify(String token, String kind, String nonce, String sid) throws Exception {
    if (config.trustedJwks.isEmpty()) throw new IllegalStateException("请配置可信公钥文件 SSO_TRUSTED_JWKS");
    SignedJWT jwt = SignedJWT.parse(token);
    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
        || jwt.getHeader().getKeyID() == null) throw new IllegalArgumentException("非法签名算法/kid");
    JWK key =
        JWKSet.parse(
                new String(
                    Files.readAllBytes(Paths.get(config.trustedJwks)), StandardCharsets.UTF_8))
            .getKeyByKeyId(jwt.getHeader().getKeyID());
    if (!(key instanceof RSAKey)
        || key.isPrivate()
        || !jwt.verify(new RSASSAVerifier(((RSAKey) key).toRSAPublicKey())))
      throw new IllegalArgumentException("签名或公钥不可信");
    JWTClaimsSet c = jwt.getJWTClaimsSet();
    long now = System.currentTimeMillis();
    if (!config.issuer.equals(c.getIssuer())
        || !c.getAudience().contains(config.clientId)
        || c.getExpirationTime() == null
        || c.getExpirationTime().getTime() <= now
        || c.getIssueTime() == null
        || c.getIssueTime().getTime() > now + 30000
        || c.getExpirationTime().before(c.getIssueTime())
        || c.getJWTID() == null
        || c.getJWTID().isEmpty()) throw new IllegalArgumentException("声明校验失败");
    if (now - c.getIssueTime().getTime() > ("logout".equals(kind) ? 600000 : 90000))
      throw new IllegalArgumentException("凭证过旧");
    if (c.getStringClaim("sid") == null || c.getStringClaim("sid").isEmpty())
      throw new IllegalArgumentException("缺少 sid");
    if ("logout".equals(kind)) {
      Map<String, Object> events = c.getJSONObjectClaim("events");
      if (events == null
          || !(events.get("http://schemas.openid.net/event/backchannel-logout") instanceof Map)
          || c.getClaims().containsKey("nonce")) throw new IllegalArgumentException("非法登出事件");
    } else {
      if (nonce == null
          || !nonce.equals(c.getStringClaim("request_nonce"))
          || c.getClaims().containsKey("events")) throw new IllegalArgumentException("请求绑定失败");
      if ("identity".equals(kind)) {
        if (c.getSubject() == null
            || c.getSubject().isEmpty()
            || c.getStringClaim("username") == null
            || c.getLongClaim("auth_time") == null
            || c.getLongClaim("session_expires_at") == null
            || c.getLongClaim("session_expires_at") <= now / 1000)
          throw new IllegalArgumentException("身份声明不完整");
      } else if (!config.clientId.equals(c.getStringClaim("client_id"))
          || !sid.equals(c.getStringClaim("sid"))
          || c.getBooleanClaim("active") == null) throw new IllegalArgumentException("状态绑定失败");
    }
    return c;
  }
}
