package org.jeecg.modules.sso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.jeecg.modules.sso.dto.SsoDtos.PendingPayload;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PendingService {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SsoProperties properties;
    public PendingService(StringRedisTemplate redis, ObjectMapper objectMapper, SsoProperties properties) {
        this.redis = redis; this.objectMapper = objectMapper; this.properties = properties;
    }
    public String save(PendingPayload payload) {
        try {
            String id = UUID.randomUUID().toString().replace("-", "");
            payload.setCreatedAt(System.currentTimeMillis() / 1000L);
            redis.opsForValue().set(SsoRedisKey.PENDING + id, objectMapper.writeValueAsString(payload),
                    properties.getPendingTtl());
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("pending保存失败", e);
        }
    }
    public PendingPayload take(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String raw = redis.opsForValue().getAndDelete(SsoRedisKey.PENDING + id);
        if (raw == null) return null;
        try { return objectMapper.readValue(raw, PendingPayload.class); }
        catch (Exception e) { return null; }
    }
    public boolean incrementAndExceeded(String clientKey) {
        String key = SsoRedisKey.PENDING_COUNT + clientKey;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count.longValue() == 1L) redis.expire(key, properties.getPendingTtl());
        return count != null && count.longValue() >= properties.getPendingMaxRetry();
    }
}
