package com.dcs4.ssoclient;

import com.dcs4.ssoclient.PageAccessService.LogoutSummary;
import com.dcs4.ssoclient.PageCatalog.Page;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 演示页面所需的本地 API；所有 SSO 密钥和 Token 均不会返回给前端。 */
@RestController
public class DemoController {
  private static final Logger log = LoggerFactory.getLogger(DemoController.class);

  private final PageAccessService access;
  private final PageCatalog pages;
  private final SsoConfig config;

  public DemoController(PageAccessService access, PageCatalog pages, SsoConfig config) {
    this.access = access;
    this.pages = pages;
    this.config = config;
  }

  @GetMapping("/api/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Collections.singletonMap("token", token.getToken());
  }

  /**
   * 所有页面共用的唯一固定回调。回调不渲染页面，只完成后端兑换后 303 回原页面。
   */
  @GetMapping("/api/auth/callback")
  public ResponseEntity<Void> callback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "error", required = false) String error,
      HttpServletRequest request) {
    log.debug("[DemoController] 收到 SSO 固定回调: hasCode={}, hasState={}, error={}",
        code != null, state != null, error);
    URI target = access.completeCallback(request, code, state, error);
    log.info("[DemoController] 回调处理完毕，303 重定向到业务页面: {}", target);
    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(target).build();
  }

  @GetMapping("/api/auth/me")
  public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    UserInfo user = access.currentUser(session);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("id", user.getId());
    result.put("userCode", user.getUserCode());
    result.put("name", user.getName());
    result.put("companyCode", user.getCompanyCode());
    result.put("authMode", config.getAuthMode());
    result.put("authorizedTokenCount", Integer.valueOf(access.authorizedPageCount(session)));
    return ResponseEntity.ok(result);
  }

  @GetMapping("/api/pages")
  public List<Map<String, String>> pageList() {
    List<Map<String, String>> result = new ArrayList<Map<String, String>>();
    for (Page page : pages.all()) {
      Map<String, String> item = new LinkedHashMap<String, String>();
      item.put("code", page.getCode());
      item.put("name", page.getName());
      item.put("path", page.getPath());
      result.add(item);
    }
    return result;
  }

  @PostMapping("/api/auth/logout/page/{pageCode}")
  public Map<String, Object> logoutPage(
      @PathVariable String pageCode, HttpServletRequest request) {
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
    LogoutSummary summary = access.logoutSystem(request.getSession(false));
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("message", "已退出 B 系统；Portal 全局会话未被注销");
    result.put("attempted", Integer.valueOf(summary.getAttempted()));
    result.put("confirmed", Integer.valueOf(summary.getConfirmed()));
    return result;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
    log.warn("[DemoController] 业务状态异常: status={}, reason={}", error.getStatus(), error.getReason());
    return ResponseEntity.status(error.getStatus())
        .body(Collections.singletonMap("message", safeMessage(error.getReason())));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
    log.warn("[DemoController] 参数不合法: {}", e.getMessage());
    return ResponseEntity.badRequest()
        .body(Collections.singletonMap("message", "页面或协议参数不合法"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> failure(Exception error) {
    log.error("[DemoController] 系统未捕获异常", error);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Collections.singletonMap("message", "请求未完成，请联系管理员检查服务配置"));
  }

  private String safeMessage(String message) {
    return message == null ? "请求未完成" : message;
  }
}
