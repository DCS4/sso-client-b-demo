package org.jeecg.modules.sso.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.dto.SsoDtos.BeginSessionRequest;
import org.jeecg.modules.sso.dto.SsoDtos.BeginSessionResponse;
import org.jeecg.modules.sso.dto.SsoDtos.PendingPayload;
import org.jeecg.modules.sso.dto.SsoDtos.RevokeUserRequest;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.jeecg.modules.sso.service.CodeService;
import org.jeecg.modules.sso.service.LogoutService;
import org.jeecg.modules.sso.service.PendingService;
import org.jeecg.modules.sso.service.RedirectBuilder;
import org.jeecg.modules.sso.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** System 与门户共用的同机回环接口；由 InnerAuthFilter 强制保护。 */
@RestController
@RequestMapping("/internal/sso")
public class InnerSsoController {
    private static final String USER_DISABLED = "USER_DISABLED";
    private static final String USER_DELETED = "USER_DELETED";
    private static final String USER_ENABLED = "USER_ENABLED";
    private final SessionService sessionService;
    private final PendingService pendingService;
    private final CodeService codeService;
    private final RedirectBuilder redirectBuilder;
    private final LogoutService logoutService;
    private final SsoProperties properties;
    public InnerSsoController(SessionService sessionService, PendingService pendingService, CodeService codeService,
            RedirectBuilder redirectBuilder, LogoutService logoutService, SsoProperties properties) {
        this.sessionService = sessionService; this.pendingService = pendingService; this.codeService = codeService;
        this.redirectBuilder = redirectBuilder; this.logoutService = logoutService; this.properties = properties;
    }
    @PostMapping("/beginSession")
    public ResponseEntity<?> beginSession(@RequestBody BeginSessionRequest request) {
        if (!StringUtils.hasText(request.getSub()) || !StringUtils.hasText(request.getUsername())) {
            return ResponseEntity.badRequest().body("sub 和 username 必填");
        }
        if (StringUtils.hasText(request.getOldSid())) {
            SessionData old = sessionService.requireActive(request.getOldSid());
            if (old != null && request.getSub().equals(old.getSub())) logoutService.revokeAndNotify(old.getSid());
        }
        SessionData session = sessionService.create(request.getSub(), request.getUsername());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("用户已禁用，不能创建 SSO 会话");
        }
        String redirectUrl = null;
        PendingPayload pending = pendingService.take(request.getPending());
        if (pending != null) {
            String code = codeService.issue(session.getSid(), session.getSub(), pending);
            redirectUrl = redirectBuilder.build(pending.getRedirectUri(), code, pending.getState());
        }
        return ResponseEntity.ok(new BeginSessionResponse(session.getSid(), session.getAbsoluteExpiresAt(), redirectUrl));
    }
    @PostMapping("/revokeUserSessions")
    public ResponseEntity<Map<String, Object>> revokeUserSessions(@RequestBody RevokeUserRequest request) {
        if (!StringUtils.hasText(request.getSub())) {
            return ResponseEntity.badRequest().build();
        }
        String eventType = StringUtils.hasText(request.getEventType()) ? request.getEventType() : USER_DISABLED;
        if (USER_ENABLED.equals(eventType)) {
            sessionService.enableSubject(request.getSub());
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("sub", request.getSub()); result.put("revoked", Integer.valueOf(0));
            return ResponseEntity.ok(result);
        }
        if (!USER_DISABLED.equals(eventType) && !USER_DELETED.equals(eventType)) {
            return ResponseEntity.badRequest().build();
        }
        // 先设置 subject-disabled，再取 SID 快照；并发 beginSession 会在同一 Redis 原子边界被拒绝。
        List<String> sids = sessionService.disableSubject(request.getSub());
        int count = 0;
        for (String sid : sids) if (logoutService.revokeAndNotify(sid) != null) count++;
        Map<String, Object> result = new HashMap<String, Object>(); result.put("sub", request.getSub()); result.put("revoked", Integer.valueOf(count));
        return ResponseEntity.ok(result);
    }
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> portalLogout(@RequestBody Map<String, String> request) {
        String sid = request.get("sid");
        String browserRequest = logoutService.revokeAndCreateBrowserRequest(sid, "portal", properties.getPortalPostLogoutUrl());
        Map<String, String> result = new HashMap<String, String>(); result.put("logout_request", browserRequest);
        return ResponseEntity.ok(result);
    }
}
