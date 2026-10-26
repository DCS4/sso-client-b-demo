package com.dcs4.ssoclient.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  // 页面授权由 PageAccessService.enter 显式执行；permitAll 不代表新增业务接口自动受保护。
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    http.authorizeRequests()
        .anyRequest()
        .permitAll()
        .and()
        .csrf();
    // SSO V2 没有外部 Back-Channel Logout 回调，因此所有本地 POST 都保留 CSRF 保护。
    http.formLogin().disable();
    http.httpBasic().disable();
    http.logout().disable();
    return http.build();
  }
}
