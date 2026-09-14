package org.jeecg.modules.sso.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevocationRecoveryServiceTest {
    @Mock private SessionService sessionService;
    @Mock private LogoutService logoutService;

    @Test
    void replaysEveryPendingSidAndKeepsScanningAfterOneFailure() {
        when(sessionService.getPendingRevocationSubjects()).thenReturn(
                new LinkedHashSet<String>(Arrays.asList("1001", "1002")));
        when(sessionService.getPendingRevocationSids("1001")).thenReturn(
                new LinkedHashSet<String>(Arrays.asList("sid-a", "sid-b")));
        when(sessionService.getPendingRevocationSids("1002")).thenReturn(
                new LinkedHashSet<String>(Arrays.asList("sid-c")));
        doThrow(new IllegalStateException("database unavailable"))
                .when(logoutService).revokeAndNotify("sid-a");

        new RevocationRecoveryService(sessionService, logoutService).recoverPendingRevocations();

        verify(logoutService).revokeAndNotify("sid-a");
        verify(logoutService).revokeAndNotify("sid-b");
        verify(logoutService).revokeAndNotify("sid-c");
    }
}
