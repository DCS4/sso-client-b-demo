package org.jeecg.modules.sso.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 全局会话：绝对 4h 上限，30m 空闲，按 sub 用 ZSET 维护可撤销索引。 */
@Service
public class SessionService {
    private static final DefaultRedisScript<Long> CREATE_SESSION = new DefaultRedisScript<Long>(
            "if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end "
                    + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                    + "redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4]) return 1", Long.class);
    private static final DefaultRedisScript<List> REQUIRE_ACTIVE = new DefaultRedisScript<List>(
            "local raw = redis.call('GET', KEYS[1]) "
                    + "if not raw then return {0, ''} end "
                    + "local session = cjson.decode(raw) "
                    + "if redis.call('EXISTS', ARGV[3] .. session.sub) == 1 then return {0, ''} end "
                    + "if tonumber(ARGV[1]) >= tonumber(session.absoluteExpiresAt) "
                    + "or tonumber(ARGV[1]) - tonumber(session.lastActiveAt) >= tonumber(ARGV[2]) then "
                    + "  redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); "
                    + "  redis.call('ZREM', ARGV[4] .. session.sub, session.sid); return {0, ''} end "
                    + "session.lastActiveAt = tonumber(ARGV[1]) "
                    + "local ttl = math.min(tonumber(session.absoluteExpiresAt) - tonumber(ARGV[1]), tonumber(ARGV[2])) "
                    + "redis.call('SET', KEYS[1], cjson.encode(session), 'EX', ttl) "
                    + "return {1, cjson.encode(session)}", List.class);
    private static final DefaultRedisScript<List> REVOKE_SESSION = new DefaultRedisScript<List>(
            "local existing = redis.call('GET', KEYS[3]) "
                    + "if existing then return {1, existing} end "
                    + "local raw = redis.call('GET', KEYS[1]) "
                    + "if not raw then return {0, ''} end "
                    + "local session = cjson.decode(raw) "
                    + "local clients = redis.call('SMEMBERS', KEYS[2]) "
                    + "local tombstone = cjson.encode({session = session, clientIds = clients}) "
                    + "redis.call('SET', KEYS[3], tombstone, 'EX', ARGV[1]) "
                    + "redis.call('SADD', ARGV[3] .. session.sub, session.sid) "
                    + "redis.call('EXPIRE', ARGV[3] .. session.sub, ARGV[1]) "
                    + "redis.call('SADD', ARGV[4], session.sub) "
                    + "redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); "
                    + "redis.call('ZREM', ARGV[2] .. session.sub, session.sid) "
                    + "return {1, tombstone}", List.class);
    private static final DefaultRedisScript<Long> CLEAR_REVOCATION = new DefaultRedisScript<Long>(
            "redis.call('DEL', KEYS[1]) "
                    + "redis.call('SREM', KEYS[2], ARGV[1]) "
                    + "if redis.call('SCARD', KEYS[2]) == 0 then "
                    + "  redis.call('DEL', KEYS[2]); redis.call('SREM', KEYS[3], ARGV[2]) "
                    + "end return 1", Long.class);
    private static final DefaultRedisScript<Long> CLEANUP_EMPTY_REVOCATION_SUBJECT = new DefaultRedisScript<Long>(
            "if redis.call('SCARD', KEYS[1]) == 0 then "
                    + "  redis.call('DEL', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]); return 1 "
                    + "end return 0", Long.class);
    private static final DefaultRedisScript<Long> REGISTER_CLIENT = new DefaultRedisScript<Long>(
            "local raw = redis.call('GET', KEYS[1]) "
                    + "if not raw then return 0 end "
                    + "local session = cjson.decode(raw) "
                    + "if redis.call('EXISTS', ARGV[3] .. session.sub) == 1 then return 0 end "
                    + "redis.call('SADD', KEYS[2], ARGV[1]) "
                    + "redis.call('EXPIREAT', KEYS[2], ARGV[2]) return 1", Long.class);
    private static final DefaultRedisScript<List> DISABLE_SUBJECT = new DefaultRedisScript<List>(
            "redis.call('SET', KEYS[1], 'disabled') "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[1]) "
                    + "local active = redis.call('ZRANGE', KEYS[2], 0, -1) "
                    + "local pending = redis.call('SMEMBERS', KEYS[3]) "
                    + "for _, sid in ipairs(pending) do table.insert(active, sid) end "
                    + "return active", List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SsoProperties properties;

