package com.dcs4.ssoclient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WebTest {
  @Autowired MockMvc mvc;

  @Test
  void anonymousCannotReadBusiness() throws Exception {
    mvc.perform(get("/api/demo/business")).andExpect(status().isUnauthorized());
  }

  @Test
  void csrfRequiredForLocalLogout() throws Exception {
    mvc.perform(post("/api/auth/logout/local")).andExpect(status().isForbidden());
  }

  @Test
  void csrfTokenAvailable() throws Exception {
    mvc.perform(get("/api/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  @Test
  void machineEndpointRejectsInvalidToken() throws Exception {
    mvc.perform(
            post("/api/sso/backchannel-logout")
                .contentType("application/json")
                .content("{\"logout_token\":\"bad\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @org.springframework.boot.test.mock.mockito.MockBean SsoClient client;

  @Test
  void callbackCreatesLocalUserAndConsumesState() throws Exception {
    org.springframework.mock.web.MockHttpSession session =
        new org.springframework.mock.web.MockHttpSession();
    session.setAttribute("state", "expected");
    session.setAttribute("verifier", "verifier");
    session.setAttribute("started", System.currentTimeMillis());
    long now = System.currentTimeMillis();
    com.nimbusds.jwt.JWTClaimsSet claims =
        new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .subject("integration-user")
            .claim("username", "demo")
            .claim("sid", "integration-sid")
            .claim("session_expires_at", now / 1000 + 3600)
            .jwtID("integration-jti")
            .expirationTime(new java.util.Date(now + 60000))
            .build();
    org.mockito.Mockito.when(
            client.signed(
                org.mockito.ArgumentMatchers.eq("/exchange"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("identity_assertion"),
                org.mockito.ArgumentMatchers.eq("identity"),
                org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(claims);
    org.springframework.test.web.servlet.MvcResult result =
        mvc.perform(
                get("/api/auth/callback")
                    .param("code", "code")
                    .param("state", "expected")
                    .session(session))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    org.springframework.mock.web.MockHttpSession logged =
        (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
    mvc.perform(get("/api/auth/me").session(logged))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("DEMO_USER"));
    mvc.perform(
            get("/api/auth/callback")
                .param("code", "code")
                .param("state", "expected")
                .session(logged))
        .andExpect(status().isBadRequest());
  }
}
