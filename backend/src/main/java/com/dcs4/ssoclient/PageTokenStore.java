package com.dcs4.ssoclient;

import com.dcs4.ssoclient.SsoModels.StoredToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * 示例用服务端 Token 仓库。
 *
 * <p>浏览器只持有 HttpOnly 的 JSESSIONID，实际 AccessToken/RefreshToken 保存在
 * HttpSession 对象中。生产多实例应替换为 Redis 等共享存储，并加密静态 Token。</p>
 *
 * <p>【接入必要项 5/5：Token 仓库】只替换本类的会话存储实现即可连接业务系统原有
 * Session/Redis。键由 SsoConfig.tokenKey 决定；Access/Refresh 必须成对保存，不得交给 Vue。</p>
 */
@Component
public class PageTokenStore {
  private static final String ATTRIBUTE = PageTokenStore.class.getName() + ".tokens";

  public StoredToken get(HttpSession session, String key) {
    synchronized (session) {
      Map<String, StoredToken> values = values(session, false);
      return values == null ? null : values.get(key);
    }
  }

  /** 刷新成功后用一个新 StoredToken 整体替换旧 Token 对，禁止分别覆盖两种 Token。 */
  public void put(HttpSession session, String key, StoredToken token) {
    synchronized (session) {
      values(session, true).put(key, token);
    }
  }

  public StoredToken remove(HttpSession session, String key) {
    synchronized (session) {
      Map<String, StoredToken> values = values(session, false);
      if (values == null) {
        return null;
      }
      StoredToken removed = values.remove(key);
      if (values.isEmpty()) {
        session.removeAttribute(ATTRIBUTE);
      }
      return removed;
    }
  }

  public List<StoredToken> snapshot(HttpSession session) {
    synchronized (session) {
      Map<String, StoredToken> values = values(session, false);
      return values == null
          ? new ArrayList<StoredToken>()
          : new ArrayList<StoredToken>(values.values());
    }
  }

  public int size(HttpSession session) {
    synchronized (session) {
      Map<String, StoredToken> values = values(session, false);
      return values == null ? 0 : values.size();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, StoredToken> values(HttpSession session, boolean create) {
    Object raw = session.getAttribute(ATTRIBUTE);
    if (raw instanceof Map) {
      return (Map<String, StoredToken>) raw;
    }
    if (!create) {
      return null;
    }
    Map<String, StoredToken> values = new LinkedHashMap<String, StoredToken>();
    session.setAttribute(ATTRIBUTE, values);
    return values;
  }
}
