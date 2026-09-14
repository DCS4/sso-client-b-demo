package org.jeecg.modules.system.sso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jeecg.common.util.RedisUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 门户 token 到 SSO sid 的服务端映射；Redis Key 只保存 token 哈希。 */
@Service
@ConditionalOnProperty(prefix = "sso.integration", name = "enabled", havingValue = "true")
public class PortalSsoMapService {
    private final RedisUtil redisUtil;
    public PortalSsoMapService(RedisUtil redisUtil) { this.redisUtil = redisUtil; }
    public void save(String token, String sid, long ttlSeconds) {
        if (token != null && sid != null && ttlSeconds > 0) redisUtil.set(key(token), sid, ttlSeconds);
    }
    public String get(String token) {
        Object value = token == null ? null : redisUtil.get(key(token));
        return value == null ? null : String.valueOf(value);
    }
    public void delete(String token) { if (token != null) redisUtil.del(key(token)); }
    private String key(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(hash.length * 2);
            for (byte b : hash) value.append(String.format("%02x", b & 0xff));
            return "portal:sso-map:" + value;
        } catch (Exception e) { throw new IllegalStateException("门户Token哈希失败", e); }
    }
}
