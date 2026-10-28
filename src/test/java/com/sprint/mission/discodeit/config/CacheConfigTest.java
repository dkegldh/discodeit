package com.sprint.mission.discodeit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sprint.mission.discodeit.dto.binarycontent.BinaryContentDto;
import com.sprint.mission.discodeit.dto.channel.ChannelDto;
import com.sprint.mission.discodeit.dto.notification.NotificationDto;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.BinaryContentStatus;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Role;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class CacheConfigTest {

  private SerializationPair<Object> serializer;

  @BeforeEach
  void setUp() {
    // 애플리케이션과 같은 설정(JavaTimeModule 등)의 ObjectMapper
    serializer = new CacheConfig()
        .redisCacheConfiguration(Jackson2ObjectMapperBuilder.json().build())
        .getValueSerializationPair();
  }

  private Object roundTrip(Object value) {
    return serializer.read(serializer.write(value));
  }

  @Test
  @DisplayName("캐시에 저장되는 DTO 목록은 직렬화 후 같은 타입으로 복원됨")
  void cachedDtos_roundTrip() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    BinaryContentDto profile = new BinaryContentDto(UUID.randomUUID(), "profile.png", "image/png",
        1024L, BinaryContentStatus.SUCCESS);
    UserDto user = new UserDto(UUID.randomUUID(), "tester", "test@test.com", profile, true,
        Role.USER);
    List<ChannelDto> channels = List.of(new ChannelDto(UUID.randomUUID(), "channel",
        ChannelType.PRIVATE, "", now, List.of(user)));
    List<NotificationDto> notifications = List.of(
        new NotificationDto(UUID.randomUUID(), now, user.id(), "title", "content"));

    assertThat(roundTrip(List.of(user))).isEqualTo(List.of(user));
    assertThat(roundTrip(channels)).isEqualTo(channels);
    assertThat(roundTrip(notifications)).isEqualTo(notifications);
  }

  @Test
  @DisplayName("허용 목록 밖의 타입 정보가 담긴 캐시 값은 역직렬화를 거부함")
  void untrustedType_isRejected() {
    // Redis에 값을 쓸 수 있는 공격자가 임의 클래스를 지정한 경우
    ByteBuffer payload = ByteBuffer.wrap(
        "[\"java.io.File\",\"/etc/passwd\"]".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> serializer.read(payload))
        .hasMessageContaining("java.io.File");
  }
}
