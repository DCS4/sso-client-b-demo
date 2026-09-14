package org.jeecg.modules.sso.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {
    @Mock private SessionService sessionService;
    @Mock private LogoutOutboxService outboxService;
    @Mock private StringRedisTemplate redis;
    @Mock private SsoProperties properties;

    @Test
    void retainsRevocationTombstoneWhenAnyOutboxInsertFails() {
        SessionData session = session("sid-1", "1001");
        when(sessionService.revoke("sid-1")).thenReturn(SessionService.RevokedSession.snapshot(session,
                new LinkedHashSet<String>(Arrays.asList("client-a", "client-b"))));
        doAnswer(invocation -> {
            if ("client-b".equals(invocation.getArgument(2))) {
                throw new IllegalStateException("database unavailable");
            }
            return null;
        }).when(outboxService).enqueue(anyString(), anyString(), anyString());

        LogoutService service = new LogoutService(sessionService, outboxService, redis, null, properties);

        assertThrows(IllegalStateException.class, () -> service.revokeAndNotify("sid-1"));
        verify(outboxService).enqueue("sid-1", "1001", "client-a");
        verify(sessionService, never()).clearRevocationTombstone(anyString(), anyString());
    }

    @Test
    void clearsTombstoneOnlyAfterEveryOutboxEventIsPersisted() {
        SessionData session = session("sid-2", "1002");
        when(sessionService.revoke("sid-2")).thenReturn(SessionService.RevokedSession.snapshot(session,
                new LinkedHashSet<String>(Arrays.asList("client-a", "client-b"))));
        LogoutService service = new LogoutService(sessionService, outboxService, redis, null, properties);

        assertSame(session, service.revokeAndNotify("sid-2"));

        InOrder order = inOrder(outboxService, sessionService);
        order.verify(outboxService).enqueue("sid-2", "1002", "client-a");
        order.verify(outboxService).enqueue("sid-2", "1002", "client-b");
        order.verify(sessionService).clearRevocationTombstone("sid-2", "1002");
    }

    private SessionData session(String sid, String sub) {
        SessionData session = new SessionData();
        session.setSid(sid);
        session.setSub(sub);
        return session;
    }
}
