package com.dcs4.ssoclient;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.*;
import javax.servlet.http.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class DemoController {
  private final SsoConfig config;
  private final SsoClient client;
  private final TokenVerifier verifier;
  private final SessionStore store;
  private final JdbcTemplate db;

  public DemoController(
      SsoConfig c, SsoClient s, TokenVerifier v, SessionStore st, JdbcTemplate d) {
    config = c;
    client = s;
    verifier = v;
    store = st;
    db = d;
  }

  @GetMapping("/api/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Collections.singletonMap("token", token.getToken());
  }

  @GetMapping("/api/auth/login")
  public void login(HttpServletRequest req, HttpServletResponse res) throws Exception {
    HttpSession s = req.getSession();
    String state = Protocol.random(), v = Protocol.random();
    s.setAttribute("state", state);
    s.setAttribute("verifier", v);
    s.setAttribute("started", System.currentTimeMillis());
    res.sendRedirect(
        UriComponentsBuilder.fromHttpUrl(config.baseUrl + "/authorize")
            .queryParam("client_id", config.clientId)
            .queryParam("redirect_uri", config.callback)
            .queryParam("state", state)
            .queryParam("code_challenge", Protocol.challenge(v))
            .queryParam("code_challenge_method", "S256")
            .build()
            .encode()
            .toUriString());
  }

  @GetMapping("/api/auth/callback")
  public void callback(
      @RequestParam String code,
      @RequestParam String state,
      HttpServletRequest req,
      HttpServletResponse res)
      throws Exception {
    HttpSession s = req.getSession(false);
    if (s == null) throw new IllegalArgumentException("登录事务不存在");
    String v;
    synchronized (s) {
      Object expected = s.getAttribute("state"), started = s.getAttribute("started");
      v = (String) s.getAttribute("verifier");
      s.removeAttribute("state");
      s.removeAttribute("verifier");
      s.removeAttribute("started");
      if (!state.equals(expected)
          || started == null
          || System.currentTimeMillis() - (Long) started > 600000
          || v == null) throw new IllegalArgumentException("登录事务过期或 state 不匹配");
    }
    Map<String, String> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("redirect_uri", config.callback);
    body.put("code_verifier", v);
    JWTClaimsSet c = client.signed("/exchange", body, "identity_assertion", "identity", null);
    store.once(c.getJWTID(), c.getExpirationTime().getTime() / 1000);
    try {
      db.update(
          "INSERT INTO demo_user (sso_subject,username,role,enabled) VALUES (?,?,?,?)",
          c.getSubject(),
          c.getStringClaim("username"),
          "DEMO_USER",
          true);
    } catch (DuplicateKeyException ignored) {
    }
    s.invalidate();
    s = req.getSession(true);
    s.setAttribute("sub", c.getSubject());
    s.setAttribute("sid", c.getStringClaim("sid"));
    s.setAttribute("expires", c.getLongClaim("session_expires_at"));
    try {
      user(req);
      store.register(c.getStringClaim("sid"), s);
    } catch (Exception e) {
      try {
        s.invalidate();
      } catch (IllegalStateException ignored) {
      }
      throw e;
    }
    res.sendRedirect(config.returnUrl);
  }

  private Map<String, Object> user(HttpServletRequest req) {
    HttpSession s = req.getSession(false);
    if (s == null || s.getAttribute("sub") == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    if ((Long) s.getAttribute("expires") <= System.currentTimeMillis() / 1000) {
      s.invalidate();
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话过期");
    }
    Map<String, Object> row =
        db.queryForMap(
            "SELECT sso_subject,username,role,enabled FROM demo_user WHERE sso_subject=?",
            s.getAttribute("sub"));
    if (!Boolean.TRUE.equals(row.get("enabled"))
        && !(row.get("enabled") instanceof Number
            && ((Number) row.get("enabled")).intValue() == 1)) {
      s.invalidate();
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本地用户禁用");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sub", s.getAttribute("sub"));
    out.put("username", row.get("username"));
    out.put("role", row.get("role"));
    out.put("sessionExpiresAt", s.getAttribute("expires"));
    return out;
  }

  @GetMapping("/api/auth/me")
  public Map<String, Object> me(HttpServletRequest r) {
    return user(r);
  }

  @GetMapping("/api/demo/business")
  public Map<String, String> business(HttpServletRequest r) {
    user(r);
    return Collections.singletonMap("message", "普通业务接口调用成功");
  }

  @PostMapping("/api/demo/sensitive")
  public Map<String, String> sensitive(HttpServletRequest r) throws Exception {
    user(r);
    String sid = (String) r.getSession(false).getAttribute("sid");
    JWTClaimsSet c =
        client.signed(
            "/session/status",
            Collections.singletonMap("sid", sid),
            "session_status",
            "status",
            sid);
    store.once(c.getJWTID(), c.getExpirationTime().getTime() / 1000);
    if (!Boolean.TRUE.equals(c.getBooleanClaim("active"))) {
      store.revoke(sid);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SSO 会话已失效");
    }
    return Collections.singletonMap("message", "SSO 状态验签通过，敏感操作成功");
  }

  @PostMapping("/api/auth/logout/local")
  public void local(HttpServletRequest r) {
    HttpSession s = r.getSession(false);
    if (s != null) s.invalidate();
  }

  @PostMapping("/api/auth/logout/global")
  public Map<String, String> global(HttpServletRequest r) throws Exception {
    user(r);
    HttpSession s = r.getSession(false);
    String sid = (String) s.getAttribute("sid");
    s.invalidate();
    Map<String, String> body = new LinkedHashMap<>();
    body.put("sid", sid);
    body.put("post_logout_redirect_uri", config.returnUrl);
    String ticket = client.post("/logout", body, Protocol.random()).get("logout_request");
    if (ticket == null || ticket.isEmpty()) throw new IllegalArgumentException("SSO 未返回登出票据");
    return Collections.singletonMap(
        "redirect",
        UriComponentsBuilder.fromHttpUrl(config.baseUrl + "/logout")
            .queryParam("request", ticket)
            .build()
            .encode()
            .toUriString());
  }

  @PostMapping(value = "/api/sso/backchannel-logout", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> backchannel(@RequestBody Map<String, String> body) throws Exception {
    JWTClaimsSet c = verifier.verify(body.get("logout_token"), "logout", null, null);
    // 撤销本身幂等；重复投递也执行，以避免先标记 jti 后清理失败。
    store.revoke(c.getStringClaim("sid"));
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Collections.singletonMap("message", "协议校验失败，请重新发起登录"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> failure(Exception e) {
    return ResponseEntity.status(503)
        .body(Collections.singletonMap("message", "操作未完成，请检查服务配置及 SSO 可用性；若正在退出，本地会话已清理"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatus())
        .body(Collections.singletonMap("message", e.getReason()));
  }
}
