package com.sprint.mission.discodeit.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.security.DiscodeitUserDetails;
import com.sprint.mission.discodeit.security.jwt.dto.JwtInformation;
import com.sprint.mission.discodeit.security.service.DiscodeitUserDetailsService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

class JwtAuthenticationChannelInterceptorTest {

  private JwtTokenProvider tokenProvider;
  private InMemoryJwtRegistry jwtRegistry;
  private DiscodeitUserDetailsService userDetailsService;
  private JwtAuthenticationChannelInterceptor interceptor;

  private UUID userId;
  private String accessToken;

  @BeforeEach
  void setUp() {
    tokenProvider = new JwtTokenProvider();
    ReflectionTestUtils.setField(tokenProvider, "secretKey",
        "test-jwt-secret-key-must-be-at-least-32-bytes");
    ReflectionTestUtils.setField(tokenProvider, "accessTokenValidity", 1800L);
    ReflectionTestUtils.setField(tokenProvider, "refreshTokenValidity", 3600L);
    tokenProvider.init();

    jwtRegistry = new InMemoryJwtRegistry(1, mock(ApplicationEventPublisher.class));
    userDetailsService = mock(DiscodeitUserDetailsService.class);
    interceptor = new JwtAuthenticationChannelInterceptor(tokenProvider, jwtRegistry,
        userDetailsService);

    userId = UUID.randomUUID();
    accessToken = tokenProvider.createAccessToken(userId, "tester");
    jwtRegistry.registerJwtInformation(new JwtInformation(userId, accessToken,
        tokenProvider.createRefreshToken(userId, "tester"), Instant.now().plusSeconds(1800)));
  }

  private Message<?> connectWith(String authorizationHeader) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    if (authorizationHeader != null) {
      accessor.addNativeHeader("Authorization", authorizationHeader);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  @DisplayName("클라이언트가 보내는 'Bearer {token}' 형식으로 CONNECT하면 인증 정보가 설정됨")
  void connect_withBearerToken_setsUser() {
    // given
    DiscodeitUserDetails userDetails = new DiscodeitUserDetails(
        new UserDto(userId, "tester", "test@test.com", null, true, Role.USER), "pw");
    given(userDetailsService.loadUserByUsername("tester")).willReturn(userDetails);

    // when
    Message<?> result = interceptor.preSend(connectWith("Bearer " + accessToken),
        mock(MessageChannel.class));

    // then
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
    assertThat(accessor.getUser()).isNotNull();
    assertThat(accessor.getUser().getName()).isEqualTo("tester");
  }

  @Test
  @DisplayName("토큰 없이 CONNECT하면 거부됨")
  void connect_withoutToken_rejected() {
    assertThatThrownBy(() -> interceptor.preSend(connectWith(null), mock(MessageChannel.class)))
        .isInstanceOf(MessagingException.class);
  }

  @Test
  @DisplayName("로그아웃 등으로 레지스트리에서 제거된 토큰으로 CONNECT하면 거부됨")
  void connect_withRevokedToken_rejected() {
    jwtRegistry.invalidateJwtInformationByUserId(userId);

    assertThatThrownBy(() -> interceptor.preSend(connectWith("Bearer " + accessToken),
        mock(MessageChannel.class)))
        .isInstanceOf(MessagingException.class);
  }
}
