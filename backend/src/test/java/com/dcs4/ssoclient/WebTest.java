package com.dcs4.ssoclient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
  void anonymousHasNoLocalUser() throws Exception {
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void pageCatalogIsPublicButContainsNoToken() throws Exception {
    mvc.perform(get("/api/pages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("B_PAGE_01"))
        .andExpect(jsonPath("$[0].path").value("/pages/orders"));
  }

  @Test
  void localLogoutRequiresCsrf() throws Exception {
    mvc.perform(post("/api/auth/logout/system")).andExpect(status().isForbidden());
  }

  @Test
  void csrfTokenIsAvailable() throws Exception {
    mvc.perform(get("/api/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }
}
