package org.jeecg.modules.sso.controller;

import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jeecg.modules.sso.config.SsoProperties;
import org.jeecg.modules.sso.dto.SsoDtos;
import org.jeecg.modules.sso.dto.SsoDtos.CodePayload;
import org.jeecg.modules.sso.dto.SsoDtos.ExchangeRequest;
import org.jeecg.modules.sso.dto.SsoDtos.LogoutPayload;
import org.jeecg.modules.sso.dto.SsoDtos.LogoutRequest;
import org.jeecg.modules.sso.dto.SsoDtos.PendingPayload;
import org.jeecg.modules.sso.dto.SsoDtos.SessionData;
import org.jeecg.modules.sso.entity.SsoClient;
import org.jeecg.modules.sso.service.ClientService;
import org.jeecg.modules.sso.service.BackchannelUriValidator;
import org.jeecg.modules.sso.service.CodeService;
import org.jeecg.modules.sso.service.HmacVerifier;
import org.jeecg.modules.sso.service.LogoutService;
import org.jeecg.modules.sso.service.PendingService;
import org.jeecg.modules.sso.service.RedirectBuilder;
import org.jeecg.modules.sso.service.SessionService;
import org.jeecg.modules.sso.service.SigningKeyService;
import org.jeecg.modules.sso.service.TokenService;
import org.jeecg.modules.sso.util.SsoCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** 对外协议接口。Nginx 仅代理本类的 /sso/v1/**，不会暴露 /internal/**。 */
@RestController
@RequestMapping("/sso/v1")
public class SsoProtocolController {
    private final ClientService clientService;
    private final BackchannelUriValidator callbackUriValidator;
    private final SessionService sessionService;
    private final PendingService pendingService;
    private final CodeService codeService;
    private final RedirectBuilder redirectBuilder;
    private final HmacVerifier hmacVerifier;
    private final TokenService tokenService;
    private final LogoutService logoutService;
    private final SigningKeyService signingKeyService;
    private final SsoProperties properties;
    private final StringRedisTemplate redis;

