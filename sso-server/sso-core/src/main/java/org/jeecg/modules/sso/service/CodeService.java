package org.jeecg.modules.sso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.jeecg.modules.sso.dto.SsoDtos.CodePayload;
import org.jeecg.modules.sso.dto.SsoDtos.PendingPayload;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class CodeService {
    private static final DefaultRedisScript<String> GET_AND_DELETE = new DefaultRedisScript<String>(
            "local v = redis.call('GET', KEYS[1]); if v then redis.call('DEL', KEYS[1]); end; return v;", String.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SsoProperties properties;

    public CodeService(StringRedisTemplate redis, ObjectMapper objectMapper, SsoProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String issue(String sid, String sub, PendingPayload pending) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            String code = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            CodePayload payload = new CodePayload();
            payload.setClientId(pending.getClientId());
            payload.setRedirectUri(pending.getRedirectUri());
            payload.setState(pending.getState());
            payload.setCodeChallenge(pending.getCodeChallenge());
            payload.setCreatedAt(now);
            payload.setSid(sid);
            payload.setSub(sub);
            payload.setExpiresAt(now + properties.getCodeTtl().getSeconds());
            redis.opsForValue().set(SsoRedisKey.CODE + code, objectMapper.writeValueAsString(payload),
                    properties.getCodeTtl());
            return code;
        } catch (Exception e) {
            throw new IllegalStateException("授权码签发失败", e);
        }
    }

    public CodePayload consume(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String raw = redis.execute(GET_AND_DELETE, Collections.singletonList(SsoRedisKey.CODE + code));
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, CodePayload.class);
        } catch (Exception e) {
            return null;
        }
    }
}