    public SessionService(StringRedisTemplate redis, ObjectMapper objectMapper, SsoProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 读取 subject-disabled 与创建会话在同一个 Lua 脚本中完成，禁用事件一旦落到 Redis，
     * 并发中的登录不会再创建新的有效全局会话。
     *
     * @return subject 已禁用时返回 {@code null}
     */
    public SessionData create(String sub, String username) {
        long now = nowSeconds();
        SessionData session = new SessionData();
        session.setSid(UUID.randomUUID().toString().replace("-", ""));
        session.setSub(sub);
        session.setUsername(username);
        session.setAuthTime(now);
        session.setLastActiveAt(now);
        session.setAbsoluteExpiresAt(now + properties.getSessionAbsoluteTimeout().getSeconds());
        Long created = redis.execute(CREATE_SESSION,
                Arrays.asList(sessionKey(session.getSid()), userSessionsKey(sub), subjectStatusKey(sub)),
                serialize(session), String.valueOf(sessionTtlSeconds(session, now)),
                String.valueOf(session.getAbsoluteExpiresAt()), session.getSid());
        return Long.valueOf(1L).equals(created) ? session : null;
    }

    /** 原子检查、空闲时间刷新和过期清理，因而无法在撤销后重建 Session。 */
    public SessionData requireActive(String sid) {
        if (sid == null || sid.trim().isEmpty()) {
            return null;
        }
        List<?> result = redis.execute(REQUIRE_ACTIVE,
                Arrays.asList(sessionKey(sid), sessionClientsKey(sid)), String.valueOf(nowSeconds()),
                String.valueOf(properties.getSessionIdleTimeout().getSeconds()),
                SsoRedisKey.SUBJECT_DISABLED, SsoRedisKey.USER_SESSIONS);
        if (!success(result)) {
            return null;
        }
        return deserialize(valueAt(result, 1));
    }

    /** 原子写入撤销墓碑、快照已登录客户端并删除有效会话。 */
    public RevokedSession revoke(String sid) {
        if (sid == null || sid.trim().isEmpty()) {
            return RevokedSession.empty();
        }
        List<?> result = redis.execute(REVOKE_SESSION,
                Arrays.asList(sessionKey(sid), sessionClientsKey(sid), revocationKey(sid)),
                String.valueOf(properties.getRevocationTombstoneTtl().getSeconds()), SsoRedisKey.USER_SESSIONS,
                SsoRedisKey.USER_REVOCATIONS, SsoRedisKey.REVOCATION_SUBJECTS);
        if (!success(result)) {
            return RevokedSession.empty();
        }
        return deserializeRevocation(valueAt(result, 1));
    }

    public void clearRevocationTombstone(String sid, String sub) {
        if (sid == null || sid.trim().isEmpty() || sub == null || sub.trim().isEmpty()) {
            return;
        }
        redis.execute(CLEAR_REVOCATION, Arrays.asList(revocationKey(sid), userRevocationsKey(sub),
                SsoRedisKey.REVOCATION_SUBJECTS), sid, sub);
    }

    /** 返回待恢复撤销通知的 subject；索引由 revoke() 的 Lua 脚本原子维护。 */
    public Set<String> getPendingRevocationSubjects() {
        Set<String> values = redis.opsForSet().members(SsoRedisKey.REVOCATION_SUBJECTS);
        return values == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(values);
    }

    /** 返回指定 subject 仍有可用墓碑的 SID；空索引会原子清理全局 subject 索引。 */
    public Set<String> getPendingRevocationSids(String sub) {
        if (sub == null || sub.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> values = redis.opsForSet().members(userRevocationsKey(sub));
        if (values == null || values.isEmpty()) {
            redis.execute(CLEANUP_EMPTY_REVOCATION_SUBJECT,
                    Arrays.asList(userRevocationsKey(sub), SsoRedisKey.REVOCATION_SUBJECTS), sub);
            return Collections.emptySet();
        }
        return new LinkedHashSet<String>(values);
    }

    /** @return 会话已撤销或用户已禁用时返回 {@code false}。 */
    public boolean registerClient(String sid, String clientId, long absoluteExpiresAt) {
        Long registered = redis.execute(REGISTER_CLIENT,
                Arrays.asList(sessionKey(sid), sessionClientsKey(sid)), clientId,
                String.valueOf(absoluteExpiresAt), SsoRedisKey.SUBJECT_DISABLED);
        return Long.valueOf(1L).equals(registered);
    }

    public Set<String> getSessionClients(String sid) {
        Set<String> values = redis.opsForSet().members(sessionClientsKey(sid));
        return values == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(values);
    }

    /** 先写禁用标记，再快照当前 SID；与 create() 使用同一个 subject 标记形成原子门禁。 */
    public List<String> disableSubject(String sub) {
        if (sub == null || sub.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<?> result = redis.execute(DISABLE_SUBJECT,
                Arrays.asList(subjectStatusKey(sub), userSessionsKey(sub), userRevocationsKey(sub)),
                String.valueOf(nowSeconds()));
        if (result == null || result.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sids = new ArrayList<String>();
        for (Object value : result) {
            if (value != null) {
                sids.add(String.valueOf(value));
            }
        }
        return sids;
    }

    public void enableSubject(String sub) {
        if (sub != null && !sub.trim().isEmpty()) {
            redis.delete(subjectStatusKey(sub));
        }
    }

    public List<String> getActiveUserSids(String sub) {
        String key = userSessionsKey(sub);
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, (double) nowSeconds());
        Set<String> values = redis.opsForZSet().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return Collections.<String>emptyList();
        }
        List<String> active = new ArrayList<String>();
        for (String sid : values) {
            if (read(sid) == null) {
                redis.opsForZSet().remove(key, sid);
            } else {
                active.add(sid);
            }
        }
        return active;
    }

    private SessionData read(String sid) {
        String raw = redis.opsForValue().get(sessionKey(sid));
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, SessionData.class);
        } catch (Exception e) {
            redis.delete(sessionKey(sid));
            return null;
        }
    }

