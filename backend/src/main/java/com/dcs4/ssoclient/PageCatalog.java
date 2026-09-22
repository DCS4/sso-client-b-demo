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
 *
 * <p>【接入必要项 3/5：页面映射】真实业务系统仅替换页面来源（路由/数据库/配置中心），
 * 保留 path 与 page_code 一一对应以及回调目标白名单。此类的三个页面只是演示数据。</p>
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
