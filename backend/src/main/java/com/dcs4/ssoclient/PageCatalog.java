package com.dcs4.ssoclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 业务页面路径与 SSO page_code 的服务端白名单。
 *
 * <p>真实项目可以改为数据库或配置中心，但浏览器不能自行提交任意 page_code/target。
 * 同一份映射还用于固定回调后的安全回跳，避免开放重定向。</p>
 */
@Component
public class PageCatalog {
  private final Map<String, Page> byPath = new LinkedHashMap<String, Page>();
  private final Map<String, Page> byCode = new LinkedHashMap<String, Page>();

  public PageCatalog() {
    register(new Page("B_PAGE_01", "订单查询", "/pages/orders"));
    register(new Page("B_PAGE_02", "经营报表", "/pages/reports"));
    register(new Page("B_PAGE_03", "运维控制台", "/pages/operations"));
  }

  public Page requireByPath(String path) {
    Page page = byPath.get(path);
    if (page == null) {
      throw new IllegalArgumentException("页面路径未登记");
    }
    return page;
  }

  public Page requireByCode(String code) {
    Page page = byCode.get(code);
    if (page == null) {
      throw new IllegalArgumentException("page_code 未登记");
    }
    return page;
  }

  public List<Page> all() {
    return Collections.unmodifiableList(new ArrayList<Page>(byCode.values()));
  }

  private void register(Page page) {
    if (byPath.put(page.getPath(), page) != null || byCode.put(page.getCode(), page) != null) {
      throw new IllegalStateException("页面路径和 page_code 不能重复");
    }
  }

  public static final class Page {
    private final String code;
    private final String name;
    private final String path;

    private Page(String code, String name, String path) {
      this.code = code;
      this.name = name;
      this.path = path;
    }

    public String getCode() {
      return code;
    }

    public String getName() {
      return name;
    }

    public String getPath() {
      return path;
    }
  }
}
