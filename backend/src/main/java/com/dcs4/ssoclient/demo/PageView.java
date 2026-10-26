package com.dcs4.ssoclient.demo;

import com.dcs4.ssoclient.auth.PageCatalog.Page;
import com.dcs4.ssoclient.sso.SsoModels.UserInfo;
import org.springframework.web.util.HtmlUtils;

/** 演示用 HTML 样式与文案。实际接入时使用业务系统自己的页面即可。 */
final class PageView {
  private PageView() {}

  static String render(Page page, UserInfo user, boolean isControlled) {
    // 1. 准备显示的用户信息，缺少用户时使用占位文案。
    String display = "已认证用户";
    String userCode = "";
    if (user != null) {
      display = user.getName();
      userCode = " (" + escape(user.getUserCode()) + ")";
    }

    // 2. 根据后端模式准备说明文案，文案本身不决定是否放行。
    String badge;
    String authDesc;
    if (isControlled) {
      badge = "<span class=\"badge controlled\">SSO V2 · 页面受控示例 (PAGE_CONTROLLED)</span>";
      authDesc = "<p class=\"auth-desc\">当前处于 <strong>PAGE_CONTROLLED（页面受控）</strong> 模式。<br>"
          + "本次请求已由 B 系统后端使用 <code>accessToken + " + escape(page.getCode())
          + "</code> 向 SSO 实时校验页面级权限通过。</p>";
    } else {
      badge = "<span class=\"badge sso-only\">SSO V2 · 业务页面示例 (SSO_ONLY)</span>";
      authDesc = "<p class=\"auth-desc\">当前处于 <strong>SSO_ONLY（普通单点登录）</strong> 模式。<br>"
          + "B 系统共用一组系统级 Token；本次请求仍由后端向 SSO 实时校验身份，不向 SSO 查询页面权限。</p>";
    }

    // 3. 组装演示 HTML；实际业务可替换为自己的页面模板。
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
        + "<p class=\"security-tip\">🛡️ <strong>安全保障</strong>：AccessToken、RefreshToken 和 client_secret 只由 B 系统后端保存和使用，不会返回给浏览器前端。</p>"
        + "<p><a class=\"btn-back\" href=\"/\">← 返回 B 系统首页</a></p></main></body></html>";
  }

  private static String escape(String value) {
    if (value == null) {
      value = "";
    }
    return HtmlUtils.htmlEscape(value);
  }
}
