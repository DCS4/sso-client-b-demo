package com.dcs4.ssoclient;

import com.dcs4.ssoclient.PageAccessService.AccessResult;
import com.dcs4.ssoclient.PageCatalog.Page;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import javax.servlet.http.HttpServletRequest;
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
  private final PageCatalog pages;
  private final PageAccessService access;

  public PageController(PageCatalog pages, PageAccessService access) {
    this.pages = pages;
    this.access = access;
  }

  @GetMapping({"/pages/orders", "/pages/reports", "/pages/operations"})
  public ResponseEntity<String> page(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    Page page = pages.requireByPath(path);
    AccessResult decision = access.enter(request, page);
    if (!decision.isAllowed()) {
      return ResponseEntity.status(HttpStatus.FOUND).location(decision.getRedirect()).build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(render(page, decision.getUser()));
  }

  private String render(Page page, UserInfo user) {
    String display = user == null ? "已认证用户" : user.getName();
    return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>" + escape(page.getName()) + "</title>"
        + "<style>body{font-family:system-ui;background:#eef2f6;color:#18324a;margin:0}"
        + "main{max-width:760px;margin:70px auto;background:white;border:1px solid #dbe3eb;"
        + "border-radius:12px;padding:32px}code{background:#edf3f7;padding:3px 7px;border-radius:4px}"
        + "a{color:#17618d}</style></head><body><main>"
        + "<p>SSO V2 页面受控示例</p><h1>" + escape(page.getName()) + "</h1>"
        + "<p>当前用户：<strong>" + escape(display) + "</strong></p>"
        + "<p>本次请求已由 B 后端使用 <code>accessToken + "
        + escape(page.getCode()) + "</code> 向 SSO 实时校验。</p>"
        + "<p>AccessToken、RefreshToken 和 client_secret 均未进入浏览器。</p>"
        + "<p><a href=\"/\">返回 B 系统首页</a></p></main></body></html>";
  }

  private String escape(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value);
  }
}
