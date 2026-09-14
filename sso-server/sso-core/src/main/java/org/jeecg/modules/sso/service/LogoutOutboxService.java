package org.jeecg.modules.sso.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.jeecg.modules.sso.entity.SsoClient;
import org.jeecg.modules.sso.entity.SsoLogoutEvent;
import org.jeecg.modules.sso.mapper.SsoLogoutEventMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** 登出通知采用 Outbox：持久化已签名 payload，失败按 1m/5m/30m 退避，最多五次。 */
@Service
public class LogoutOutboxService {
    private static final long[] BACKOFF_MINUTES = {1L, 5L, 30L, 30L, 30L};
    private static final int MAX_RETRIES = 5;
    private static final long CLAIM_LEASE_SECONDS = 30L;
    private final SsoLogoutEventMapper eventMapper;
    private final ClientService clientService;
    private final TokenService tokenService;
    private final BackchannelUriValidator backchannelUriValidator;
    private final RestTemplate restTemplate;
    private final Clock clock;

    public LogoutOutboxService(SsoLogoutEventMapper eventMapper, ClientService clientService,
                               TokenService tokenService, BackchannelUriValidator backchannelUriValidator) {
        this(eventMapper, clientService, tokenService, backchannelUriValidator, Clock.systemDefaultZone());
    }

    LogoutOutboxService(SsoLogoutEventMapper eventMapper, ClientService clientService,
                        TokenService tokenService, BackchannelUriValidator backchannelUriValidator, Clock clock) {
        this.eventMapper = eventMapper;
        this.clientService = clientService;
        this.tokenService = tokenService;
        this.backchannelUriValidator = backchannelUriValidator;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void enqueue(String sid, String sub, String clientId) {
        SsoClient client = clientService.getEnabled(clientId);
        if (client == null || blank(client.getBackchannelLogoutUri())) return;
        if (!backchannelUriValidator.isAllowed(client.getBackchannelLogoutUri(), client.getTransport())) {
            throw new IllegalArgumentException("客户端 Backchannel 地址不在允许的企业 CIDR 内");
        }
        String jti = UUID.randomUUID().toString().replace("-", "");
        SsoLogoutEvent event = new SsoLogoutEvent();
        event.setId(jti);
        event.setSid(sid);
        event.setSub(sub);
        event.setClientId(clientId);
        event.setEventType("logout");
        event.setLogoutUri(client.getBackchannelLogoutUri());
        event.setEventPayload(tokenService.issueLogoutToken(jti, sub, sid, clientId));
        event.setStatus(Integer.valueOf(0));
        event.setRetryCount(Integer.valueOf(0));
        event.setNextRetryAt(now());
        event.setCreateTime(now());
        event.setUpdateTime(now());
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // 唯一索引保证同 sid/client/event 幂等。
        }
    }

    @Scheduled(fixedDelay = 10_000L)
    public void deliverDueEvents() {
        LocalDateTime selectionNow = now();
        List<SsoLogoutEvent> events = eventMapper.selectList(new LambdaQueryWrapper<SsoLogoutEvent>()
                .in(SsoLogoutEvent::getStatus, Integer.valueOf(0), Integer.valueOf(2))
                .le(SsoLogoutEvent::getNextRetryAt, selectionNow)
                .and(wrapper -> wrapper.isNull(SsoLogoutEvent::getClaimUntil)
                        .or().lt(SsoLogoutEvent::getClaimUntil, selectionNow))
                .last("LIMIT 100"));
        for (SsoLogoutEvent event : events) {
            String claimToken = UUID.randomUUID().toString().replace("-", "");
            // 每条事件均从实际抢占时刻起计算 30 秒租约；不能复用批次查询的旧时间。
            LocalDateTime claimNow = now();
            if (claim(event, claimToken, claimNow)) {
                event.setClaimToken(claimToken);
                deliverOne(event);
            }
        }
    }

    /** 条件更新是多实例间的原子抢占；租约到期后同一 jti 可安全地被其他实例续投。 */
    private boolean claim(SsoLogoutEvent event, String claimToken, LocalDateTime now) {
        int updated = eventMapper.update(null, new LambdaUpdateWrapper<SsoLogoutEvent>()
                .eq(SsoLogoutEvent::getId, event.getId())
                .in(SsoLogoutEvent::getStatus, Integer.valueOf(0), Integer.valueOf(2))
                .le(SsoLogoutEvent::getNextRetryAt, now)
                .and(wrapper -> wrapper.isNull(SsoLogoutEvent::getClaimUntil)
                        .or().lt(SsoLogoutEvent::getClaimUntil, now))
                .set(SsoLogoutEvent::getClaimToken, claimToken)
                .set(SsoLogoutEvent::getClaimUntil, now.plusSeconds(CLAIM_LEASE_SECONDS))
                .set(SsoLogoutEvent::getUpdateTime, now));
        return updated == 1;
    }

    void deliverOne(SsoLogoutEvent event) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 退避总时长长于单个 Logout Token 的有效期；每次投递使用同一 jti 重新签发短期 Token，
            // 客户端仍可按 jti 幂等去重，同时不会在后续重试收到过期断言。
            event.setEventPayload(tokenService.issueLogoutToken(event.getId(), event.getSub(),
                    event.getSid(), event.getClientId()));
            String body = "{\"logout_token\":\"" + event.getEventPayload() + "\"}";
            ResponseEntity<String> response = restTemplate.postForEntity(event.getLogoutUri(),
                    new HttpEntity<String>(body, headers), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                event.setStatus(Integer.valueOf(1));
                event.setDeliveredAt(now());
                event.setLastError(null);
            } else {
                retry(event, "HTTP " + response.getStatusCodeValue());
            }
        } catch (Exception e) {
            retry(event, e.getMessage());
        }
        event.setUpdateTime(now());
        persistClaimedEvent(event);
    }

    /** 仅拥有该租约的实例能落最终状态，失效工作者不会覆盖后来者的结果。 */
    private void persistClaimedEvent(SsoLogoutEvent event) {
        eventMapper.update(null, new LambdaUpdateWrapper<SsoLogoutEvent>()
                .eq(SsoLogoutEvent::getId, event.getId())
                .eq(SsoLogoutEvent::getClaimToken, event.getClaimToken())
                .set(SsoLogoutEvent::getEventPayload, event.getEventPayload())
                .set(SsoLogoutEvent::getStatus, event.getStatus())
                .set(SsoLogoutEvent::getRetryCount, event.getRetryCount())
                .set(SsoLogoutEvent::getNextRetryAt, event.getNextRetryAt())
                .set(SsoLogoutEvent::getLastError, event.getLastError())
                .set(SsoLogoutEvent::getDeliveredAt, event.getDeliveredAt())
                .set(SsoLogoutEvent::getClaimToken, null)
                .set(SsoLogoutEvent::getClaimUntil, null)
                .set(SsoLogoutEvent::getUpdateTime, event.getUpdateTime()));
    }

    private void retry(SsoLogoutEvent event, String reason) {
        int retries = event.getRetryCount() == null ? 0 : event.getRetryCount().intValue();
        event.setLastError(reason == null ? "unknown" : reason.substring(0, Math.min(900, reason.length())));
        if (retries >= MAX_RETRIES) {
            event.setStatus(Integer.valueOf(9));
            return;
        }
        event.setStatus(Integer.valueOf(2));
        event.setRetryCount(Integer.valueOf(retries + 1));
        event.setNextRetryAt(now().plusMinutes(BACKOFF_MINUTES[Math.min(retries, BACKOFF_MINUTES.length - 1)]));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
