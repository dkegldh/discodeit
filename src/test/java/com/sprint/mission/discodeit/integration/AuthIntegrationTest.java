package com.sprint.mission.discodeit.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class AuthIntegrationTest extends AbstractIntegrationTest {

  private String accessToken;
  private Cookie refreshCookie;

  @BeforeEach
  void setUp() throws Exception {
    saveUser("tester", Role.USER);

    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .param("username", "tester")
            .param("password", DEFAULT_PASSWORD)
            .with(csrf()))
        .andExpect(status().isOk())
        .andReturn();
    accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
        .get("accessToken").asText();
    refreshCookie = result.getResponse().getCookie(JwtTokenProvider.REFRESH_TOKEN_COOKIE_NAME);
  }

  private MvcResult refresh(Cookie cookie) throws Exception {
    return mockMvc.perform(post("/api/auth/refresh").cookie(cookie).with(csrf())).andReturn();
  }

  @Test
  @DisplayName("refresh 후에는 새 토큰만 유효하고, 이전 access/refresh 토큰은 사용할 수 없음")
  void refresh_rotatesTokens() throws Exception {
    // when
    MvcResult refreshed = refresh(refreshCookie);

    // then
    String newAccessToken = objectMapper.readTree(refreshed.getResponse().getContentAsString())
        .get("accessToken").asText();
    Cookie newRefreshCookie = refreshed.getResponse()
        .getCookie(JwtTokenProvider.REFRESH_TOKEN_COOKIE_NAME);

    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccessToken))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie).with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    mockMvc.perform(post("/api/auth/refresh").cookie(newRefreshCookie).with(csrf()))
        .andExpect(status().isOk());
  }
}
