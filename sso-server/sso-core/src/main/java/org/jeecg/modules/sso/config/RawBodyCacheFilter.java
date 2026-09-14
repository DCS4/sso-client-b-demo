package org.jeecg.modules.sso.config;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** HMAC 必须按客户端发来的原始 body 字节计算摘要。 */
@Component
@Order(0)
public class RawBodyCacheFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if ("POST".equalsIgnoreCase(http.getMethod()) && http.getRequestURI().startsWith("/sso/v1/")) {
            chain.doFilter(new ContentCachingRequestWrapper(http), response);
        } else {
            chain.doFilter(request, response);
        }
    }
}
