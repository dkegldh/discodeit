package com.sprint.mission.discodeit.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sprint.mission.discodeit.config.RedisConfig;
import com.sprint.mission.discodeit.dto.user.UserOnlineStatusChangedEvent;
import com.sprint.mission.discodeit.exception.auth.RefreshTokenInvalidException;
import com.sprint.mission.discodeit.redis.RedisLockProvider;
import com.sprint.mission.discodeit.security.jwt.dto.JwtInformation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Redis(Testcontainers)에서 키 구조와 TTL을 검증한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisJwtRegistryTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static RedisTemplate<String, Object> redisTemplate;
  private static RedisLockProvider lockProvider;

  private ApplicationEventPublisher eventPublisher;
  private RedisJwtRegistry registry;

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();

    RedisConfig redisConfig = new RedisConfig();
    redisTemplate = redisConfig.jwtRedisTemplate(connectionFactory,
        redisConfig.jwtRedisSerializer(Jackson2ObjectMapperBuilder.json().build()));

    StringRedisTemplate stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    lockProvider = new RedisLockProvider(stringRedisTemplate);
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    eventPublisher = mock(ApplicationEventPublisher.class);
    registry = new RedisJwtRegistry(1, eventPublisher, redisTemplate, lockProvider);
  }

  private JwtInformation tokenFor(UUID userId, Duration validity) {
    String id = UUID.randomUUID().toString();
    return new JwtInformation(userId, "access-" + id, "refresh-" + id,
        Instant.now().plus(validity));
  }

  private Duration ttlOfAccessToken(JwtInformation info) {
    // 저장 방식과 무관하게 "이 access token이 언제까지 유효하게 남는가"를 측정
    await().atMost(Duration.ofSeconds(1)).until(() ->
        registry.hasActiveJwtInformationByAccessToken(info.accessToken()));
    return redisTemplate.keys("jwt:*").stream()
        .filter(key -> key.contains(info.accessToken()))
        .findFirst()
        .map(key -> Duration.ofSeconds(redisTemplate.getExpire(key)))
        .orElseThrow(() -> new AssertionError("access token 전용 키가 없음 (공용 Set에 저장됨)"));
  }

  @Test
  @DisplayName("등록한 토큰은 access/refresh/사용자 기준으로 모두 활성 상태이고, 온라인 이벤트가 한 번 발행됨")
  void register_activatesTokens() {
    UUID userId = UUID.randomUUID();
    JwtInformation info = tokenFor(userId, Duration.ofDays(14));

    registry.registerJwtInformation(info);

    assertThat(registry.hasActiveJwtInformationByAccessToken(info.accessToken())).isTrue();
    assertThat(registry.hasActiveJwtInformationByRefreshToken(info.refreshToken())).isTrue();
    assertThat(registry.hasActiveJwtInformationByUserId(userId)).isTrue();
    assertThat(registry.getActiveUserIds()).containsExactly(userId);
    verify(eventPublisher).publishEvent(new UserOnlineStatusChangedEvent(userId, true));
  }

  @Test
  @DisplayName("토큰은 각자의 만료 시각까지만 남고, 다른 사용자의 로그인이 만료 시각을 연장하지 않음")
  void tokenTtl_isPerToken() {
    JwtInformation shortLived = tokenFor(UUID.randomUUID(), Duration.ofMinutes(1));
    JwtInformation longLived = tokenFor(UUID.randomUUID(), Duration.ofDays(14));

    registry.registerJwtInformation(shortLived);
    registry.registerJwtInformation(longLived);

    assertThat(ttlOfAccessToken(shortLived)).isLessThanOrEqualTo(Duration.ofMinutes(1));
    assertThat(ttlOfAccessToken(longLived)).isGreaterThan(Duration.ofDays(13));
  }

  @Test
  @DisplayName("refresh token 유효기간(14일) 동안 사용자 세션이 유지됨 (30분 고정 TTL로 사라지지 않음)")
  void userSession_livesUntilRefreshExpiration() {
    UUID userId = UUID.randomUUID();
    registry.registerJwtInformation(tokenFor(userId, Duration.ofDays(14)));

    assertThat(redisTemplate.getExpire("jwt:user:" + userId))
        .isGreaterThan(Duration.ofDays(13).toSeconds());
  }

  @Test
  @DisplayName("최대 활성 개수를 넘으면 가장 오래된 토큰이 비활성화됨")
  void register_overMaxCount_evictsOldest() {
    UUID userId = UUID.randomUUID();
    JwtInformation first = tokenFor(userId, Duration.ofDays(14));
    JwtInformation second = tokenFor(userId, Duration.ofDays(14));

    registry.registerJwtInformation(first);
    registry.registerJwtInformation(second);

    assertThat(registry.hasActiveJwtInformationByAccessToken(first.accessToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByRefreshToken(first.refreshToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByAccessToken(second.accessToken())).isTrue();
    verify(eventPublisher, times(1)).publishEvent(any(UserOnlineStatusChangedEvent.class));
  }

  @Test
  @DisplayName("refresh token 회전 시 기존 토큰은 비활성화되고 새 토큰이 활성화됨")
  void rotate_replacesTokens() {
    UUID userId = UUID.randomUUID();
    JwtInformation old = tokenFor(userId, Duration.ofDays(14));
    JwtInformation rotated = tokenFor(userId, Duration.ofDays(14));
    registry.registerJwtInformation(old);

    registry.rotateJwtInformation(old.refreshToken(), rotated);

    assertThat(registry.hasActiveJwtInformationByRefreshToken(old.refreshToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByAccessToken(old.accessToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByRefreshToken(rotated.refreshToken())).isTrue();
    assertThat(registry.hasActiveJwtInformationByAccessToken(rotated.accessToken())).isTrue();
  }

  @Test
  @DisplayName("이미 회전된 refresh token으로 다시 회전하면 거부되고 새 세션이 생기지 않음 (동시 재발급 요청)")
  void rotate_alreadyRotatedRefreshToken_rejected() {
    UUID userId = UUID.randomUUID();
    JwtInformation old = tokenFor(userId, Duration.ofDays(14));
    JwtInformation first = tokenFor(userId, Duration.ofDays(14));
    JwtInformation second = tokenFor(userId, Duration.ofDays(14));
    registry.registerJwtInformation(old);
    registry.rotateJwtInformation(old.refreshToken(), first);

    assertThatThrownBy(() -> registry.rotateJwtInformation(old.refreshToken(), second))
        .isInstanceOf(RefreshTokenInvalidException.class);

    assertThat(registry.hasActiveJwtInformationByRefreshToken(second.refreshToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByAccessToken(second.accessToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByAccessToken(first.accessToken())).isTrue();
  }

  @Test
  @DisplayName("사용자 토큰을 무효화하면 모든 토큰이 비활성화되고 오프라인 이벤트가 발행됨")
  void invalidate_revokesAllTokens() {
    UUID userId = UUID.randomUUID();
    JwtInformation info = tokenFor(userId, Duration.ofDays(14));
    registry.registerJwtInformation(info);

    registry.invalidateJwtInformationByUserId(userId);

    assertThat(registry.hasActiveJwtInformationByAccessToken(info.accessToken())).isFalse();
    assertThat(registry.hasActiveJwtInformationByRefreshToken(info.refreshToken())).isFalse();
    assertThat(registry.getActiveUserIds()).isEmpty();
    verify(eventPublisher).publishEvent(new UserOnlineStatusChangedEvent(userId, false));
  }

  @Test
  @DisplayName("토큰이 없는 사용자를 무효화해도 오프라인 이벤트를 발행하지 않음")
  void invalidate_offlineUser_noEvent() {
    registry.invalidateJwtInformationByUserId(UUID.randomUUID());

    verify(eventPublisher, never()).publishEvent(any(UserOnlineStatusChangedEvent.class));
  }

  @Test
  @DisplayName("만료된 세션을 정리하면 온라인 목록에서 빠지고 오프라인 이벤트가 발행됨")
  void clearExpired_removesExpiredSessions() {
    UUID userId = UUID.randomUUID();
    registry.registerJwtInformation(tokenFor(userId, Duration.ofSeconds(1)));

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      registry.clearExpiredJwtInformation();
      assertThat(registry.getActiveUserIds()).isEmpty();
    });
    verify(eventPublisher).publishEvent(new UserOnlineStatusChangedEvent(userId, false));
  }
}
