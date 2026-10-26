package com.dcs4.ssoclient.demo;

import com.dcs4.ssoclient.auth.PageAccessService;
import com.dcs4.ssoclient.auth.PageAccessService.AccessResult;
import com.dcs4.ssoclient.auth.PageCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 教程代码：复制到 backend 的 demo 包，并按教程登记库存页后生效。 */
@RestController
public class InventoryApiController {
  private final PageAccessService access;
  private final PageCatalog pages;

  public InventoryApiController(PageAccessService access, PageCatalog pages) {
    this.access = access;
    this.pages = pages;
  }

  @GetMapping("/api/demo/inventory")
  public ResponseEntity<Map<String, Object>> inventory(HttpServletRequest request) {
    // 页面和数据接口使用同一份服务端映射。API 也要执行校验。
    AccessResult decision = access.enter(request, pages.requireByPath("/pages/inventory"));
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    if (!decision.isAllowed()) {
      // JSON 请求由前端显式执行整页导航，不直接把 fetch 重定向到远端 SSO。
      body.put("message", "请先完成本次授权");
      body.put("loginUrl", decision.getRedirect().toASCIIString());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
    // 固定演示数据；真实项目在这里接入业务权限、数据范围和库存查询。
    body.put("items", Collections.singletonList(Collections.singletonMap("sku", "DEMO-001")));
    return ResponseEntity.ok(body);
  }
}
