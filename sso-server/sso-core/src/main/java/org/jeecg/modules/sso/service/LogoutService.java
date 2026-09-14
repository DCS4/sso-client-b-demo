package org.jeecg.modules.sso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.jeecg.modules.sso.dto.SsoDtos.LogoutPayload;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class LogoutService {
    private final SessionService sessionService;
    private final LogoutOutboxService outboxService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SsoProperties properties;
    public LogoutService(SessionService sessionService, LogoutOutboxService outboxService,
                         StringRedisTemplate redis, ObjectMapper objectMapper, SsoProperties properties) {
        this.sessionService = sessionService; this.outboxService = outboxService;
        this.redis = redis; this.objectMapper = objectMapper; this.properties = properties;
    }
    public String revokeAndCreateBrowserRequest(String sid, String clientId, String postLogoutRedirectUri) {
        revokeAndNotify(sid);
        LogoutPayload payload = new LogoutPayload();
        payload.setSid(sid); payload.setClientId(clientId); payload.setPostLogoutRedirectUri(postLogoutRedirectUri);
        String requestId = UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set(SsoRedisKey.LOGOUT_REQUEST + requestId,
                    objectMapper.writeValueAsString(payload), properties.getLogoutRequestTtl());
            return requestId;
        } catch (Exception e) { throw new IllegalStateException("logout request保存失败", e); }
    }

    public SessionData revokeAndNotify(String sid) {
        SessionService.RevokedSession revoked = sessionService.revoke(sid);
        SessionData session = revoked.getSession();
        if (session != null) {
            try {
                for (String registeredClient : revoked.getClientIds()) {
                    outboxService.enqueue(sid, session.getSub(), registeredClient);
                }
            } catch (RuntimeException ex) {
                // 撤销墓碑保留客户端快照；下次调用可继续补齐尚未持久化的 Outbox 事件。
                throw ex;
            }
            try {
                // 只有所有客户端事件均已落库（重复插入也视为已落库）后才允许清理快照。
                sessionService.clearRevocationTombstone(sid, session.getSub());
            } catch (RuntimeException ignored) {
                // 清理失败不影响已完成的吊销；墓碑超时前仍可作为幂等重试的恢复依据。
            }
        }
        return session;
    }
    public LogoutPayload consumeBrowserRequest(String requestId) {
        String raw = redis.opsForValue().getAndDelete(SsoRedisKey.LOGOUT_REQUEST + requestId);
        if (raw == null) return null;
        try { return objectMapper.readValue(raw, LogoutPayload.class); }
        catch (Exception e) { return null; }
    }
}
