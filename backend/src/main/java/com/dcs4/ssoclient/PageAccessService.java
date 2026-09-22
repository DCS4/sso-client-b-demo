package com.dcs4.ssoclient;

import com.dcs4.ssoclient.LoginStateStore.LoginTransaction;
import com.dcs4.ssoclient.PageCatalog.Page;
import com.dcs4.ssoclient.SsoGateway.SsoClientException;
import com.dcs4.ssoclient.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.SsoModels.StoredToken;
import com.dcs4.ssoclient.SsoModels.TokenData;
import com.dcs4.ssoclient.SsoModels.UserInfo;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 固定回调和公共页面拦截逻辑的参考实现。
 *
 * <p>每次进入页面都会调用 checkAccessToken。只有 AccessToken 无效时才尝试一次
 * RefreshToken 轮换；同一 HttpSession 上加锁，防止两个请求并发消费同一 RefreshToken。</p>
 *
 * <p>【接入必要项 2/5：业务接入流程】真实项目把 enter() 接到统一页面守卫，
 * 把 completeCallback() 接到唯一后端回调；与 SSO 的协议交互通过 SsoGateway 隔离。
 * 示例的 HttpSession 存储及页面目录可独立替换，不必照搬 Demo Controller 和页面 HTML。</p>
 */
@Service
public class PageAccessService {
  private static final Logger log = LoggerFactory.getLogger(PageAccessService.class);
  private static final String USER_ATTRIBUTE = PageAccessService.class.getName() + ".user";

  private final SsoConfig config;
  private final SsoGateway sso;
  private final LoginStateStore states;
  private final PageTokenStore tokens;

  public PageAccessService(
      SsoConfig config, SsoGateway sso, LoginStateStore states, PageTokenStore tokens) {
    this.config = config;
    this.sso = sso;
    this.states = states;
    this.tokens = tokens;
  }

  /** 【页面守卫】每次真实页面请求都从后端进入；只检查前端菜单不能防止直接 URL 越权。 */
  public AccessResult enter(HttpServletRequest request, Page page) {
    HttpSession session = request.getSession(true);
    String tokenKey = config.tokenKey(page.getCode());
    log.debug("[PageAccessService] 检查页面授权: pageCode={}, path={}",
        page.getCode(), page.getPath());
    // Demo 只在单 JVM HttpSession 上串行刷新；多实例需把锁/Token 存储替换为共享实现。
    synchronized (session) {
      StoredToken current = tokens.get(session, tokenKey);
      if (current != null) {
        log.info("[PageAccessService] 发现本地缓存 Token，执行 checkAccessToken 校验: pageCode={}", page.getCode());
        ActiveTokenData checked = checkOrFailClosed(current.getAccessToken(), page.getCode());
        if (matches(checked, current, page.getCode())) {
          log.info("[PageAccessService] Token 仍然有效，允许访问: pageCode={}, user={}",
              page.getCode(), currentUser(session) != null ? currentUser(session).getName() : "已认证");
          return AccessResult.allowed(currentUser(session));
        }

        log.warn("[PageAccessService] Token 已失效或页面受控校验不匹配，尝试使用 RefreshToken 刷新: pageCode={}", page.getCode());
        StoredToken refreshed = tryRefresh(session, tokenKey, current, page.getCode());
        if (refreshed != null) {
          log.info("[PageAccessService] RefreshToken 刷新成功，允许访问: pageCode={}", page.getCode());
          return AccessResult.allowed(currentUser(session));
        }
      }

      // 【必要】state 绑定服务端 pageCode/targetPath：浏览器不能指定回跳位置或越权页面。
      String state = states.begin(session, page.getCode(), page.getPath());
      String authUrl = sso.authorizeUrl(state, page.getCode());
      log.debug("[PageAccessService] 已生成单点登录授权跳转: pageCode={}", page.getCode());
      return AccessResult.redirect(URI.create(authUrl));
    }
  }

