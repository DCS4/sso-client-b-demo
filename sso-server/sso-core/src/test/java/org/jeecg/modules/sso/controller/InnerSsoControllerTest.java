package org.jeecg.modules.sso.controller;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.dto.SsoDtos.BeginSessionRequest;
import org.jeecg.modules.sso.dto.SsoDtos.RevokeUserRequest;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.jeecg.modules.sso.service.CodeService;
import org.jeecg.modules.sso.service.LogoutService;
import org.jeecg.modules.sso.service.PendingService;
import org.jeecg.modules.sso.service.RedirectBuilder;
import org.jeecg.modules.sso.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InnerSsoControllerTest {
    @Mock private SessionService sessionService;
    @Mock private PendingService pendingService;
    @Mock private CodeService codeService;
    @Mock private RedirectBuilder redirectBuilder;
    @Mock private LogoutService logoutService;
    @Mock private SsoProperties properties;

    @Test
    void rejectsBeginSessionWhenDisableBarrierWonTheRace() {
        BeginSessionRequest request = new BeginSessionRequest();
        request.setSub("1001");
        request.setUsername("zhangsan");
        when(sessionService.create("1001", "zhangsan")).thenReturn(null);

        ResponseEntity<?> response = controller().beginSession(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void disablesSubjectBeforeRevokingTheCapturedSessions() {
        RevokeUserRequest request = statusRequest("1001", "USER_DISABLED");
        when(sessionService.disableSubject("1001")).thenReturn(Arrays.asList("sid-1", "sid-2"));
        when(logoutService.revokeAndNotify("sid-1")).thenReturn(session("sid-1", "1001"));
        when(logoutService.revokeAndNotify("sid-2")).thenReturn(session("sid-2", "1001"));

        ResponseEntity<Map<String, Object>> response = controller().revokeUserSessions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Integer.valueOf(2), response.getBody().get("revoked"));
        InOrder order = inOrder(sessionService, logoutService);
        order.verify(sessionService).disableSubject("1001");
        order.verify(logoutService).revokeAndNotify("sid-1");
        order.verify(logoutService).revokeAndNotify("sid-2");
    }

    @Test
    void enablesSubjectWithoutRevokingNewSessions() {
        RevokeUserRequest request = statusRequest("1001", "USER_ENABLED");

        ResponseEntity<Map<String, Object>> response = controller().revokeUserSessions(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Integer.valueOf(0), response.getBody().get("revoked"));
        verify(sessionService).enableSubject("1001");
        verify(logoutService, never()).revokeAndNotify("sid-1");
    }

    private InnerSsoController controller() {
        return new InnerSsoController(sessionService, pendingService, codeService, redirectBuilder, logoutService, properties);
    }

    private RevokeUserRequest statusRequest(String sub, String eventType) {
        RevokeUserRequest request = new RevokeUserRequest();
        request.setSub(sub);
        request.setEventType(eventType);
        return request;
    }

    private SessionData session(String sid, String sub) {
        SessionData session = new SessionData();
        session.setSid(sid);
        session.setSub(sub);
        return session;
    }
}
