package com.dcs4.ssoclient.auth;

import com.dcs4.ssoclient.sso.SsoModels.StoredToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * 把 Token 对保存在服务端 HttpSession 中；键由 SsoConfig.tokenKey() 决定。
 * 示例使用单实例会话，多实例适配见接入指南。
 */
@Component
public class PageTokenStore {
  private static final String ATTRIBUTE = PageTokenStore.class.getName() + ".tokens";

  public StoredToken get(HttpSession session, String key) {
    synchronized (session) {
      Map<String, StoredToken> values = findTokens(session);
      if (values == null) {
        return null;
      }
      return values.get(key);
    }
  }

  /** 刷新成功后用一个新 StoredToken 整体替换旧 Token 对，禁止分别覆盖两种 Token。 */
  public void put(HttpSession session, String key, StoredToken token) {
    synchronized (session) {
      Map<String, StoredToken> values = getOrCreateTokens(session);
      values.put(key, token);
    }
  }

  public StoredToken remove(HttpSession session, String key) {
    synchronized (session) {
      Map<String, StoredToken> values = findTokens(session);
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

  /** 账号切换时清除 B 本地全部页面 Token；不向 SSO 发起退出请求。 */
  public void clear(HttpSession session) {
    synchronized (session) {
      session.removeAttribute(ATTRIBUTE);
    }
  }

  public List<StoredToken> snapshot(HttpSession session) {
    synchronized (session) {
      Map<String, StoredToken> values = findTokens(session);
      if (values == null) {
        return new ArrayList<StoredToken>();
      }
      return new ArrayList<StoredToken>(values.values());
    }
  }

  public int size(HttpSession session) {
    synchronized (session) {
      Map<String, StoredToken> values = findTokens(session);
      if (values == null) {
        return 0;
      }
      return values.size();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, StoredToken> findTokens(HttpSession session) {
    // Session 返回 Object，类型转换集中放在这里。
    Object raw = session.getAttribute(ATTRIBUTE);
    if (raw instanceof Map) {
      return (Map<String, StoredToken>) raw;
    }
    return null;
  }

  private Map<String, StoredToken> getOrCreateTokens(HttpSession session) {
    Map<String, StoredToken> values = findTokens(session);
    if (values == null) {
      values = new LinkedHashMap<String, StoredToken>();
      session.setAttribute(ATTRIBUTE, values);
    }
    return values;
  }
}
