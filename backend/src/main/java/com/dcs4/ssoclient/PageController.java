package com.dcs4.ssoclient;

import com.dcs4.ssoclient.PageAccessService.AccessResult;
import com.dcs4.ssoclient.PageCatalog.Page;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

/**
 * 三个普通外部页面的最小示例。
 *
 * <p>真实项目可以把相同的 PageAccessService 调用放入拦截器、Filter 或统一页面
 * Controller。关键要求是直接输入原页面 URL 也必须经过后端校验。</p>
 */
@Controller
public class PageController {
  private static final Logger log = LoggerFactory.getLogger(PageController.class);

  private final PageCatalog pages;
  private final PageAccessService access;
  private final SsoConfig config;

  public PageController(PageCatalog pages, PageAccessService access, SsoConfig config) {
    this.pages = pages;
    this.access = access;
    this.config = config;
  }

  @GetMapping({"/pages/orders", "/pages/reports", "/pages/operations"})
  public ResponseEntity<String> page(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    log.info("[Demo-PageController] 用户请求业务页面: URI={}, RemoteAddr={}, Host={}",
        path, request.getRemoteAddr(), request.getHeader("Host"));
    Page page = pages.requireByPath(path);
    AccessResult decision = access.enter(request, page);
    if (!decision.isAllowed()) {
      log.info("[Demo-PageController] 页面未授权，302 跳转至 SSO 认证地址: pageCode={}, redirect={}",
          page.getCode(), decision.getRedirect());
      return ResponseEntity.status(HttpStatus.FOUND).location(decision.getRedirect()).build();
    }
    log.info("[Demo-PageController] 页面授权通过，渲染页面: pageCode={}, user={}",
        page.getCode(), decision.getUser() != null ? decision.getUser().getName() : "已认证");
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(render(page, decision.getUser()));
  }

  private String render(Page page, UserInfo user) {
    String display = user == null ? "已认证用户" : user.getName();
    String userCode = user == null ? "" : " (" + escape(user.getUserCode()) + ")";
    boolean isControlled = config.isPageControlled();

    String badge = isControlled
        ? "<span class=\"badge controlled\">SSO V2 · 页面受控示例 (PAGE_CONTROLLED)</span>"
        : "<span class=\"badge sso-only\">SSO V2 · 业务页面示例 (SSO_ONLY)</span>";

    String authDesc = isControlled
        ? "<p class=\"auth-desc\">当前处于 <strong>PAGE_CONTROLLED（页面受控）</strong> 模式。<br>"
          + "本次请求已由 B 系统后端使用 <code>accessToken + " + escape(page.getCode())
          + "</code> 向 SSO 实时校验页面级权限通过。</p>"
        : "<p class=\"auth-desc\">当前处于 <strong>SSO_ONLY（普通单点登录）</strong> 模式。<br>"
          + "用户已通过主平台统一身份认证，B 系统本地共享登录会话，直接放行本业务页面。</p>";

    return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>" + escape(page.getName()) + " - 业务系统 B</title>"
        + "<style>"
        + "* { box-sizing: border-box; }"
        + "body { font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", \"PingFang SC\", \"Hiragino Sans GB\", \"Microsoft YaHei\", \"微软雅黑\", Arial, sans-serif; background: #eef2f6; color: #182c43; margin: 0; padding: 20px; }"
        + "main { max-width: 680px; margin: 50px auto; background: #ffffff; border: 1px solid #dbe3eb; border-radius: 12px; padding: 36px 40px; box-shadow: 0 4px 16px rgba(15, 34, 58, 0.05); }"
        + ".badge { display: inline-block; font-size: 13px; font-weight: 600; padding: 4px 12px; border-radius: 20px; letter-spacing: 0.5px; margin-bottom: 16px; }"
        + ".badge.controlled { background: #e8f4fd; color: #1967d2; border: 1px solid #d2e3fc; }"
        + ".badge.sso-only { background: #e6f4ea; color: #137333; border: 1px solid #ceead6; }"
        + "h1 { font-size: 28px; font-weight: 700; margin: 0 0 20px 0; color: #182c43; }"
        + ".user-info { font-size: 15px; color: #334e68; margin-bottom: 12px; }"
        + ".user-info strong { color: #182c43; font-size: 16px; }"
        + ".auth-desc { font-size: 14px; line-height: 1.8; color: #486581; background: #f8fafc; border-left: 4px solid #164f78; padding: 12px 16px; border-radius: 0 6px 6px 0; margin: 18px 0; }"
        + "code { background: #e2e8f0; color: #0f4c81; padding: 2px 7px; border-radius: 4px; font-family: Consolas, Monaco, monospace; font-size: 13px; }"
        + ".security-tip { font-size: 13px; color: #627d98; margin: 20px 0 26px 0; line-height: 1.6; border-top: 1px dashed #e1e8ed; padding-top: 16px; }"
        + ".btn-back { display: inline-block; background: #164f78; color: #ffffff; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-size: 14px; font-weight: 500; transition: background 0.2s; }"
        + ".btn-back:hover { background: #0f3652; }"
        + "</style></head><body><main>"
        + badge
        + "<h1>" + escape(page.getName()) + "</h1>"
        + "<p class=\"user-info\">当前登录用户：<strong>" + escape(display) + "</strong>" + userCode + "</p>"
        + authDesc
        + "<p class=\"security-tip\">🛡️ <strong>安全保障</strong>：AccessToken、RefreshToken 和 client_secret 均由 B 系统服务端内存安全管理，绝不泄露至浏览器前端。</p>"
        + "<p><a class=\"btn-back\" href=\"/\">← 返回 B 系统首页</a></p></main></body></html>";
  }

  private String escape(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value);
  }
}
