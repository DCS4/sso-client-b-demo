package com.dcs4.ssoclient.auth;

import com.dcs4.ssoclient.auth.LoginStateStore.LoginTransaction;
import com.dcs4.ssoclient.auth.PageCatalog.Page;
import com.dcs4.ssoclient.config.SsoConfig;
import com.dcs4.ssoclient.sso.SsoGateway.SsoClientException;
import com.dcs4.ssoclient.sso.SsoGateway;
import com.dcs4.ssoclient.sso.SsoModels.ActiveTokenData;
import com.dcs4.ssoclient.sso.SsoModels.StoredToken;
import com.dcs4.ssoclient.sso.SsoModels.TokenData;
import com.dcs4.ssoclient.sso.SsoModels.UserInfo;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** B 系统的公共授权流程：页面校验、固定回调、Token 刷新和本地退出。 */
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

  /** 每次真实页面请求都从后端进入；只检查前端菜单不能防止直接 URL 越权。 */
  public AccessResult enter(HttpServletRequest request, Page page) {
    // 1. 找到当前会话的 Token 键：受控模式按页保存，普通 SSO 共用一组。
    HttpSession session = request.getSession(true);
    String tokenKey = config.tokenKey(page.getCode());
    log.debug("[PageAccessService] 检查页面授权: pageCode={}, path={}",
        page.getCode(), page.getPath());
    // Demo 只在单 JVM HttpSession 上串行刷新；多实例需把锁/Token 存储替换为共享实现。
    synchronized (session) {
      StoredToken current = tokens.get(session, tokenKey);
      if (current != null) {
        log.info("[PageAccessService] 发现本地缓存 Token，执行 checkAccessToken 校验: pageCode={}", page.getCode());
        // 2. 已有 Token 也要向 SSO 实时校验，通过后才返回页面。
        ActiveTokenData checked = checkOrFailClosed(current.getAccessToken(), page.getCode());
        if (matches(checked, current, page.getCode())) {
          UserInfo user = currentUser(session);
          String userName = "已认证";
          if (user != null) {
            userName = user.getName();
          }
          log.info("[PageAccessService] Token 仍然有效，允许访问: pageCode={}, user={}",
              page.getCode(), userName);
          return AccessResult.allowed(currentUser(session));
        }

        log.warn("[PageAccessService] Token 已失效或页面受控校验不匹配，尝试使用 RefreshToken 刷新: pageCode={}", page.getCode());
        // 3. 校验未通过时，在同一个会话锁内尝试一次刷新。
        StoredToken refreshed = tryRefresh(session, tokenKey, current, page.getCode());
        if (refreshed != null) {
          log.info("[PageAccessService] RefreshToken 刷新成功，允许访问: pageCode={}", page.getCode());
          return AccessResult.allowed(currentUser(session));
        }
      }

      // 4. 没有可用 Token：保存本次目标，生成授权地址让浏览器跳转。
      String state = states.begin(session, page.getCode(), page.getPath());
      String authUrl = sso.authorizeUrl(state, page.getCode());
      // 只记页面编码和时间；不要记录 state、code、授权 URL（其中包含一次性参数）。
      log.info("[SSO-Timing] 开始浏览器授权往返: pageCode={}", page.getCode());
      return AccessResult.redirect(URI.create(authUrl));
    }
  }

  /**
   * 消费 state、后端兑换 code、核对绑定、保存 Token，并 303 返回白名单目标。
   * 同一个 client_id 的所有页面只登记这一个 callback；不要为每个页面重复写协议代码。
   */
  public URI completeCallback(
      HttpServletRequest request, String code, String state, String protocolError) {
    long callbackStartNanos = System.nanoTime();
    HttpSession session = request.getSession(false);
    log.debug("[PageAccessService] 收到 SSO 回调: hasCode={}, hasState={}, error={}, sessionExists={}",
        StringUtils.hasText(code), StringUtils.hasText(state), protocolError, session != null);
    // 1. 先消费一次性 state，找回服务端保存的页面目标。
    LoginTransaction transaction = states.consume(session, state);
    if (transaction == null) {
      log.warn("[PageAccessService] 登录事务不存在、过期或已使用; 请核对浏览器访问与回调的 Host");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "登录事务不存在、过期或已使用");
    }
    // 2. 处理 SSO 拒绝和缺少 code 的情况，不在这里重新发起登录。
    if (StringUtils.hasText(protocolError)) {
      log.warn("[SSO-Timing] 授权拒绝: pageCode={}, error={}, browserRoundTripMs={}",
          transaction.getPageCode(), protocolError, browserRoundTripMs(transaction));
      HttpStatus status = HttpStatus.BAD_REQUEST;
      if ("access_denied".equals(protocolError)) {
        status = HttpStatus.FORBIDDEN;
      }
      throw new ResponseStatusException(status, "SSO 拒绝本次页面授权：" + protocolError);
    }
    if (!StringUtils.hasText(code)) {
      log.warn("[PageAccessService] SSO 回调缺少授权码");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SSO 未返回授权码");
    }

    try {
      log.debug("[PageAccessService] 正在兑换一次性授权码");
      // 3. 在后端兑换 code，核对返回的客户端、页面和用户标识。
      TokenData issued = sso.exchangeCode(code);
      validateIssuedToken(issued, transaction.getPageCode());
      log.info("[PageAccessService] 成功换取 Token: openid={}, pageCode={}, expiresIn={}",
          issued.getOpenid(), issued.getPageCode(), issued.getExpiresIn());
      log.info("[PageAccessService] 准备校验新签发的 Token: pageCode={}", transaction.getPageCode());
      // 4. 再向 SSO 校验新 Token，兑换成功不等于已经可以放行。
      ActiveTokenData checked = sso.checkAccessToken(issued.getAccessToken(), transaction.getPageCode());
      StoredToken stored = new StoredToken(issued, nowSeconds());
      if (!matches(checked, stored, transaction.getPageCode())) {
        log.error("[PageAccessService] 页面 Token checkAccessToken 校验未通过! active={}, pageCode={}",
            checked != null && checked.isActive(), checked == null ? null : checked.getPageCode());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "页面 Token 校验未通过");
      }

      log.info("[PageAccessService] 准备获取用户信息: openid={}", issued.getOpenid());
      // 5. 获取用户资料，确认身份一致且用户启用。
      UserInfo user = sso.getUserInfo(issued.getAccessToken());
      if (user == null || !issued.getOpenid().equals(user.getId())
          || !Integer.valueOf(1).equals(user.getEnableStatus())) {
        String userName = null;
        Integer enableStatus = null;
        if (user != null) {
          userName = user.getName();
          enableStatus = user.getEnableStatus();
        }
        log.error("[PageAccessService] 用户信息无效或已被停用: user={}, enableStatus={}",
            userName, enableStatus);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户信息无效或用户已停用");
      }
      log.info("[PageAccessService] 成功识别登录用户: name={}, userCode={}, companyCode={}",
          user.getName(), user.getUserCode(), user.getCompanyCode());

      // 6. 同一个 B 会话切换账号时，先清除旧账号的全部本地页面授权。
      //    这里只清 B 的 Session 缓存，不调用 Portal 的全局退出。
      synchronized (session) {
        UserInfo previousUser = currentUser(session);
        if (previousUser != null && !user.getId().equals(previousUser.getId())) {
          tokens.clear(session);
        }

        // 7. 原子更新身份和本次 Token；浏览器只接收会话 Cookie 和回跳地址。
        request.changeSessionId();
        tokens.put(session, config.tokenKey(transaction.getPageCode()), stored);
        session.setAttribute(USER_ATTRIBUTE, user);
      }
      log.info("[SSO-Timing] 固定回调完成: pageCode={}, browserRoundTripMs={}, callbackProcessingMs={}",
          transaction.getPageCode(), browserRoundTripMs(transaction),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callbackStartNanos));
      log.info("[PageAccessService] 登录流程成功完成，即将跳转目标页面: {}", transaction.getTargetPath());
      return URI.create(transaction.getTargetPath());
    } catch (SsoClientException e) {
      log.error("[PageAccessService] SSO 客户端通信异常", e);
      HttpStatus status = HttpStatus.BAD_REQUEST;
      if (e.isUnavailable()) {
        status = HttpStatus.SERVICE_UNAVAILABLE;
      }
      throw new ResponseStatusException(status, e.getMessage());
    }
  }

  public UserInfo currentUser(HttpSession session) {
    if (session == null) {
      return null;
    }
    Object value = session.getAttribute(USER_ATTRIBUTE);
    if (value instanceof UserInfo) {
      return (UserInfo) value;
    }
    return null;
  }

  public int authorizedPageCount(HttpSession session) {
    if (session == null) {
      return 0;
    }
    return tokens.size(session);
  }

  /** 清本地该页面授权并撤销其 Token family；不影响 Portal SID。 */
  public boolean logoutPage(HttpSession session, String pageCode) {
    if (session == null) {
      return false;
    }
    // 1. 先移除本地授权，随后才能尝试远端注销。
    StoredToken token = tokens.remove(session, config.tokenKey(pageCode));
    if (token == null) {
      return false;
    }
    // 2. 撤销远端 Token family；通信失败也不恢复已清理的本地 Token。
    try {
      return sso.logout(token.getAccessToken());
    } catch (SsoClientException e) {
      // 本地 Token 已清除；调用方会收到失败提示，但不能继续复用该 Token。
      HttpStatus status = HttpStatus.BAD_REQUEST;
      if (e.isUnavailable()) {
        status = HttpStatus.SERVICE_UNAVAILABLE;
      }
      throw new ResponseStatusException(status, "本地页面授权已清除，但 SSO 注销未确认");
    }
  }

  /** 逐个撤销本系统 Token family、销毁本地会话；不等同于 Portal 全局退出。 */
  public LogoutSummary logoutSystem(HttpSession session) {
    if (session == null) {
      return new LogoutSummary(0, 0);
    }

    // 1. 取得本地 Token 快照；同一个 AccessToken 只注销一次。
    List<StoredToken> values = tokens.snapshot(session);
    Set<String> accessTokens = new HashSet<String>();
    int confirmed = 0;
    for (StoredToken value : values) {
      if (value == null) {
        continue;
      }
      String accessToken = value.getAccessToken();
      if (accessTokens.contains(accessToken)) {
        continue;
      }
      accessTokens.add(accessToken);

      // 2. 逐个请求远端注销，记录得到确认的数量。
      try {
        if (sso.logout(accessToken)) {
          confirmed++;
        }
      } catch (SsoClientException ignored) {
        // 一个 Token 注销失败，不影响其余 Token 和本地退出。
      }
    }

    // 3. 无论远端确认了几个，都销毁本地会话并返回统计。
    int attempted = accessTokens.size();
    session.invalidate();
    return new LogoutSummary(attempted, confirmed);
  }

  /** 同一会话锁内只用一次旧 RefreshToken，验证后成对替换新 Access/Refresh。 */
  private StoredToken tryRefresh(
      HttpSession session, String tokenKey, StoredToken current, String pageCode) {
    // 1. 没有可用的 RefreshToken 时，清理该组本地 Token。
    if (!StringUtils.hasText(current.getRefreshToken()) || current.getRefreshExpiresAt() <= nowSeconds()) {
      tokens.remove(session, tokenKey);
      return null;
    }
    try {
      // 2. 申请新 Token 对，并核对返回的绑定信息。
      TokenData refreshed = sso.refresh(current.getRefreshToken());
      validateIssuedToken(refreshed, pageCode);
      StoredToken replacement = new StoredToken(refreshed, nowSeconds());
      // 3. 校验新 AccessToken 通过后，才整体替换本地 Token 对。
      ActiveTokenData checked = sso.checkAccessToken(replacement.getAccessToken(), pageCode);
      if (!matches(checked, replacement, pageCode)) {
        tokens.remove(session, tokenKey);
        return null;
      }
      // 新旧 AccessToken/RefreshToken 必须作为一对原子替换。
      tokens.put(session, tokenKey, replacement);
      return replacement;
    } catch (ResponseStatusException e) {
      // 4. 新 Token 的绑定异常时，旧 RefreshToken 也可能已被消费，不能留给下次重放。
      tokens.remove(session, tokenKey);
      throw e;
    } catch (SsoClientException e) {
      // 5. 旧 RefreshToken 可能已被消费，先清理本地 Token，避免下次请求重放。
      tokens.remove(session, tokenKey);
      if (e.isUnavailable()) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
      }
      return null;
    }
  }

  private ActiveTokenData checkOrFailClosed(String accessToken, String pageCode) {
    try {
      return sso.checkAccessToken(accessToken, pageCode);
    } catch (SsoClientException e) {
      // 【失败关闭】SSO 不可达时返回错误；不能按 Token 已失效重新授权或直接放行业务页面。
      HttpStatus status = HttpStatus.BAD_GATEWAY;
      if (e.isUnavailable()) {
        status = HttpStatus.SERVICE_UNAVAILABLE;
      }
      throw new ResponseStatusException(status, e.getMessage());
    }
  }

  private boolean matches(ActiveTokenData checked, StoredToken token, String requestedPageCode) {
    String expectedPage = expectedPageCode(requestedPageCode);
    // 1. SSO 明确确认 Token 有效。
    if (checked == null || !checked.isActive()) {
      return false;
    }
    // 2. 返回结果属于当前客户端和请求的页面。
    if (!config.getClientId().equals(checked.getClientId())) {
      return false;
    }
    if (!Objects.equals(expectedPage, checked.getPageCode())) {
      return false;
    }
    // 3. 返回用户与本地这组 Token 的持有人一致。
    return token.getSubject().equals(checked.getUid());
  }

  private void validateIssuedToken(TokenData issued, String requestedPageCode) {
    String expectedPage = expectedPageCode(requestedPageCode);
    if (issued == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SSO 返回的 Token 绑定不匹配");
    }

    // 1. Token 对和用户标识必须完整。
    if (!StringUtils.hasText(issued.getAccessToken())
        || !StringUtils.hasText(issued.getRefreshToken())
        || !StringUtils.hasText(issued.getOpenid())) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SSO 返回的 Token 绑定不匹配");
    }

    // 2. 客户端和页面绑定必须与本次请求相符。
    if (!config.getClientId().equals(issued.getClientId())
        || !Objects.equals(expectedPage, issued.getPageCode())) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SSO 返回的 Token 绑定不匹配");
    }
  }

  /** 受控模式绑定具体页面；SSO_ONLY 必须保持 page_code=null，不能把业务页面码混进协议。 */
  private String expectedPageCode(String pageCode) {
    if (config.isPageControlled()) {
      return pageCode;
    }
    return null;
  }

  /** 从生成 state 到回调完成的墙钟时间：包含浏览器跳转、Portal 登录等待及 B 回调处理。 */
  private long browserRoundTripMs(LoginTransaction transaction) {
    long startedAt = transaction.getStartedAtMillis();
    if (startedAt <= 0L) {
      return -1L;
    }
    return Math.max(0L, System.currentTimeMillis() - startedAt);
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
