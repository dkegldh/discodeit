package com.sprint.mission.discodeit.security.jwt;

import com.sprint.mission.discodeit.dto.user.UserOnlineStatusChangedEvent;
import com.sprint.mission.discodeit.exception.auth.RefreshTokenInvalidException;
import com.sprint.mission.discodeit.redis.RedisLockProvider;
import com.sprint.mission.discodeit.redis.RedisLockProvider.RedisLockAcquisitionException;
import com.sprint.mission.discodeit.security.jwt.dto.JwtInformation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Redis 기반 JWT 레지스트리.
 * <p>
 * 키 구조
 * <ul>
 *   <li>{@code jwt:user:{userId}} (List) - 사용자의 활성 세션. 가장 늦은 refresh token 만료 시각에 만료</li>
 *   <li>{@code jwt:access:{token}}, {@code jwt:refresh:{token}} (String) - 토큰별 조회용 인덱스.
 *   토큰마다 자기 만료 시각에 만료되므로 다른 사용자의 로그인에 영향을 받지 않는다</li>
 *   <li>{@code jwt:online-users} (Set) - 온라인 사용자 ID. {@code KEYS} 명령 없이 조회하기 위함</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisJwtRegistry implements JwtRegistry {

  private static final String USER_JWT_KEY_PREFIX = "jwt:user:";
  private static final String ACCESS_TOKEN_KEY_PREFIX = "jwt:access:";
  private static final String REFRESH_TOKEN_KEY_PREFIX = "jwt:refresh:";
  private static final String ONLINE_USERS_KEY = "jwt:online-users";

  private final int maxActiveJwtCount;
  private final ApplicationEventPublisher eventPublisher;
  private final RedisTemplate<String, Object> jwtRedisTemplate;
  private final RedisLockProvider redisLockProvider;

  @Retryable(retryFor = RedisLockAcquisitionException.class, maxAttempts = 10,
      backoff = @Backoff(delay = 100, multiplier = 2))
  @Override
  public void registerJwtInformation(JwtInformation jwtInformation) {
    UUID userId = jwtInformation.userId();
    String userKey = getUserKey(userId);
    String lockKey = userId.toString();

    boolean wasOffline;
    redisLockProvider.acquireLock(lockKey);
    try {
      wasOffline = !hasActiveJwtInformationByUserId(userId);

      jwtRedisTemplate.opsForList().rightPush(userKey, jwtInformation);
      jwtRedisTemplate.expireAt(userKey, jwtInformation.expiration());
      addTokenIndex(jwtInformation);
      jwtRedisTemplate.opsForSet().add(ONLINE_USERS_KEY, userId.toString());

      Long currentSize = jwtRedisTemplate.opsForList().size(userKey);
      while (currentSize != null && currentSize > maxActiveJwtCount) {
        Object oldest = jwtRedisTemplate.opsForList().leftPop(userKey);
        if (oldest instanceof JwtInformation oldestInfo) {
          removeTokenIndex(oldestInfo);
        }
        currentSize = jwtRedisTemplate.opsForList().size(userKey);
      }
    } finally {
      redisLockProvider.releaseLock(lockKey);
    }

    if (wasOffline) {
      eventPublisher.publishEvent(new UserOnlineStatusChangedEvent(userId, true));
    }
  }

  @Retryable(retryFor = RedisLockAcquisitionException.class, maxAttempts = 10,
      backoff = @Backoff(delay = 100, multiplier = 2))
  @Override
  public void invalidateJwtInformationByUserId(UUID userId) {
    String lockKey = userId.toString();

    boolean wasOnline;
    redisLockProvider.acquireLock(lockKey);
    try {
      wasOnline = revokeAll(userId);
    } finally {
      redisLockProvider.releaseLock(lockKey);
    }

    if (wasOnline) {
      eventPublisher.publishEvent(new UserOnlineStatusChangedEvent(userId, false));
    }
  }

  @Override
  public boolean hasActiveJwtInformationByUserId(UUID userId) {
    Long size = jwtRedisTemplate.opsForList().size(getUserKey(userId));
    return size != null && size > 0;
  }

  @Override
  public boolean hasActiveJwtInformationByAccessToken(String accessToken) {
    return Boolean.TRUE.equals(jwtRedisTemplate.hasKey(ACCESS_TOKEN_KEY_PREFIX + accessToken));
  }

  @Override
  public boolean hasActiveJwtInformationByRefreshToken(String refreshToken) {
    return Boolean.TRUE.equals(jwtRedisTemplate.hasKey(REFRESH_TOKEN_KEY_PREFIX + refreshToken));
  }

  /**
   * refresh token을 회전한다.
   * <p>
   * 호출 전에 활성 여부를 확인하므로, 여기서 회전 대상을 찾지 못하는 것은 같은 refresh token으로 동시에 회전을
   * 시도해 다른 요청이 먼저 회전한 경우다. 이때 새 세션을 등록하면 토큰 하나로 유효한 세션이 두 개 생기므로 거부한다.
   */
  @Retryable(retryFor = RedisLockAcquisitionException.class, maxAttempts = 10,
      backoff = @Backoff(delay = 100, multiplier = 2))
  @Override
  public JwtInformation rotateJwtInformation(String oldRefreshToken,
      JwtInformation newJwtInformation) {
    UUID userId = newJwtInformation.userId();
    String userKey = getUserKey(userId);
    String lockKey = userId.toString();

    redisLockProvider.acquireLock(lockKey);
    try {
      List<Object> tokens = jwtRedisTemplate.opsForList().range(userKey, 0, -1);
      if (tokens != null) {
        for (int i = 0; i < tokens.size(); i++) {
          if (tokens.get(i) instanceof JwtInformation info
              && info.refreshToken().equals(oldRefreshToken)) {
            removeTokenIndex(info);
            jwtRedisTemplate.opsForList().set(userKey, i, newJwtInformation);
            jwtRedisTemplate.expireAt(userKey, newJwtInformation.expiration());
            addTokenIndex(newJwtInformation);
            return newJwtInformation;
          }
        }
      }

    } finally {
      redisLockProvider.releaseLock(lockKey);
    }

    log.warn("refresh token 회전 실패 - 이미 회전되었거나 무효화된 토큰. userId: {}", userId);
    throw new RefreshTokenInvalidException();
  }

  @Override
  public Set<UUID> getActiveUserIds() {
    Set<Object> members = jwtRedisTemplate.opsForSet().members(ONLINE_USERS_KEY);
    if (members == null) {
      return Set.of();
    }
    return members.stream()
        .map(member -> UUID.fromString(member.toString()))
        .collect(Collectors.toSet());
  }

  /**
   * 토큰 인덱스와 세션 목록은 TTL로 만료되므로, 여기서는 온라인 사용자 목록과 오프라인 이벤트만 정리한다.
   */
  @Scheduled(fixedDelay = 1000 * 60 * 5)
  @Override
  public void clearExpiredJwtInformation() {
    Instant now = Instant.now();

    for (UUID userId : getActiveUserIds()) {
      String lockKey = userId.toString();
      try {
        redisLockProvider.acquireLock(lockKey);
      } catch (RedisLockAcquisitionException e) {
        // 다른 요청이 처리 중인 사용자는 다음 주기에 정리
        continue;
      }

      boolean wentOffline;
      try {
        wentOffline = removeExpired(userId, now);
      } finally {
        redisLockProvider.releaseLock(lockKey);
      }

      if (wentOffline) {
        eventPublisher.publishEvent(new UserOnlineStatusChangedEvent(userId, false));
      }
    }
  }

  // 락을 잡은 상태에서 호출. 세션이 남아 있지 않게 되면 true
  private boolean removeExpired(UUID userId, Instant now) {
    String userKey = getUserKey(userId);
    List<Object> tokens = jwtRedisTemplate.opsForList().range(userKey, 0, -1);
    if (tokens != null) {
      for (Object tokenObj : tokens) {
        if (tokenObj instanceof JwtInformation info && !info.expiration().isAfter(now)) {
          jwtRedisTemplate.opsForList().remove(userKey, 1, info);
          removeTokenIndex(info);
        }
      }
    }

    if (hasActiveJwtInformationByUserId(userId)) {
      return false;
    }
    jwtRedisTemplate.opsForSet().remove(ONLINE_USERS_KEY, userId.toString());
    return true;
  }

  // 락을 잡은 상태에서 호출. 무효화 전에 온라인이었으면 true
  private boolean revokeAll(UUID userId) {
    String userKey = getUserKey(userId);
    List<Object> tokens = jwtRedisTemplate.opsForList().range(userKey, 0, -1);
    if (tokens != null) {
      tokens.forEach(tokenObj -> {
        if (tokenObj instanceof JwtInformation info) {
          removeTokenIndex(info);
        }
      });
    }

    jwtRedisTemplate.delete(userKey);
    Long removed = jwtRedisTemplate.opsForSet().remove(ONLINE_USERS_KEY, userId.toString());
    return (tokens != null && !tokens.isEmpty()) || (removed != null && removed > 0);
  }

  private String getUserKey(UUID userId) {
    return USER_JWT_KEY_PREFIX + userId;
  }

  private void addTokenIndex(JwtInformation info) {
    Duration ttl = Duration.between(Instant.now(), info.expiration());
    if (ttl.isNegative() || ttl.isZero()) {
      return;
    }
    String userId = info.userId().toString();
    jwtRedisTemplate.opsForValue().set(ACCESS_TOKEN_KEY_PREFIX + info.accessToken(), userId, ttl);
    jwtRedisTemplate.opsForValue().set(REFRESH_TOKEN_KEY_PREFIX + info.refreshToken(), userId, ttl);
  }

  private void removeTokenIndex(JwtInformation info) {
    jwtRedisTemplate.delete(List.of(
        ACCESS_TOKEN_KEY_PREFIX + info.accessToken(),
        REFRESH_TOKEN_KEY_PREFIX + info.refreshToken()
    ));
  }
}
