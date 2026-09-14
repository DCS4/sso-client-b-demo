package org.jeecg.modules.system.sso;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.system.entity.SysUserStatusEvent;
import org.jeecg.modules.system.mapper.SysUserStatusEventMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * System 到 SSO 的用户状态事件 Outbox。
 *
 * <p>System 和 SSO 固定部署在同一主机；投递只走 127.0.0.1:19089，并携带
 * X-INNER-TOKEN。失败事件保留在本地数据库中，供定时任务指数退避重试。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "sso.integration", name = "enabled", havingValue = "true")
public class UserStatusOutboxService {
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String USER_ENABLED = "USER_ENABLED";
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_DELIVERED = 1;
    private static final int STATUS_RETRY = 2;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int[] RETRY_SECONDS = {60, 300, 1800, 1800, 1800};
    private static final long CLAIM_LEASE_MILLIS = 30_000L;

    private final SysUserStatusEventMapper eventMapper;
    private final RestTemplate restTemplate;

    @Value("${sso.system.inner-url:http://127.0.0.1:19089}")
    private String innerUrl;
    @Value("${sso.system.inner-token:}")
    private String innerToken;

    public UserStatusOutboxService(SysUserStatusEventMapper eventMapper) {
        this.eventMapper = eventMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 在调用方的用户状态变更事务中写入事件。 */
    public void record(String subject, String eventType) {
        if (subject == null || subject.trim().isEmpty()) {
            throw new IllegalArgumentException("用户 subject 不能为空");
        }
        if (!USER_DISABLED.equals(eventType) && !USER_DELETED.equals(eventType)
                && !USER_ENABLED.equals(eventType)) {
            throw new IllegalArgumentException("不支持的用户状态事件: " + eventType);
        }
        Date now = new Date();
        SysUserStatusEvent event = new SysUserStatusEvent();
        event.setEventType(eventType);
        event.setSubject(subject);
        event.setStatus(STATUS_PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(now);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        eventMapper.insert(event);
    }

    @Scheduled(fixedDelayString = "${sso.system.outbox-fixed-delay-ms:10000}")
    public void deliverDueEvents() {
        Date now = new Date();
        List<SysUserStatusEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<SysUserStatusEvent>()
                        .in(SysUserStatusEvent::getStatus, STATUS_PENDING, STATUS_RETRY)
                        .le(SysUserStatusEvent::getNextRetryAt, now)
                        .and(wrapper -> wrapper.isNull(SysUserStatusEvent::getClaimUntil)
                                .or().lt(SysUserStatusEvent::getClaimUntil, now))
                        .orderByAsc(SysUserStatusEvent::getCreateTime)
                        .orderByAsc(SysUserStatusEvent::getId)
                        .last("LIMIT " + MAX_BATCH_SIZE));
        for (SysUserStatusEvent event : events) {
            String claimToken = UUID.randomUUID().toString().replace("-", "");
            if (!hasUndeliveredEarlierEvent(event) && claim(event, claimToken, now)) {
                event.setClaimToken(claimToken);
                deliver(event);
            }
        }
    }

    /** 条件更新实现跨 System 实例的原子抢占，避免同一状态事件被并发覆盖。 */
    private boolean claim(SysUserStatusEvent event, String claimToken, Date now) {
        int updated = eventMapper.update(null, new LambdaUpdateWrapper<SysUserStatusEvent>()
                .eq(SysUserStatusEvent::getId, event.getId())
                .in(SysUserStatusEvent::getStatus, STATUS_PENDING, STATUS_RETRY)
                .le(SysUserStatusEvent::getNextRetryAt, now)
                .and(wrapper -> wrapper.isNull(SysUserStatusEvent::getClaimUntil)
                        .or().lt(SysUserStatusEvent::getClaimUntil, now))
                .set(SysUserStatusEvent::getClaimToken, claimToken)
                .set(SysUserStatusEvent::getClaimUntil, new Date(now.getTime() + CLAIM_LEASE_MILLIS))
                .set(SysUserStatusEvent::getUpdateTime, now));
        return updated == 1;
    }

    /** 同一用户的状态事件必须按事务产生顺序送达，不能让解冻越过重试中的禁用。 */
    private boolean hasUndeliveredEarlierEvent(SysUserStatusEvent event) {
        if (event.getCreateTime() == null || event.getId() == null) {
            return false;
        }
        Long count = eventMapper.selectCount(new LambdaQueryWrapper<SysUserStatusEvent>()
                .eq(SysUserStatusEvent::getSubject, event.getSubject())
                .in(SysUserStatusEvent::getStatus, STATUS_PENDING, STATUS_RETRY)
                .and(wrapper -> wrapper.lt(SysUserStatusEvent::getCreateTime, event.getCreateTime())
                        .or().eq(SysUserStatusEvent::getCreateTime, event.getCreateTime())
                        .lt(SysUserStatusEvent::getId, event.getId())));
        return count != null && count.longValue() > 0L;
    }

    private void deliver(SysUserStatusEvent event) {
        try {
            Map<String, String> body = new HashMap<String, String>();
            body.put("sub", event.getSubject());
            body.put("event_type", event.getEventType());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-INNER-TOKEN", innerToken);
            restTemplate.postForEntity(innerUrl + "/internal/sso/revokeUserSessions",
                    new HttpEntity<Map<String, String>>(body, headers), Void.class);
            markDelivered(event);
        } catch (Exception ex) {
            markRetry(event, ex);
        }
    }

    private void markDelivered(SysUserStatusEvent event) {
        eventMapper.update(null, new LambdaUpdateWrapper<SysUserStatusEvent>()
                .eq(SysUserStatusEvent::getId, event.getId())
                .eq(SysUserStatusEvent::getClaimToken, event.getClaimToken())
                .set(SysUserStatusEvent::getStatus, STATUS_DELIVERED)
                .set(SysUserStatusEvent::getLastError, null)
                .set(SysUserStatusEvent::getClaimToken, null)
                .set(SysUserStatusEvent::getClaimUntil, null)
                .set(SysUserStatusEvent::getUpdateTime, new Date()));
    }

    private void markRetry(SysUserStatusEvent event, Exception ex) {
        int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        int retryIndex = Math.min(retryCount - 1, RETRY_SECONDS.length - 1);
        Date now = new Date();
        eventMapper.update(null, new LambdaUpdateWrapper<SysUserStatusEvent>()
                .eq(SysUserStatusEvent::getId, event.getId())
                .eq(SysUserStatusEvent::getClaimToken, event.getClaimToken())
                .set(SysUserStatusEvent::getStatus, STATUS_RETRY)
                .set(SysUserStatusEvent::getRetryCount, retryCount)
                .set(SysUserStatusEvent::getNextRetryAt,
                        new Date(now.getTime() + RETRY_SECONDS[retryIndex] * 1000L))
                .set(SysUserStatusEvent::getLastError,
                        truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()))
                .set(SysUserStatusEvent::getClaimToken, null)
                .set(SysUserStatusEvent::getClaimUntil, null)
                .set(SysUserStatusEvent::getUpdateTime, now));
        if (retryCount >= RETRY_SECONDS.length) {
            log.error("用户状态事件投递持续失败，eventId={}, subject={}, retryCount={}",
                    event.getId(), event.getSubject(), retryCount, ex);
        } else {
            log.warn("用户状态事件投递失败，eventId={}, subject={}, retryCount={}",
                    event.getId(), event.getSubject(), retryCount, ex);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
