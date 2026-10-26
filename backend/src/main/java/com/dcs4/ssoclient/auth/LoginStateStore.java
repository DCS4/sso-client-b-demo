package com.dcs4.ssoclient.auth;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * 保存一次性 state 及其目标页面。同一会话用 Map 保存多个登录事务，支持多个页签。
 * 每个事务都有过期时间，回调时先删除再使用。
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
      // 1. 取出事务表并清理过期记录。
      Map<String, LoginTransaction> values = getOrCreateTransactions(session);
      removeExpired(values);
      // 2. 超过上限时移除最早的记录，为新事务留出位置。
      while (values.size() >= MAX_PENDING) {
        Iterator<String> iterator = values.keySet().iterator();
        iterator.next();
        iterator.remove();
      }
      // 3. 生成随机 state，保存目标页面、开始时间和过期时间。
      String state = randomState();
      long startedAtMillis = System.currentTimeMillis();
      long expiresAt = startedAtMillis + TTL_MILLIS;
      LoginTransaction transaction = new LoginTransaction(pageCode, targetPath, startedAtMillis, expiresAt);
      values.put(state, transaction);
      return state;
    }
  }

  /** 先删除后返回；即使后续 code 兑换失败，该 state 也不能再次使用。 */
  public LoginTransaction consume(HttpSession session, String state) {
    if (session == null || state == null) {
      return null;
    }
    synchronized (session) {
      // 1. 只读取已有事务，不为无效回调新建事务表。
      Map<String, LoginTransaction> values = findTransactions(session);
      if (values == null) {
        return null;
      }
      // 2. 清理过期记录，再移除本次 state，保证只能消费一次。
      removeExpired(values);
      LoginTransaction value = values.remove(state);
      if (values.isEmpty()) {
        session.removeAttribute(ATTRIBUTE);
      }
      return value;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, LoginTransaction> findTransactions(HttpSession session) {
    Object raw = session.getAttribute(ATTRIBUTE);
    if (raw instanceof Map) {
      return (Map<String, LoginTransaction>) raw;
    }
    return null;
  }

  private Map<String, LoginTransaction> getOrCreateTransactions(HttpSession session) {
    Map<String, LoginTransaction> values = findTransactions(session);
    if (values == null) {
      values = new LinkedHashMap<String, LoginTransaction>();
      session.setAttribute(ATTRIBUTE, values);
    }
    return values;
  }

  private void removeExpired(Map<String, LoginTransaction> values) {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, LoginTransaction>> iterator = values.entrySet().iterator();
    while (iterator.hasNext()) {
      LoginTransaction transaction = iterator.next().getValue();
      if (transaction.getExpiresAt() <= now) {
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
