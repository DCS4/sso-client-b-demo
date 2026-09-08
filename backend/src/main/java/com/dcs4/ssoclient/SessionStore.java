package com.dcs4.ssoclient;

import java.util.*;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

@Component
public class SessionStore {
  private final Map<String, Long> replay = new HashMap<>(), revoked = new HashMap<>();
  private final Map<String, Set<HttpSession>> sessions = new HashMap<>();

  private void clean() {
    long now = System.currentTimeMillis() / 1000;
    replay.values().removeIf(t -> t <= now);
    revoked.values().removeIf(t -> t <= now);
    sessions
        .values()
        .forEach(
            set ->
                set.removeIf(
                    s -> {
                      try {
                        return s.getMaxInactiveInterval() > 0
                            && s.getLastAccessedTime() / 1000 + s.getMaxInactiveInterval() <= now;
                      } catch (IllegalStateException e) {
                        return true;
                      }
                    }));
    sessions.values().removeIf(Set::isEmpty);
  }

  public synchronized void once(String jti, long expiry) {
    clean();
    if (replay.containsKey(jti)) throw new IllegalArgumentException("凭证已使用");
    replay.put(jti, expiry);
  }

  public synchronized void register(String sid, HttpSession s) {
    clean();
    if (revoked.containsKey(sid)) throw new IllegalArgumentException("会话已撤销");
    sessions.computeIfAbsent(sid, k -> new HashSet<>()).add(s);
  }

  public synchronized void revoke(String sid) {
    clean();
    revoked.put(sid, System.currentTimeMillis() / 1000 + 86400);
    Set<HttpSession> all = sessions.remove(sid);
    if (all != null)
      for (HttpSession s : all) {
        try {
          s.invalidate();
        } catch (IllegalStateException ignored) {
        }
      }
  }
}
