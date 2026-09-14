package org.jeecg.modules.sso.util;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jeecg.modules.sso.constant.SsoRedisKey;

public final class SsoCookie {
    private SsoCookie() { }
    public static String readSid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (SsoRedisKey.COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
    public static void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", SsoRedisKey.COOKIE_NAME + "=; Path=" + SsoRedisKey.COOKIE_PATH
                + "; Max-Age=0; HttpOnly; SameSite=Lax");
    }
    public static void write(HttpServletResponse response, String sid, long maxAgeSeconds) {
        response.addHeader("Set-Cookie", SsoRedisKey.COOKIE_NAME + "=" + sid + "; Path="
                + SsoRedisKey.COOKIE_PATH + "; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax");
    }
}
