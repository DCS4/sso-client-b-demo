package org.jeecg.modules.sso.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jeecg.modules.sso.entity.SsoLogoutEvent;
import org.jeecg.modules.sso.mapper.SsoLogoutEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutOutboxServiceTest {
    @Mock private SsoLogoutEventMapper eventMapper;
    @Mock private ClientService clientService;
    @Mock private TokenService tokenService;
    @Mock private BackchannelUriValidator callbackUriValidator;

    @BeforeEach
    void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SsoLogoutEvent.class);
    }

    @Test
    void eachEventGetsAFullLeaseWhenAnEarlierCallbackExceedsThirtySeconds() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        MutableClock clock = new MutableClock(start.atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        when(eventMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<SsoLogoutEvent>>any()))
                .thenReturn(Arrays.asList(dueEvent("event-1"), dueEvent("event-2")));
        when(eventMapper.update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<SsoLogoutEvent>>any()))
                .thenReturn(Integer.valueOf(1));

        new DelayedOutboxService(eventMapper, clientService, tokenService, callbackUriValidator, clock)
                .deliverDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<SsoLogoutEvent>> wrappers = updateWrapperCaptor();
        verify(eventMapper, times(2)).update(isNull(), wrappers.capture());
        List<LambdaUpdateWrapper<SsoLogoutEvent>> claims = wrappers.getAllValues();
        assertEquals(start.plusSeconds(30L), latestTime(claims.get(0)));
        assertEquals(start.plusSeconds(61L), latestTime(claims.get(1)));
    }

    private SsoLogoutEvent dueEvent(String id) {
        SsoLogoutEvent event = new SsoLogoutEvent();
        event.setId(id);
        event.setStatus(Integer.valueOf(0));
        event.setNextRetryAt(LocalDateTime.of(2025, 12, 31, 23, 59, 0));
        return event;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<SsoLogoutEvent>> updateWrapperCaptor() {
        return (ArgumentCaptor<LambdaUpdateWrapper<SsoLogoutEvent>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    private LocalDateTime latestTime(LambdaUpdateWrapper<SsoLogoutEvent> wrapper) {
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        return values.values().stream().filter(LocalDateTime.class::isInstance).map(LocalDateTime.class::cast)
                .max(Comparator.naturalOrder()).orElseThrow(() -> new AssertionError("claim 参数缺少租约时间"));
    }

    private static final class DelayedOutboxService extends LogoutOutboxService {
        private final MutableClock clock;
        private int deliveryCount;

        private DelayedOutboxService(SsoLogoutEventMapper eventMapper, ClientService clientService,
                                    TokenService tokenService, BackchannelUriValidator callbackUriValidator,
                                    MutableClock clock) {
            super(eventMapper, clientService, tokenService, callbackUriValidator, clock);
            this.clock = clock;
        }

        @Override
        void deliverOne(SsoLogoutEvent event) {
            if (deliveryCount++ == 0) {
                clock.advance(Duration.ofSeconds(31L));
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId value) {
            return new MutableClock(instant, value);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
