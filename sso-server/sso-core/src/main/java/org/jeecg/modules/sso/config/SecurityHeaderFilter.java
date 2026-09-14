package org.jeecg.modules.sso.config;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

@Component
public class SecurityHeaderFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse http = (HttpServletResponse) response;
        http.setHeader("Cache-Control", "no-store");
        http.setHeader("Pragma", "no-cache");
        http.setHeader("Referrer-Policy", "no-referrer");
        http.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
