package com.dcs4.ssoclient;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    http.authorizeRequests()
        .anyRequest()
        .permitAll()
        .and()
        .csrf()
        .ignoringAntMatchers("/api/sso/backchannel-logout");
    http.formLogin().disable();
    http.httpBasic().disable();
    http.logout().disable();
    return http.build();
  }
}
