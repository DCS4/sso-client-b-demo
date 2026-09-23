package com.dcs4.ssoclient;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * 把一次性 state 与服务端登记的页面目标绑定。
 *
 * <p>使用 Map 而不是单一 session 属性，使同一浏览器的多个页签可以同时发起授权；
 * 每个 state 只能消费一次，且不会把任意 target URL 放进浏览器可修改的数据中。</p>
 *
 * <p>【接入必要项 4/5：登录事务仓库】真实厂商可以使用其已有会话/Redis 保存同一映射；
 * 必须保持不可预测、限时、回调先消费再兑 code、目标由服务端登记，不要改为前端传目标 URL。</p>
 */
@Component
public class LoginStateStore {
  private static final String ATTRIBUTE = LoginStateStore.class.getName() + ".transactions";
  private static final long TTL_MILLIS = 10L * 60L * 1000L;
  private static final int MAX_PENDING = 16;
  private final SecureRandom random = new SecureRandom();

  /** 进入未授权页面时创建 state，绑定当前服务端已登记的目标页；多个标签页可以并发授权。 */
  public String begin(HttpSession session, String pageCode, String targetPath) {
    synchronized (session) {
      Map<String, LoginTransaction> values = values(session, true);
      clean(values);
      while (values.size() >= MAX_PENDING) {
        Iterator<String> iterator = values.keySet().iterator();
        if (!iterator.hasNext()) {
          break;
        }
        iterator.next();
        iterator.remove();
      }
      String state = randomState();
      long startedAtMillis = System.currentTimeMillis();
      values.put(state, new LoginTransaction(
          pageCode, targetPath, startedAtMillis, startedAtMillis + TTL_MILLIS));
      return state;
    }
  }

  /** 先删除后返回；即使后续 code 兑换失败，该 state 也不能再次使用。 */
  public LoginTransaction consume(HttpSession session, String state) {
    if (session == null || state == null) {
      return null;
    }
    synchronized (session) {
      Map<String, LoginTransaction> values = values(session, false);
      if (values == null) {
        return null;
      }
      clean(values);
      LoginTransaction value = values.remove(state);
      if (values.isEmpty()) {
        session.removeAttribute(ATTRIBUTE);
      }
      return value;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, LoginTransaction> values(HttpSession session, boolean create) {
    Object raw = session.getAttribute(ATTRIBUTE);
    if (raw instanceof Map) {
      return (Map<String, LoginTransaction>) raw;
    }
    if (!create) {
      return null;
    }
    Map<String, LoginTransaction> values = new LinkedHashMap<String, LoginTransaction>();
    session.setAttribute(ATTRIBUTE, values);
    return values;
  }

  private void clean(Map<String, LoginTransaction> values) {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, LoginTransaction>> iterator = values.entrySet().iterator();
    while (iterator.hasNext()) {
      if (iterator.next().getValue().getExpiresAt() <= now) {
        iterator.remove();
      }
    }
  }

  private String randomState() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static final class LoginTransaction implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String pageCode;
    private final String targetPath;
    // 只用于跨浏览器跳转的粗粒度耗时观测，不参与 state 过期或消费判断。
    private final long startedAtMillis;
    private final long expiresAt;

    private LoginTransaction(
        String pageCode, String targetPath, long startedAtMillis, long expiresAt) {
      this.pageCode = pageCode;
      this.targetPath = targetPath;
      this.startedAtMillis = startedAtMillis;
      this.expiresAt = expiresAt;
    }

    public String getPageCode() {
      return pageCode;
    }

    public String getTargetPath() {
      return targetPath;
    }

    public long getStartedAtMillis() {
      return startedAtMillis;
    }

    public long getExpiresAt() {
      return expiresAt;
    }
  }
}
