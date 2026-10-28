package com.sprint.mission.discodeit.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtTokenProviderTest {

  private static final String SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes";

  private JwtTokenProvider tokenProvider;
  private UUID userId;

  private static JwtTokenProvider createProvider(String secret, long accessValidity) {
    JwtTokenProvider provider = new JwtTokenProvider();
    ReflectionTestUtils.setField(provider, "secretKey", secret);
    ReflectionTestUtils.setField(provider, "accessTokenValidity", accessValidity);
    ReflectionTestUtils.setField(provider, "refreshTokenValidity", 3600L);
    provider.init();
    return provider;
  }

  @BeforeEach
  void setUp() {
    tokenProvider = createProvider(SECRET, 1800L);
    userId = UUID.randomUUID();
  }

  @Test
  @DisplayName("Access 토큰은 검증을 통과하고 userId, username을 추출할 수 있음")
  void accessToken_validAndContainsClaims() {
    String token = tokenProvider.createAccessToken(userId, "tester");

    assertThat(tokenProvider.validateAccessToken(token)).isTrue();
    assertThat(tokenProvider.getUserId(token)).isEqualTo(userId);
    assertThat(tokenProvider.getUsername(token)).isEqualTo("tester");
  }

  @Test
  @DisplayName("Access 토큰과 Refresh 토큰은 타입으로 구분되어 서로 대체할 수 없음")
  void tokenType_isDistinguished() {
    String access = tokenProvider.createAccessToken(userId, "tester");
    String refresh = tokenProvider.createRefreshToken(userId, "tester");

    assertThat(tokenProvider.validateRefreshToken(access)).isFalse();
    assertThat(tokenProvider.validateAccessToken(refresh)).isFalse();
    assertThat(tokenProvider.validateRefreshToken(refresh)).isTrue();
  }

  @Test
  @DisplayName("같은 사용자에게 같은 시각에 발급해도 토큰은 서로 다름 (회전 후 이전 토큰과 구분)")
  void tokensIssuedAtSameSecond_areUnique() {
    String first = tokenProvider.createAccessToken(userId, "tester");
    String second = tokenProvider.createAccessToken(userId, "tester");

    assertThat(first).isNotEqualTo(second);
    assertThat(tokenProvider.createRefreshToken(userId, "tester"))
        .isNotEqualTo(tokenProvider.createRefreshToken(userId, "tester"));
  }

  @Test
  @DisplayName("만료된 토큰은 검증에 실패함")
  void expiredToken_isInvalid() {
    JwtTokenProvider expiredProvider = createProvider(SECRET, -1L);
    String token = expiredProvider.createAccessToken(userId, "tester");

    assertThat(expiredProvider.validateToken(token)).isFalse();
  }

  @Test
  @DisplayName("다른 키로 서명된 토큰은 검증에 실패함")
  void tokenSignedWithOtherKey_isInvalid() {
    JwtTokenProvider otherProvider = createProvider(
        "another-jwt-secret-key-must-be-at-least-32-bytes", 1800L);
    String forged = otherProvider.createAccessToken(userId, "tester");

    assertThat(tokenProvider.validateToken(forged)).isFalse();
  }

  @Test
  @DisplayName("JWT 형식이 아닌 문자열은 예외 없이 검증에 실패함")
  void malformedToken_isInvalid() {
    assertThat(tokenProvider.validateToken("not-a-jwt")).isFalse();
    assertThat(tokenProvider.isAccessToken("not-a-jwt")).isFalse();
  }

  @Test
  @DisplayName("32바이트 미만의 secret key로는 초기화할 수 없음")
  void shortSecret_failsOnInit() {
    assertThatThrownBy(() -> createProvider("short-secret", 1800L))
        .isInstanceOf(IllegalStateException.class);
  }
}
