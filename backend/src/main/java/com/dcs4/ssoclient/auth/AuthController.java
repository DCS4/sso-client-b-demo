package com.dcs4.ssoclient.auth;

import com.dcs4.ssoclient.auth.PageAccessService.LogoutSummary;
import com.dcs4.ssoclient.config.SsoConfig;
import com.dcs4.ssoclient.sso.SsoGateway;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 接入入口：固定回调、CSRF Token 和本地退出。HTTP 路径与演示首页解耦。 */
@RestController
public class AuthController {
  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private final PageAccessService access;
  private final PageCatalog pages;
  private final SsoGateway sso;
  private final SsoConfig config;

  public AuthController(
      PageAccessService access, PageCatalog pages, SsoGateway sso, SsoConfig config) {
    this.access = access;
    this.pages = pages;
    this.sso = sso;
    this.config = config;
  }

  @GetMapping("/api/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Collections.singletonMap("token", token.getToken());
  }

  /** 所有页面共用的固定后端回调。 */
  @GetMapping("/api/auth/callback")
  public ResponseEntity<Void> callback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "error", required = false) String error,
      HttpServletRequest request) {
    log.debug("[AuthController] 收到 SSO 固定回调: hasCode={}, hasState={}, error={}",
        code != null, state != null, error);
    // 1. 完成一次性事务和 Token 处理，取回服务端登记的目标。
    URI target = access.completeCallback(request, code, state, error);
    log.info("[AuthController] 回调处理完毕，303 重定向到业务页面: {}", target);
    // 2. 浏览器重新请求业务页面，继续经过页面守卫。
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(target).build();
  }

  @PostMapping("/api/auth/logout/page/{pageCode}")
  public Map<String, Object> logoutPage(
      @PathVariable String pageCode, HttpServletRequest request) {
    // 1. 校验页面已登记，再交给公共流程清理授权。
    pages.requireByCode(pageCode);
    boolean confirmed = access.logoutPage(request.getSession(false), pageCode);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("message", "本地页面授权已清除");
    result.put("ssoRevoked", Boolean.valueOf(confirmed));
    return result;
  }

  /** 退出 B 的全部本地页面授权，不撤销 Portal 的全局登录。 */
  @PostMapping("/api/auth/logout/system")
  public Map<String, Object> logoutSystem(HttpServletRequest request) {
    // 1. 撤销本系统保存的授权并销毁本地会话。
    LogoutSummary summary = access.logoutSystem(request.getSession(false));
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("message", "已退出 B 系统；Portal 全局会话未被注销");
    // 2. 返回尝试数和远端确认数，供前端解释退出结果。
    result.put("attempted", Integer.valueOf(summary.getAttempted()));
    result.put("confirmed", Integer.valueOf(summary.getConfirmed()));
    return result;
  }

  /** 退出整个服务（SSO 全局单点退出）：清理本地会话并提供 SSO 全局退出 URL，供前端跳转销毁 SSO 全局 Cookie。 */
  @PostMapping("/api/auth/logout/global")
  public Map<String, Object> logoutGlobal(HttpServletRequest request) {
    // 1. 撤销本系统保存的全部 Token 并销毁本地会话
    access.logoutSystem(request.getSession(false));
    // 2. 构造 SSO 全局退出端点 URL，登出后跳回 B 系统首页（与登记的 sys_address 同源）
    String redirectUri = URI.create(config.getCallbackUrl()).resolve("/").toString();
    String ssoLogoutUrl = sso.globalLogoutUrl(redirectUri);
    log.info("[AuthController] 客户端发起全局退出，已销毁本地会话，返回 SSO 全局退出地址: {}", ssoLogoutUrl);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("message", "本地会话已清除，正在跳转统一认证中心注销全局会话...");
    result.put("logoutUrl", ssoLogoutUrl);
    return result;
  }

  @GetMapping("/api/auth/logout/global")
  public ResponseEntity<Void> logoutGlobalGet(HttpServletRequest request) {
    access.logoutSystem(request.getSession(false));
    String redirectUri = URI.create(config.getCallbackUrl()).resolve("/").toString();
    String ssoLogoutUrl = sso.globalLogoutUrl(redirectUri);
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(ssoLogoutUrl)).build();
  }

}