  /**
   * 【唯一固定回调】消费 state、后端兑换 code、核对绑定、保存 Token，并 303 返回白名单目标。
   * 同一个 client_id 的所有页面只登记这一个 callback；不要为每个页面重复写协议代码。
   */
  public URI completeCallback(
      HttpServletRequest request, String code, String state, String protocolError) {
    HttpSession session = request.getSession(false);
    log.debug("[PageAccessService] 收到 SSO 回调: hasCode={}, hasState={}, error={}, sessionExists={}",
        StringUtils.hasText(code), StringUtils.hasText(state), protocolError, session != null);
    // 【一次性事务】先消费 state，再处理 error/code；重复回调不能重复换 Token。
    LoginTransaction transaction = states.consume(session, state);
    if (transaction == null) {
      log.warn("[PageAccessService] 登录事务不存在、过期或已使用; 请核对浏览器访问与回调的 Host");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "登录事务不存在、过期或已使用");
    }
    if (StringUtils.hasText(protocolError)) {
      log.warn("[PageAccessService] SSO 拒绝授权: protocolError={}, pageCode={}",
          protocolError, transaction.getPageCode());
      HttpStatus status = "access_denied".equals(protocolError) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, "SSO 拒绝本次页面授权：" + protocolError);
    }
    if (!StringUtils.hasText(code)) {
      log.warn("[PageAccessService] SSO 回调缺少授权码");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SSO 未返回授权码");
    }

    try {
      log.debug("[PageAccessService] 正在兑换一次性授权码");
      // 【必要】code 在后端兑换；核对 client_id / page_code / 用户身份后才能创建本地登录。
      TokenData issued = sso.exchangeCode(code);
      log.info("[PageAccessService] 成功换取 Token: openid={}, pageCode={}, expiresIn={}",
          issued.getOpenid(), issued.getPageCode(), issued.getExpiresIn());
      validateIssuedToken(issued, transaction.getPageCode());
      log.info("[PageAccessService] 准备校验新签发的 Token: pageCode={}", transaction.getPageCode());
      ActiveTokenData checked = sso.checkAccessToken(issued.getAccessToken(), transaction.getPageCode());
      StoredToken stored = new StoredToken(issued, nowSeconds());
      if (!matches(checked, stored, transaction.getPageCode())) {
        log.error("[PageAccessService] 页面 Token checkAccessToken 校验未通过! active={}, pageCode={}",
            checked.isActive(), checked.getPageCode());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "页面 Token 校验未通过");
      }

      log.info("[PageAccessService] 准备获取用户信息: openid={}", issued.getOpenid());
      UserInfo user = sso.getUserInfo(issued.getAccessToken());
      if (user == null || !issued.getOpenid().equals(user.getId())
          || !Integer.valueOf(1).equals(user.getEnableStatus())) {
        log.error("[PageAccessService] 用户信息无效或已被停用: user={}, enableStatus={}",
            user != null ? user.getName() : null, user != null ? user.getEnableStatus() : null);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户信息无效或用户已停用");
      }
      log.info("[PageAccessService] 成功识别登录用户: name={}, userCode={}, companyCode={}",
          user.getName(), user.getUserCode(), user.getCompanyCode());

      // 【必要】身份建立后轮换本地 Session ID；Token 只写服务端仓库，浏览器仅持有会话 Cookie。
      request.changeSessionId();
      tokens.put(session, config.tokenKey(transaction.getPageCode()), stored);
      session.setAttribute(USER_ATTRIBUTE, user);
      log.info("[PageAccessService] 登录流程成功完成，即将跳转目标页面: {}", transaction.getTargetPath());
      return URI.create(transaction.getTargetPath());
    } catch (SsoClientException e) {
      log.error("[PageAccessService] SSO 客户端通信异常", e);
      throw new ResponseStatusException(
          e.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST,
          e.getMessage());
    }
  }

  public UserInfo currentUser(HttpSession session) {
    if (session == null) {
      return null;
    }
    Object value = session.getAttribute(USER_ATTRIBUTE);
    return value instanceof UserInfo ? (UserInfo) value : null;
  }

  public int authorizedPageCount(HttpSession session) {
    return session == null ? 0 : tokens.size(session);
  }

  /** 【页面级登出】清本地该页面授权并撤销其 Token family；不影响 Portal SID。 */
  public boolean logoutPage(HttpSession session, String pageCode) {
    if (session == null) {
      return false;
    }
    StoredToken token = tokens.remove(session, config.tokenKey(pageCode));
    if (token == null) {
      return false;
    }
    try {
      return sso.logout(token.getAccessToken());
    } catch (SsoClientException e) {
      // 本地 Token 已清除；调用方会收到失败提示，但不能继续复用该 Token。
      throw new ResponseStatusException(
          e.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST,
          "本地页面授权已清除，但 SSO 注销未确认");
    }
  }

  /** 【本系统退出】逐个撤销本系统 Token family、销毁本地会话；不等同于 Portal 全局退出。 */
  public LogoutSummary logoutSystem(HttpSession session) {
    if (session == null) {
      return new LogoutSummary(0, 0);
    }
    List<StoredToken> values = tokens.snapshot(session);
    Set<String> accessTokens = new HashSet<String>();
    int confirmed = 0;
    for (StoredToken value : values) {
      if (value != null && accessTokens.add(value.getAccessToken())) {
        try {
          if (sso.logout(value.getAccessToken())) {
            confirmed++;
          }
        } catch (SsoClientException ignored) {
          // 无论远端结果如何，本系统退出都必须清理本地 Session。
        }
      }
    }
    int attempted = accessTokens.size();
    session.invalidate();
    return new LogoutSummary(attempted, confirmed);
  }

  /** 【刷新必要规则】同一会话锁内只用一次旧 RefreshToken，验证后成对替换新 Access/Refresh。 */
  private StoredToken tryRefresh(
      HttpSession session, String tokenKey, StoredToken current, String pageCode) {
    if (!StringUtils.hasText(current.getRefreshToken()) || current.getRefreshExpiresAt() <= nowSeconds()) {
      tokens.remove(session, tokenKey);
      return null;
    }
    try {
      TokenData refreshed = sso.refresh(current.getRefreshToken());
      validateIssuedToken(refreshed, pageCode);
      StoredToken replacement = new StoredToken(refreshed, nowSeconds());
      ActiveTokenData checked = sso.checkAccessToken(replacement.getAccessToken(), pageCode);
      if (!matches(checked, replacement, pageCode)) {
        tokens.remove(session, tokenKey);
        return null;
      }
      // 新旧 AccessToken/RefreshToken 必须作为一对原子替换。
      tokens.put(session, tokenKey, replacement);
      return replacement;
    } catch (SsoClientException e) {
      if (e.isUnavailable()) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
      }
      tokens.remove(session, tokenKey);
      return null;
    }
  }

  private ActiveTokenData checkOrFailClosed(String accessToken, String pageCode) {
    try {
      return sso.checkAccessToken(accessToken, pageCode);
    } catch (SsoClientException e) {
      // 【失败关闭】SSO 不可达时返回错误；不能按 Token 已失效重新授权或直接放行业务页面。
      throw new ResponseStatusException(
          e.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
          e.getMessage());
    }
  }

  private boolean matches(ActiveTokenData checked, StoredToken token, String requestedPageCode) {
    String expectedPage = expectedPageCode(requestedPageCode);
    return checked != null
        && checked.isActive()
        && config.getClientId().equals(checked.getClientId())
        && Objects.equals(expectedPage, checked.getPageCode())
        && token.getSubject().equals(checked.getUid());
  }

  private void validateIssuedToken(TokenData issued, String requestedPageCode) {
    String expectedPage = expectedPageCode(requestedPageCode);
    if (issued == null
        || !StringUtils.hasText(issued.getAccessToken())
        || !StringUtils.hasText(issued.getRefreshToken())
        || !StringUtils.hasText(issued.getOpenid())
        || !config.getClientId().equals(issued.getClientId())
        || !Objects.equals(expectedPage, issued.getPageCode())) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SSO 返回的 Token 绑定不匹配");
    }
  }

  /** 受控模式绑定具体页面；SSO_ONLY 必须保持 page_code=null，不能把业务页面码混进协议。 */
  private String expectedPageCode(String pageCode) {
    return config.isPageControlled() ? pageCode : null;
  }

  private long nowSeconds() {
    return System.currentTimeMillis() / 1000L;
  }

  public static final class AccessResult {
    private final boolean allowed;
    private final URI redirect;
    private final UserInfo user;

    private AccessResult(boolean allowed, URI redirect, UserInfo user) {
      this.allowed = allowed;
      this.redirect = redirect;
      this.user = user;
    }

    public static AccessResult allowed(UserInfo user) {
      return new AccessResult(true, null, user);
    }

    public static AccessResult redirect(URI target) {
      return new AccessResult(false, target, null);
    }

    public boolean isAllowed() {
      return allowed;
    }

    public URI getRedirect() {
      return redirect;
    }

    public UserInfo getUser() {
      return user;
    }
  }

  public static final class LogoutSummary {
    private final int attempted;
    private final int confirmed;

    private LogoutSummary(int attempted, int confirmed) {
      this.attempted = attempted;
      this.confirmed = confirmed;
    }

    public int getAttempted() {
      return attempted;
    }

    public int getConfirmed() {
      return confirmed;
    }
  }
}
