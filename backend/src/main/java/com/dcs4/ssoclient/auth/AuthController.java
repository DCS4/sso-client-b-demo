package com.dcs4.ssoclient.auth;

import com.dcs4.ssoclient.auth.PageAccessService.GlobalLogoutResult;
import com.dcs4.ssoclient.auth.PageAccessService.LogoutSummary;
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

/** 接入入口：固定回调、CSRF Token、本地退出与全局单点退出。HTTP 路径与演示首页解耦。 */
@RestController
public class AuthController {
  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private final PageAccessService access;
  private final PageCatalog pages;

  public AuthController(PageAccessService access, PageCatalog pages) {
    this.access = access;
    this.pages = pages;
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

  /**
   * 退出整个服务（客户端发起的 SSO 全局单点退出）：
   * 1. 先清理 B 本地全部 Token 与 HttpSession；
   * 2. 返回 SSO 全局登出地址（GET /logout），由前端引导浏览器顶层跳转以清除 SSO_SID Cookie 并销毁全局会话。
   */
  @PostMapping("/api/auth/logout/global")
  public Map<String, Object> logoutGlobal(HttpServletRequest request) {
    // 1. 清理本地会话并生成 SSO 全局退出跳转地址。
    GlobalLogoutResult globalLogout = access.logoutGlobal(request.getSession(false));
    log.info("[AuthController] 客户端发起全局退出，已销毁本地会话，返回 SSO 全局退出地址: {}",
        globalLogout.getLogoutUrl());
    // 2. 返回本地清理统计与远端跳转 URL，由前端执行整页导航。
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("message", "本地会话已清除，正在跳转统一认证中心注销全局会话...");
    result.put("attempted", Integer.valueOf(globalLogout.getAttempted()));
    result.put("confirmed", Integer.valueOf(globalLogout.getConfirmed()));
    result.put("logoutUrl", globalLogout.getLogoutUrl());
    return result;
  }

  /** 支持浏览器直接 GET 触发全局退出（清理本地会话后 303 重定向至 SSO 全局退出端点）。 */
  @GetMapping("/api/auth/logout/global")
  public ResponseEntity<Void> logoutGlobalGet(HttpServletRequest request) {
    GlobalLogoutResult globalLogout = access.logoutGlobal(request.getSession(false));
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(URI.create(globalLogout.getLogoutUrl()))
        .build();
  }
}
