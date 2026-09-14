package org.jeecg.modules.sso.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** /internal/** 仅允许同机回环地址与 X-INNER-TOKEN；不存在跨机 HMAC 通道。 */
@Component
@Order(1)
public class InnerAuthFilter implements Filter {
    private final SsoProperties properties;
    public InnerAuthFilter(SsoProperties properties) { this.properties = properties; }
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (!http.getRequestURI().startsWith("/internal/")) {
            chain.doFilter(request, response);
            return;
        }
        String source = http.getRemoteAddr();
        String provided = http.getHeader("X-INNER-TOKEN");
        String expected = properties.getInnerToken();
        boolean loopback = "127.0.0.1".equals(source) || "::1".equals(source)
                || "0:0:0:0:0:0:0:1".equals(source);
        boolean tokenMatches = expected != null && !expected.trim().isEmpty() && provided != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8));
        if (loopback && tokenMatches) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse result = (HttpServletResponse) response;
        result.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        result.setContentType("application/json;charset=UTF-8");
        result.getWriter().write("{\"error\":\"invalid_inner_auth\"}");
    }
}
