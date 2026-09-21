package com.dcs4.ssoclient;

import com.dcs4.ssoclient.LoginStateStore.LoginTransaction;
import com.dcs4.ssoclient.PageCatalog.Page;
import com.dcs4.ssoclient.SsoClient.SsoClientException;
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
 */
@Service
public class PageAccessService {
  private static final Logger log = LoggerFactory.getLogger(PageAccessService.class);
  private static final String USER_ATTRIBUTE = PageAccessService.class.getName() + ".user";

  private final SsoConfig config;
  private final SsoClient sso;
  private final LoginStateStore states;
  private final PageTokenStore tokens;

  public PageAccessService(
      SsoConfig config, SsoClient sso, LoginStateStore states, PageTokenStore tokens) {
    this.config = config;
    this.sso = sso;
    this.states = states;
    this.tokens = tokens;
  }

  public AccessResult enter(HttpServletRequest request, Page page) {
    HttpSession session = request.getSession(true);
    String tokenKey = config.tokenKey(page.getCode());
    log.info("[PageAccessService] 检查页面授权: pageCode={}, path={}, sessionId={}",
        page.getCode(), page.getPath(), session.getId());
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

      // target 只来自服务端 PageCatalog，不接受浏览器提供的任意 URL。
      String state = states.begin(session, page.getCode(), page.getPath());
      String authUrl = sso.authorizeUrl(state, page.getCode());
      log.info("[PageAccessService] 生成单点登录授权跳转: state={}, pageCode={}, authUrl={}",
          state, page.getCode(), authUrl);
      return AccessResult.redirect(URI.create(authUrl));
    }
  }

  /**
   * 固定回调：消费 state、后端兑换 code、核对绑定、保存 Token，并返回白名单目标。
   */
  public URI completeCallback(
      HttpServletRequest request, String code, String state, String protocolError) {
    HttpSession session = request.getSession(false);
    log.info("[PageAccessService] 开始处理 SSO 回调 completeCallback: code={}, state={}, error={}, sessionExists={}, sessionId={}",
        (code != null ? (code.length() > 8 ? code.substring(0, 8) + "..." : code) : null),
        state, protocolError, (session != null), (session != null ? session.getId() : "null"));
    LoginTransaction transaction = states.consume(session, state);
    if (transaction == null) {
      log.error("[PageAccessService] 登录事务不存在、过期或已使用! state={}, session={}. 请检查浏览器访问地址与回调地址的 Host/端口 是否一致(例如 127.0.0.1 vs localhost 会导致 Cookie 隔离)",
          state, (session != null ? session.getId() : "null"));
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "登录事务不存在、过期或已使用");
    }
    if (StringUtils.hasText(protocolError)) {
      log.error("[PageAccessService] SSO 拒绝授权: protocolError={}, state={}, pageCode={}",
          protocolError, state, transaction.getPageCode());
      HttpStatus status = "access_denied".equals(protocolError) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, "SSO 拒绝本次页面授权：" + protocolError);
    }
    if (!StringUtils.hasText(code)) {
      log.error("[PageAccessService] SSO 未返回授权码 code: state={}", state);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SSO 未返回授权码");
    }

    try {
      log.info("[PageAccessService] 准备通过 code 换取 Token: code={}", code);
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

      // 身份建立后更换 Session ID，保留已消费的服务端状态并防止会话固定攻击。
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

  /** 逐个撤销本系统保存的 Token family；这不等同于 Portal 全局退出。 */
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
      // 页面受控链路不可用时不能降级放行，也不能制造无限授权跳转。
      throw new ResponseStatusException(
          e.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
          e.getMessage());
    }
  }

  private boolean matches(ActiveTokenData checked, StoredToken token, String requestedPageCode) {
    String expectedPage = config.isPageControlled() ? requestedPageCode : null;
    return checked != null
        && checked.isActive()
        && config.getClientId().equals(checked.getClientId())
        && Objects.equals(expectedPage, checked.getPageCode())
        && token.getSubject().equals(checked.getUid());
  }

  private void validateIssuedToken(TokenData issued, String requestedPageCode) {
    String expectedPage = config.isPageControlled() ? requestedPageCode : null;
    if (issued == null
        || !StringUtils.hasText(issued.getAccessToken())
        || !StringUtils.hasText(issued.getRefreshToken())
        || !StringUtils.hasText(issued.getOpenid())
        || !config.getClientId().equals(issued.getClientId())
        || !Objects.equals(expectedPage, issued.getPageCode())) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SSO 返回的 Token 绑定不匹配");
    }
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
