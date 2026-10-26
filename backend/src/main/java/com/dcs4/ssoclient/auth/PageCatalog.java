package com.dcs4.ssoclient.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 后端登记的页面目录：路径用于定位页面，编码用于 SSO 授权，回跳目标也来自此处。 */
@Component
public class PageCatalog {
  private final Map<String, Page> byPath = new LinkedHashMap<String, Page>();
  private final Map<String, Page> byCode = new LinkedHashMap<String, Page>();

  public PageCatalog() {
    register(new Page("B_PAGE_01", "订单查询", "/pages/orders"));
    register(new Page("B_PAGE_02", "经营报表", "/pages/reports"));
    register(new Page("B_PAGE_03", "运维控制台", "/pages/operations"));
  }

  /** 请求真实页面 URL 时从服务端路径确定 page_code；不能信任浏览器传入的权限码。 */
  public Page requireByPath(String path) {
    Page page = byPath.get(path);
    if (page == null) {
      throw new IllegalArgumentException("页面路径未登记");
    }
    return page;
  }

  /** 单页退出等操作用已登记的码定位页面，拒绝任意未登记码。 */
  public Page requireByCode(String code) {
    Page page = byCode.get(code);
    if (page == null) {
      throw new IllegalArgumentException("page_code 未登记");
    }
    return page;
  }

  public List<Page> all() {
    List<Page> pages = new ArrayList<Page>(byCode.values());
    return Collections.unmodifiableList(pages);
  }

  private void register(Page page) {
    // 1. 按路径登记页面，同时检查是否重复。
    Page previousPath = byPath.put(page.getPath(), page);
    if (previousPath != null) {
      throw new IllegalStateException("页面路径和 page_code 不能重复");
    }

    // 2. 按编码建立另一份索引，供单页退出等操作查找。
    Page previousCode = byCode.put(page.getCode(), page);
    if (previousCode != null) {
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
