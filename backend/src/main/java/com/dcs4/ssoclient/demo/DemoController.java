package com.dcs4.ssoclient.demo;

import com.dcs4.ssoclient.auth.PageAccessService;
import com.dcs4.ssoclient.auth.PageCatalog.Page;
import com.dcs4.ssoclient.auth.PageCatalog;
import com.dcs4.ssoclient.config.SsoConfig;
import com.dcs4.ssoclient.sso.SsoModels.UserInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首页展示接口。me 返回本地用户摘要和实时校验的授权数量，pages 返回演示目录。 */
@RestController
public class DemoController {
  private final PageAccessService access;
  private final PageCatalog pages;
  private final SsoConfig config;

  public DemoController(PageAccessService access, PageCatalog pages, SsoConfig config) {
    this.access = access;
    this.pages = pages;
    this.config = config;
  }

  @GetMapping("/api/auth/me")
  public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
    // 1. 首页沿用已有本地会话，业务页面负责发起新授权。
    HttpSession session = request.getSession(false);
    UserInfo user = access.currentUser(session);
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    // 2. 校验已有 AccessToken，首页只接收数量和用户摘要。
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("id", user.getId());
    result.put("userCode", user.getUserCode());
    result.put("name", user.getName());
    result.put("companyCode", user.getCompanyCode());
    result.put("authMode", config.getAuthMode());
    result.put("authorizedTokenCount", access.countValidAccessTokens(session));
    return ResponseEntity.ok(result);
  }

  @GetMapping("/api/pages")
  public List<Map<String, String>> pageList() {
    // 返回演示目录；这份列表不代表当前用户的权限。
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

}