    public SsoProtocolController(ClientService clientService, BackchannelUriValidator callbackUriValidator,
            SessionService sessionService,
            PendingService pendingService, CodeService codeService, RedirectBuilder redirectBuilder,
            HmacVerifier hmacVerifier, TokenService tokenService, LogoutService logoutService,
            SigningKeyService signingKeyService, SsoProperties properties, StringRedisTemplate redis) {
        this.clientService = clientService; this.callbackUriValidator = callbackUriValidator;
        this.sessionService = sessionService;
        this.pendingService = pendingService; this.codeService = codeService;
        this.redirectBuilder = redirectBuilder; this.hmacVerifier = hmacVerifier;
        this.tokenService = tokenService; this.logoutService = logoutService;
        this.signingKeyService = signingKeyService; this.properties = properties; this.redis = redis;
    }

    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(@RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri, @RequestParam String state,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam(value = "code_challenge_method", defaultValue = "S256") String challengeMethod,
            HttpServletRequest request) {
        SsoClient client = clientService.getEnabled(clientId);
        if (client == null || !clientService.redirectUriRegistered(clientId, redirectUri)
                || !callbackUriValidator.isAllowed(redirectUri, client.getTransport())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client", "客户端未登记或回调地址不匹配");
        }
        if (!"S256".equals(challengeMethod) || !StringUtils.hasText(codeChallenge) || !StringUtils.hasText(state)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "state 与 S256 PKCE 参数必填");
        }
        String cookieSid = SsoCookie.readSid(request);
        SessionData session = sessionService.requireActive(cookieSid);
        PendingPayload pending = pending(clientId, redirectUri, state, codeChallenge);
        if (session != null) {
            String code = codeService.issue(session.getSid(), session.getSub(), pending);
            return redirect(redirectBuilder.build(redirectUri, code, state));
        }
        // 重试计数只属于一次授权尝试，绝不能按 Nginx 回环地址或企业 NAT 地址聚合用户。
        String key = clientId + ":" + sha256Hex(state);
        if (pendingService.incrementAndExceeded(key)) {
            return error(HttpStatus.BAD_REQUEST, "too_many_attempts", "多次未能建立登录，请稍后重试");
        }
        return redirect(properties.getPortalLoginUrl() + "?_sso=" + pendingService.save(pending));
    }

    @PostMapping(value = "/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exchange(@RequestBody ExchangeRequest body, HttpServletRequest request) {
        String clientId = hmacVerifier.verify(request, rawBody(request));
        if (clientId == null) return error(HttpStatus.UNAUTHORIZED, "invalid_signature", "客户端签名校验失败");
        CodePayload code = codeService.consume(body.getCode());
        if (code == null) return error(HttpStatus.BAD_REQUEST, "invalid_grant", "授权码无效或已被消费");
        if (!clientId.equals(code.getClientId()) || !code.getRedirectUri().equals(body.getRedirectUri())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "授权码绑定不匹配");
        }
        if (!pkceMatches(code.getCodeChallenge(), body.getCodeVerifier())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "PKCE校验失败");
        }
        SessionData session = sessionService.requireActive(code.getSid());
        if (session == null || !session.getSub().equals(code.getSub())) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_session", "全局会话已失效");
        }
        if (!sessionService.registerClient(session.getSid(), clientId, session.getAbsoluteExpiresAt())) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_session", "全局会话已失效");
        }
        String assertion = tokenService.issueIdentityAssertion(session, clientId,
                request.getHeader(HmacVerifier.HEADER_NONCE));
        return ResponseEntity.ok(new SsoDtos.ExchangeResponse(assertion));
    }

    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> postLogout(@RequestBody LogoutRequest body, HttpServletRequest request) {
        String clientId = hmacVerifier.verify(request, rawBody(request));
        if (clientId == null) return error(HttpStatus.UNAUTHORIZED, "invalid_signature", "客户端签名校验失败");
        SsoClient client = clientService.getEnabled(clientId);
        if (client == null || !StringUtils.hasText(body.getSid())
                || !StringUtils.hasText(body.getPostLogoutRedirectUri())
                || !body.getPostLogoutRedirectUri().equals(client.getPostLogoutRedirectUri())
                || !callbackUriValidator.isAllowed(body.getPostLogoutRedirectUri(), client.getTransport())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "登出请求绑定不匹配");
        }
        SessionData active = sessionService.requireActive(body.getSid());
        if (active == null || !sessionService.getSessionClients(body.getSid()).contains(clientId)) {
            return error(HttpStatus.FORBIDDEN, "access_denied", "客户端无权注销未登录过的会话");
        }
        String logoutRequest = logoutService.revokeAndCreateBrowserRequest(body.getSid(), clientId,
                body.getPostLogoutRedirectUri());
        Map<String, String> response = new HashMap<String, String>();
        response.put("logout_request", logoutRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/logout")
    public void browserLogout(@RequestParam("request") String requestId, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        LogoutPayload payload = logoutService.consumeBrowserRequest(requestId);
        if (payload == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "登出请求已过期或无效");
            return;
        }
        if (sameSid(payload.getSid(), SsoCookie.readSid(request))) {
            SsoCookie.clear(response);
        }
        response.sendRedirect(payload.getPostLogoutRedirectUri());
    }

    @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(signingKeyService.jwksJson());
    }

    @GetMapping("/health/readiness")
    public ResponseEntity<Map<String, String>> readiness() {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            if ("PONG".equals(connection.ping()) && signingKeyService.currentPublicKey() != null) {
                Map<String, String> result = new HashMap<String, String>(); result.put("status", "UP");
                return ResponseEntity.ok(result);
            }
        } catch (Exception ignored) { }
        Map<String, String> result = new HashMap<String, String>(); result.put("status", "DOWN");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }

    @PostMapping(value = "/session/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> sessionStatus(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String clientId = hmacVerifier.verify(request, rawBody(request));
        if (clientId == null) return error(HttpStatus.UNAUTHORIZED, "invalid_signature", "客户端签名校验失败");
        String sid = body.get("sid");
        SessionData session = sessionService.requireActive(sid);
        boolean active = session != null && sessionService.getSessionClients(sid).contains(clientId);
        Map<String, String> response = new HashMap<String, String>();
        response.put("session_status", tokenService.issueSessionStatus(sid, active, clientId,
                request.getHeader(HmacVerifier.HEADER_NONCE)));
        return ResponseEntity.ok(response);
    }

    private PendingPayload pending(String clientId, String redirectUri, String state, String challenge) {
        PendingPayload value = new PendingPayload();
        value.setClientId(clientId); value.setRedirectUri(redirectUri); value.setState(state); value.setCodeChallenge(challenge);
        return value;
    }
    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
    private ResponseEntity<SsoDtos.ErrorResponse> error(HttpStatus status, String code, String description) {
        return ResponseEntity.status(status).body(new SsoDtos.ErrorResponse(code, description));
    }
    private byte[] rawBody(HttpServletRequest request) {
        return request instanceof ContentCachingRequestWrapper
                ? ((ContentCachingRequestWrapper) request).getContentAsByteArray() : new byte[0];
    }
    private boolean pkceMatches(String expected, String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (verifier == null ? "" : verifier).getBytes("UTF-8"));
            byte[] actual = Base64.getUrlEncoder().withoutPadding().encode(digest);
            return MessageDigest.isEqual(expected.getBytes("UTF-8"), actual);
        } catch (Exception e) { return false; }
    }
    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", Integer.valueOf(item & 0xff)));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("state 哈希失败", e);
        }
    }
    private boolean sameSid(String first, String second) {
        try {
            return first != null && second != null && MessageDigest.isEqual(
                    first.getBytes("UTF-8"), second.getBytes("UTF-8"));
        } catch (Exception e) { return false; }
    }
}
