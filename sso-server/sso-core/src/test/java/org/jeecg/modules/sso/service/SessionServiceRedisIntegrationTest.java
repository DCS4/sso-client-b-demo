package org.jeecg.modules.sso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用明确隔离的非零 Redis DB 验证 SessionService 的 Lua 原子操作。测试不执行 FLUSHDB，
 * 仅清理自身随机 subject/SID 写入的键。
 */
@EnabledIfEnvironmentVariable(named = "SSO_TEST_REDIS_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SSO_TEST_REDIS_DB", matches = "([1-9]|1[0-5])")
@EnabledIfEnvironmentVariable(named = "SSO_TEST_REDIS_CONFIRM", matches = "I_UNDERSTAND_REDIS_TEST_WRITES")
class SessionServiceRedisIntegrationTest {
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private final List<String> testSubjects = new ArrayList<String>();
    private final List<String> testSids = new ArrayList<String>();
    private boolean migrationMarkerAlreadyPresent;

    @BeforeEach
    void setUp() {
        String host = System.getenv("SSO_TEST_REDIS_HOST");
        String configuredPort = System.getenv("SSO_TEST_REDIS_PORT");
        int port = configuredPort == null || configuredPort.trim().isEmpty()
                ? 6379 : Integer.parseInt(configuredPort);
        int database = Integer.parseInt(System.getenv("SSO_TEST_REDIS_DB"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        configuration.setDatabase(database);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        migrationMarkerAlreadyPresent = Boolean.TRUE.equals(
                redis.hasKey(SsoRedisKey.REVOCATION_SUBJECTS_MIGRATION));
    }

    @AfterEach
    void tearDown() {
        for (String sid : testSids) {
            redis.delete(Arrays.asList(SsoRedisKey.SESSION + sid, SsoRedisKey.SESSION_CLIENTS + sid,
                    SsoRedisKey.REVOCATION + sid));
        }
        for (String sub : testSubjects) {
            redis.delete(Arrays.asList(SsoRedisKey.USER_SESSIONS + sub, SsoRedisKey.USER_REVOCATIONS + sub,
                    SsoRedisKey.SUBJECT_DISABLED + sub));
            redis.opsForSet().remove(SsoRedisKey.REVOCATION_SUBJECTS, sub);
        }
        if (!migrationMarkerAlreadyPresent) {
            redis.delete(SsoRedisKey.REVOCATION_SUBJECTS_MIGRATION);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void revokeLuaCreatesAndAtomicallyCleansGlobalRecoveryIndex() {
        SessionService service = sessionService();
        String sub = newTestSubject("revocation");
        SessionData session = service.create(sub, "tester");
        testSids.add(session.getSid());
        assertTrue(service.registerClient(session.getSid(), "client-a", session.getAbsoluteExpiresAt()));

        SessionService.RevokedSession revoked = service.revoke(session.getSid());

        assertEquals(session.getSid(), revoked.getSession().getSid());
        assertTrue(revoked.getClientIds().contains("client-a"));
        assertNull(service.requireActive(session.getSid()));
        assertTrue(service.getPendingRevocationSubjects().contains(sub));
        assertTrue(service.getPendingRevocationSids(sub).contains(session.getSid()));

        service.clearRevocationTombstone(session.getSid(), sub);

        assertFalse(service.getPendingRevocationSubjects().contains(sub));
        assertTrue(service.getPendingRevocationSids(sub).isEmpty());
    }

    @Test
    void startupMigrationFindsLegacyUserRevocationIndexes() {
        assumeFalse(migrationMarkerAlreadyPresent,
                "测试 Redis DB 已有迁移完成标记；请使用空闲的专用测试 DB");
        Long databaseSize = redis.execute(RedisConnection::dbSize);
        assumeTrue(Long.valueOf(0L).equals(databaseSize),
                "旧索引迁移测试要求空的专用 Redis DB");
        String sub = newTestSubject("legacy");
        redis.opsForSet().add(SsoRedisKey.USER_REVOCATIONS + sub, "sid-legacy");
        assertFalse(redis.opsForSet().isMember(SsoRedisKey.REVOCATION_SUBJECTS, sub));

        new LegacyRevocationIndexMigrationService(redis).migrateLegacyRevocationSubjects();

        Set<String> subjects = redis.opsForSet().members(SsoRedisKey.REVOCATION_SUBJECTS);
        assertTrue(subjects.contains(sub));
        assertEquals("complete", redis.opsForValue().get(SsoRedisKey.REVOCATION_SUBJECTS_MIGRATION));
    }

    private String newTestSubject(String scenario) {
        String sub = "sso-test-" + scenario + "-" + UUID.randomUUID().toString().replace("-", "");
        testSubjects.add(sub);
        return sub;
    }

    private SessionService sessionService() {
        SsoProperties properties = new SsoProperties();
        return new SessionService(redis, new ObjectMapper(), properties);
    }
}
