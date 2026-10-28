package com.sprint.mission.discodeit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sprint.mission.discodeit.security.jwt.dto.JwtInformation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class RedisConfigTest {

  private RedisSerializer<Object> serializer;

  @BeforeEach
  void setUp() {
    serializer = new RedisConfig().jwtRedisSerializer(Jackson2ObjectMapperBuilder.json().build());
  }

  @Test
  @DisplayName("Redis에 저장되는 JwtInformation은 직렬화 후 같은 값으로 복원됨")
  void jwtInformation_roundTrip() {
    JwtInformation info = new JwtInformation(UUID.randomUUID(), "access", "refresh",
        Instant.now().truncatedTo(ChronoUnit.MILLIS));

    assertThat(serializer.deserialize(serializer.serialize(info))).isEqualTo(info);
  }

  @Test
  @DisplayName("허용 목록 밖의 타입 정보가 담긴 값은 역직렬화를 거부함")
  void untrustedType_isRejected() {
    byte[] payload = "[\"java.io.File\",\"/etc/passwd\"]".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> serializer.deserialize(payload))
        .hasMessageContaining("java.io.File");
  }
}
