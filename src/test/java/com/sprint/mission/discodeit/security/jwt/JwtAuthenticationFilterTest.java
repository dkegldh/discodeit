package com.sprint.mission.discodeit.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.security.DiscodeitUserDetails;
import com.sprint.mission.discodeit.security.service.DiscodeitUserDetailsService;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  private static final String TOKEN = "access-token";

  @Mock
  private JwtTokenProvider jwtTokenProvider;
  @Mock
  private JwtRegistry jwtRegistry;
  @Mock
  private DiscodeitUserDetailsService userDetailsService;
  @Mock
  private FilterChain filterChain;

  private JwtAuthenticationFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    filter = new JwtAuthenticationFilter(jwtTokenProvider, jwtRegistry, userDetailsService,
        new ObjectMapper());
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void givenValidSignedAccessToken() {
    given(jwtTokenProvider.validateToken(TOKEN)).willReturn(true);
    given(jwtTokenProvider.isAccessToken(TOKEN)).willReturn(true);
  }

  @Test
  @DisplayName("Authorization 헤더가 없으면 인증 없이 다음 필터로 진행")
  void noToken_passesThroughWithoutAuthentication() throws Exception {
    filter.doFilter(request, response, filterChain);

    then(filterChain).should().doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("유효하고 레지스트리에 등록된 Access 토큰이면 SecurityContext에 인증 정보가 설정됨")
  void validToken_setsAuthentication() throws Exception {
    // given
    UUID userId = UUID.randomUUID();
    DiscodeitUserDetails userDetails = new DiscodeitUserDetails(
        new UserDto(userId, "tester", "test@test.com", null, true, Role.USER), "pw");
    request.addHeader("Authorization", "Bearer " + TOKEN);
    givenValidSignedAccessToken();
    given(jwtRegistry.hasActiveJwtInformationByAccessToken(TOKEN)).willReturn(true);
    given(jwtTokenProvider.getUsername(TOKEN)).willReturn("tester");
    given(userDetailsService.loadUserByUsername("tester")).willReturn(userDetails);

    // when
    filter.doFilter(request, response, filterChain);

    // then
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getPrincipal()).isEqualTo(userDetails);
    assertThat(authentication.getAuthorities()).extracting("authority")
        .containsExactly("ROLE_USER");
    then(filterChain).should().doFilter(request, response);
  }

  @Test
  @DisplayName("서명/만료 검증에 실패한 토큰이면 401을 응답하고 다음 필터로 진행하지 않음")
  void invalidToken_returns401() throws Exception {
    // given
    request.addHeader("Authorization", "Bearer " + TOKEN);
    given(jwtTokenProvider.validateToken(TOKEN)).willReturn(false);

    // when
    filter.doFilter(request, response, filterChain);

    // then
    assertThat(response.getStatus()).isEqualTo(401);
    then(filterChain).should(never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("서명은 유효하지만 로그아웃 등으로 레지스트리에서 제거된 토큰이면 401")
  void revokedToken_returns401() throws Exception {
    // given
    request.addHeader("Authorization", "Bearer " + TOKEN);
    givenValidSignedAccessToken();
    given(jwtRegistry.hasActiveJwtInformationByAccessToken(TOKEN)).willReturn(false);

    // when
    filter.doFilter(request, response, filterChain);

    // then
    assertThat(response.getStatus()).isEqualTo(401);
    then(filterChain).should(never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("토큰의 사용자가 삭제되었으면 401")
  void deletedUser_returns401() throws Exception {
    // given
    request.addHeader("Authorization", "Bearer " + TOKEN);
    givenValidSignedAccessToken();
    given(jwtRegistry.hasActiveJwtInformationByAccessToken(TOKEN)).willReturn(true);
    given(jwtTokenProvider.getUsername(TOKEN)).willReturn("tester");
    given(userDetailsService.loadUserByUsername("tester"))
        .willThrow(new UsernameNotFoundException("tester"));

    // when
    filter.doFilter(request, response, filterChain);

    // then
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