    private SessionData deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, SessionData.class);
        } catch (Exception e) {
            throw new IllegalStateException("SSO会话反序列化失败", e);
        }
    }

    private RevokedSession deserializeRevocation(String raw) {
        try {
            RevocationTombstone tombstone = objectMapper.readValue(raw, RevocationTombstone.class);
            return new RevokedSession(tombstone.getSession(), tombstone.getClientIds());
        } catch (Exception e) {
            throw new IllegalStateException("SSO撤销墓碑反序列化失败", e);
        }
    }

    private String serialize(SessionData session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSO会话序列化失败", e);
        }
    }

    private long sessionTtlSeconds(SessionData session, long now) {
        long remainingSeconds = session.getAbsoluteExpiresAt() - now;
        if (remainingSeconds <= 0L) {
            throw new IllegalStateException("SSO会话已过期，不能创建");
        }
        return Math.min(remainingSeconds, properties.getSessionIdleTimeout().getSeconds());
    }

    private boolean success(List<?> result) {
        return result != null && !result.isEmpty() && "1".equals(String.valueOf(result.get(0)));
    }

    private String valueAt(List<?> result, int index) {
        if (result == null || result.size() <= index || result.get(index) == null) {
            throw new IllegalStateException("Redis Lua 返回数据不完整");
        }
        return String.valueOf(result.get(index));
    }

    private String sessionKey(String sid) { return SsoRedisKey.SESSION + sid; }
    private String sessionClientsKey(String sid) { return SsoRedisKey.SESSION_CLIENTS + sid; }
    private String userSessionsKey(String sub) { return SsoRedisKey.USER_SESSIONS + sub; }
    private String userRevocationsKey(String sub) { return SsoRedisKey.USER_REVOCATIONS + sub; }
    private String subjectStatusKey(String sub) { return SsoRedisKey.SUBJECT_DISABLED + sub; }
    private String revocationKey(String sid) { return SsoRedisKey.REVOCATION + sid; }
    private long nowSeconds() { return System.currentTimeMillis() / 1000L; }

    public static final class RevocationTombstone {
        private SessionData session;
        private Set<String> clientIds = Collections.emptySet();
        public SessionData getSession() { return session; }
        public void setSession(SessionData value) { session = value; }
        public Set<String> getClientIds() { return clientIds; }
        public void setClientIds(Set<String> value) {
            clientIds = value == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(value);
        }
    }

    public static final class RevokedSession {
        private final SessionData session;
        private final Set<String> clientIds;
        private RevokedSession(SessionData session, Set<String> clientIds) {
            this.session = session;
            this.clientIds = clientIds == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(clientIds);
        }
        private static RevokedSession empty() { return new RevokedSession(null, Collections.<String>emptySet()); }
        static RevokedSession snapshot(SessionData session, Set<String> clientIds) {
            return new RevokedSession(session, clientIds);
        }
        public SessionData getSession() { return session; }
        public Set<String> getClientIds() { return clientIds; }
    }
}
