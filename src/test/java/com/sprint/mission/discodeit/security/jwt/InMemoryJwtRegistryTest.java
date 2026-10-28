package com.sprint.mission.discodeit.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sprint.mission.discodeit.exception.auth.RefreshTokenInvalidException;
import com.sprint.mission.discodeit.security.jwt.dto.JwtInformation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class InMemoryJwtRegistryTest {

  private InMemoryJwtRegistry registry;
  private UUID userId;

  @BeforeEach
  void setUp() {
    registry = new InMemoryJwtRegistry(1, mock(ApplicationEventPublisher.class));
    userId = UUID.randomUUID();
  }

  private JwtInformation newToken() {
    String id = UUID.randomUUID().toString();
    return new JwtInformation(userId, "access-" + id, "refresh-" + id,
        Instant.now().plusSeconds(3600));
  }

  @Test
  @DisplayName("refresh token 회전 시 기존 토큰은 비활성화되고 새 토큰이 활성화됨")
  void rotate_replacesTokens() {
    JwtInformation old = newToken();
    JwtInformation rotated = newToken();
    registry.registerJwtInformation(old);

    registry.rotateJwtInformation(old.refreshToken(), rotated);

    assertThat(registry.hasActiveJwtInformationByRefreshToken(old.refreshToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByRefreshToken(rotated.refreshToken())).isTrue();
  }

  @Test
  @DisplayName("이미 회전된 refresh token으로 다시 회전하면 거부되고 새 세션이 생기지 않음")
  void rotate_alreadyRotatedRefreshToken_rejected() {
    JwtInformation old = newToken();
    JwtInformation first = newToken();
    JwtInformation second = newToken();
    registry.registerJwtInformation(old);
    registry.rotateJwtInformation(old.refreshToken(), first);

    assertThatThrownBy(() -> registry.rotateJwtInformation(old.refreshToken(), second))
        .isInstanceOf(RefreshTokenInvalidException.class);

    assertThat(registry.hasActiveJwtInformationByRefreshToken(second.refreshToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByAccessToken(first.accessToken())).isTrue();
  }
}
