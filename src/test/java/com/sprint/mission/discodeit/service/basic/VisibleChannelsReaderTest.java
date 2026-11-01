package com.sprint.mission.discodeit.service.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;

import com.sprint.mission.discodeit.dto.channel.ChannelDto;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.mapper.ChannelMapper;
import com.sprint.mission.discodeit.mapper.UserMapper;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class VisibleChannelsReaderTest {

  @Mock
  private ChannelRepository channelRepository;
  @Mock
  private ReadStatusRepository readStatusRepository;
  @Mock
  private UserMapper userMapper;
  @Mock
  private ChannelMapper channelMapper;

  private VisibleChannelsReader reader;

  private UUID userId;
  private User user;
  private UserDto userDto;

  @BeforeEach
  void setUp() {
    reader = new VisibleChannelsReader(channelRepository, readStatusRepository, userMapper,
        channelMapper);

    userId = UUID.randomUUID();
    user = new User("tester", "test@test.com", "password123", null);
    ReflectionTestUtils.setField(user, "id", userId);
    userDto = new UserDto(userId, "tester", "test@test.com", null, false, Role.USER);
  }

  private Channel channel(ChannelType type, Instant createdAt) {
    Channel channel = new Channel("channel", "", type);
    ReflectionTestUtils.setField(channel, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(channel, "createdAt", createdAt);
    return channel;
  }

  private void givenChannelMapperBuildsDtoFromArguments() {
    given(channelMapper.toDto(any(Channel.class), nullable(Instant.class), any()))
        .willAnswer(inv -> {
          Channel c = inv.getArgument(0);
          return new ChannelDto(c.getId(), c.getName(), c.getType(), c.getDescription(),
              inv.getArgument(1), inv.getArgument(2));
        });
  }

  @Test
  @DisplayName("Public 채널은 참여 여부와 관계없이 반환되고, lastMessageAt에는 채널 생성 시각이 담김")
  void read_publicChannel_includedWithCreatedAt() {
    // given
    Instant createdAt = Instant.now().minusSeconds(60);
    Channel publicChannel = channel(ChannelType.PUBLIC, createdAt);
    given(channelRepository.findAll()).willReturn(List.of(publicChannel));
    given(readStatusRepository.findAllByUserId(userId)).willReturn(Collections.emptyList());
    given(readStatusRepository.findAllByChannelIdIn(List.of())).willReturn(List.of());
    givenChannelMapperBuildsDtoFromArguments();

    // when
    List<ChannelDto> result = reader.read(userId);

    // then
    assertThat(result).singleElement().satisfies(dto -> {
      assertThat(dto.id()).isEqualTo(publicChannel.getId());
      assertThat(dto.lastMessageAt()).isEqualTo(createdAt);
    });
  }

  @Test
  @DisplayName("참여 중인 Private 채널은 참여자 목록과 함께 반환됨")
  void read_joinedPrivateChannel_includedWithParticipants() {
    // given
    Channel privateChannel = channel(ChannelType.PRIVATE, Instant.now());
    ReadStatus readStatus = new ReadStatus(user, privateChannel, Instant.now(), true);
    given(channelRepository.findAll()).willReturn(List.of(privateChannel));
    given(readStatusRepository.findAllByUserId(userId)).willReturn(List.of(readStatus));
    given(readStatusRepository.findAllByChannelIdIn(List.of(privateChannel.getId())))
        .willReturn(List.of(readStatus));
    given(userMapper.toDto(user, false)).willReturn(userDto);
    givenChannelMapperBuildsDtoFromArguments();

    // when
    List<ChannelDto> result = reader.read(userId);

    // then
    assertThat(result).singleElement()
        .satisfies(dto -> assertThat(dto.participants()).containsExactly(userDto));
  }

  @Test
  @DisplayName("참여하지 않은 Private 채널은 반환되지 않음")
  void read_notJoinedPrivateChannel_excluded() {
    // given
    given(channelRepository.findAll()).willReturn(List.of(channel(ChannelType.PRIVATE,
        Instant.now())));
    given(readStatusRepository.findAllByUserId(userId)).willReturn(Collections.emptyList());
    given(readStatusRepository.findAllByChannelIdIn(List.of())).willReturn(List.of());

    // when & then
    assertThat(reader.read(userId)).isEmpty();
  }
}
