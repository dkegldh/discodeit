package com.sprint.mission.discodeit.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChannelSecurityServiceTest {

  @Mock
  private ChannelRepository channelRepository;
  @Mock
  private ReadStatusRepository readStatusRepository;

  private ChannelSecurityService channelSecurityService;

  private UUID channelId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    channelSecurityService = new ChannelSecurityService(channelRepository, readStatusRepository);
    channelId = UUID.randomUUID();
    userId = UUID.randomUUID();
  }

  @Test
  @DisplayName("Public 채널은 참여 여부와 관계없이 접근 가능")
  void canAccess_publicChannel_true() {
    given(channelRepository.findById(channelId))
        .willReturn(Optional.of(new Channel("public", "", ChannelType.PUBLIC)));

    assertThat(channelSecurityService.canAccess(channelId, userId)).isTrue();
  }

  @Test
  @DisplayName("Private 채널은 참여자면 접근 가능")
  void canAccess_privateChannelMember_true() {
    given(channelRepository.findById(channelId))
        .willReturn(Optional.of(new Channel("", "", ChannelType.PRIVATE)));
    given(readStatusRepository.existsByUserIdAndChannelId(userId, channelId)).willReturn(true);

    assertThat(channelSecurityService.canAccess(channelId, userId)).isTrue();
  }

  @Test
  @DisplayName("Private 채널은 참여자가 아니면 접근 불가")
  void canAccess_privateChannelOutsider_false() {
    given(channelRepository.findById(channelId))
        .willReturn(Optional.of(new Channel("", "", ChannelType.PRIVATE)));
    given(readStatusRepository.existsByUserIdAndChannelId(userId, channelId)).willReturn(false);

    assertThat(channelSecurityService.canAccess(channelId, userId)).isFalse();
  }

  @Test
  @DisplayName("채널이 없으면 true를 반환해 인가를 통과시키고, 서비스에서 404를 응답하도록 함")
  void canAccess_channelNotFound_true() {
    given(channelRepository.findById(channelId)).willReturn(Optional.empty());

    assertThat(channelSecurityService.canAccess(channelId, userId)).isTrue();
  }

  @Test
  @DisplayName("읽음 상태는 소유자만 수정 가능")
  void isReadStatusOwner() {
    User owner = new User("owner", "o@test.com", "pw", null);
    ReflectionTestUtils.setField(owner, "id", userId);
    UUID readStatusId = UUID.randomUUID();
    given(readStatusRepository.findById(readStatusId)).willReturn(Optional.of(
        new ReadStatus(owner, new Channel("", "", ChannelType.PRIVATE), Instant.now(), true)));

    assertThat(channelSecurityService.isReadStatusOwner(readStatusId, userId)).isTrue();
    assertThat(channelSecurityService.isReadStatusOwner(readStatusId, UUID.randomUUID()))
        .isFalse();
  }
}
