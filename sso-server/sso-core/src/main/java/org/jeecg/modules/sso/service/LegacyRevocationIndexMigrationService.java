package org.jeecg.modules.sso.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.sso.constant.SsoRedisKey;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 兼容首次升级前已存在的撤销墓碑。旧版已有按 subject 分组的 SID 索引，但没有全局
 * subject 索引；本服务启动时以 SCAN 非阻塞地将其回填一次。
 */
@Slf4j
@Service
public class LegacyRevocationIndexMigrationService {
    private static final ScanOptions LEGACY_INDEX_SCAN = ScanOptions.scanOptions()
            .match(SsoRedisKey.USER_REVOCATIONS + "*").count(200).build();
    private final StringRedisTemplate redis;

    public LegacyRevocationIndexMigrationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void migrateLegacyRevocationSubjects() {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(SsoRedisKey.REVOCATION_SUBJECTS_MIGRATION))) {
                return;
            }
            Set<String> subjects = redis.execute(new RedisCallback<Set<String>>() {
                @Override
                public Set<String> doInRedis(RedisConnection connection) {
                    Set<String> values = new LinkedHashSet<String>();
                    try (Cursor<byte[]> cursor = connection.scan(LEGACY_INDEX_SCAN)) {
                        while (cursor.hasNext()) {
                            String key = new String(cursor.next(), StandardCharsets.UTF_8);
                            if (key.startsWith(SsoRedisKey.USER_REVOCATIONS)) {
                                String sub = key.substring(SsoRedisKey.USER_REVOCATIONS.length());
                                if (!sub.trim().isEmpty()) {
                                    values.add(sub);
                                }
                            }
                        }
                    }
                    return values;
                }
            });
            if (subjects != null) {
                for (String sub : subjects) {
                    String key = SsoRedisKey.USER_REVOCATIONS + sub;
                    Long count = redis.opsForSet().size(key);
                    if (count != null && count.longValue() > 0L) {
                        redis.opsForSet().add(SsoRedisKey.REVOCATION_SUBJECTS, sub);
                    }
                }
            }
            // 仅在完整 SCAN 成功后写标记；中断时下次启动可幂等重试。
            redis.opsForValue().set(SsoRedisKey.REVOCATION_SUBJECTS_MIGRATION, "complete");
        } catch (RuntimeException ex) {
            log.warn("回填旧版 SSO 撤销索引失败；本次不写迁移完成标记", ex);
        }
    }

    /** Redis 暂不可用导致启动回填失败时，无需重启服务也会重试；完成标记存在后该任务立即返回。 */
    @Scheduled(fixedDelayString = "${sso.legacy-revocation-index-migration-fixed-delay-ms:60000}")
    public void retryLegacyRevocationSubjectsMigration() {
        migrateLegacyRevocationSubjects();
    }
}
