package com.dcs4.ssoclient.demo;

import com.dcs4.ssoclient.auth.PageAccessService.AccessResult;
import com.dcs4.ssoclient.auth.PageAccessService;
import com.dcs4.ssoclient.auth.PageCatalog.Page;
import com.dcs4.ssoclient.auth.PageCatalog;
import com.dcs4.ssoclient.config.SsoConfig;
import java.util.concurrent.TimeUnit;
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

/** 真实页面 URL 的演示入口。实际项目可在原有 Controller、Filter 或拦截器调用同一守卫。 */
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
    long pageStartNanos = System.nanoTime();
    // 1. 按真实请求路径找到后端登记的页面。
    String path = request.getRequestURI().substring(request.getContextPath().length());
    log.info("[Demo-PageController] 用户请求业务页面: URI={}, RemoteAddr={}, Host={}",
        path, request.getRemoteAddr(), request.getHeader("Host"));
    Page page = pages.requireByPath(path);
    // 2. 执行公共守卫，需要授权时让浏览器整页跳转。
    AccessResult decision = access.enter(request, page);
    if (!decision.isAllowed()) {
      log.info("[SSO-Timing] 页面请求转授权: pageCode={}, backendGuardMs={}",
          page.getCode(), elapsedMs(pageStartNanos));
      return ResponseEntity.status(HttpStatus.FOUND).location(decision.getRedirect()).build();
    }
    // 3. 校验通过后渲染页面，并禁止浏览器缓存本次内容。
    String userName = "已认证";
    if (decision.getUser() != null) {
      userName = decision.getUser().getName();
    }
    log.info("[Demo-PageController] 页面授权通过，渲染页面: pageCode={}, user={}",
        page.getCode(), userName);
    String html = PageView.render(page, decision.getUser(), config.isPageControlled());
    log.info("[SSO-Timing] 页面渲染响应: pageCode={}, backendPageMs={}",
        page.getCode(), elapsedMs(pageStartNanos));
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(html);
  }

  /** 本次 B 后端页面请求耗时：不包含浏览器下载和绘制页面的时间。 */
  private long elapsedMs(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

}
