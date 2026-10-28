package com.sprint.mission.discodeit.service.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

import com.sprint.mission.discodeit.dto.channel.ChannelDto;
import com.sprint.mission.discodeit.dto.channel.ChannelUpdateRequest;
import com.sprint.mission.discodeit.dto.channel.PrivateChannelCreatedEvent;
import com.sprint.mission.discodeit.dto.channel.PrivateChannelRequest;
import com.sprint.mission.discodeit.dto.channel.PublicChannelRequest;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Message;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.event.ChannelCreatedEvent;
import com.sprint.mission.discodeit.event.ChannelDeletedEvent;
import com.sprint.mission.discodeit.event.ChannelUpdatedEvent;
import com.sprint.mission.discodeit.exception.channel.ChannelNotFoundException;
import com.sprint.mission.discodeit.exception.channel.PrivateChannelUpdateException;
import com.sprint.mission.discodeit.exception.user.UserNotFoundException;
import com.sprint.mission.discodeit.mapper.ChannelMapper;
import com.sprint.mission.discodeit.mapper.UserMapper;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.MessageRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import com.sprint.mission.discodeit.repository.UserRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BasicChannelServiceTest {

  @Mock
  private ChannelRepository channelRepository;

  @Mock
  private ReadStatusRepository readStatusRepository;

  @Mock
  private MessageRepository messageRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserMapper userMapper;

  @Mock
  private ChannelMapper channelMapper;

  @Mock
  private ApplicationEventPublisher applicationEventPublisher;

  private BasicChannelService channelService;

  private UUID channelId;
  private UUID userId;
  private Channel mockPublicChannel;
  private Channel mockPrivateChannel;
  private User mockUser;
  private UserDto mockUserDto;
  private ChannelDto mockChannelDto;

  @BeforeEach
  void setUp() {
    channelService = new BasicChannelService(channelRepository, readStatusRepository,
        messageRepository, userRepository, userMapper, channelMapper, applicationEventPublisher);

    channelId = UUID.randomUUID();
    userId = UUID.randomUUID();

    mockPublicChannel = new Channel("테스트", "테스트 채널입니다.", ChannelType.PUBLIC);
    ReflectionTestUtils.setField(mockPublicChannel, "id", channelId);

    mockPrivateChannel = new Channel("Private Channel", "", ChannelType.PRIVATE);
    ReflectionTestUtils.setField(mockPrivateChannel, "id", channelId);

    mockUser = new User("tester", "test@test.com", "password123", null);
    ReflectionTestUtils.setField(mockUser, "id", userId);

    mockUserDto = new UserDto(userId, "tester", "test@test.com", null, true, Role.USER);
    mockChannelDto = new ChannelDto(channelId, "테스트", ChannelType.PUBLIC, "테스트 채널입니다.",
        Instant.now(),
        Collections.emptyList());
  }

  @Nested
  @DisplayName("createPublicChannel()")
  class CreatePublicChannel {

    // --- 성공 ---
    @Test
    @DisplayName("Public 채널 생성")
    void createPublicChannel_success() {
      // given
      PublicChannelRequest request = new PublicChannelRequest("테스트", "테스트입니다.");

      given(channelRepository.save(any(Channel.class))).willReturn(mockPublicChannel);
      given(channelMapper.toDto(any(Channel.class), nullable(Instant.class), any())).willReturn(
          mockChannelDto);

      // when
      ChannelDto result = channelService.createPublicChannel(request);

      // then
      ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
      then(channelRepository).should().save(captor.capture());
      assertThat(captor.getValue().getName()).isEqualTo("테스트");
      assertThat(captor.getValue().getDescription()).isEqualTo("테스트입니다.");
      assertThat(captor.getValue().getType()).isEqualTo(ChannelType.PUBLIC);
      then(applicationEventPublisher).should().publishEvent(argThat((Object event) ->
          event instanceof ChannelCreatedEvent e && e.getData().equals(result)));
    }
  }

  @Nested
  @DisplayName("createPrivateChannel()")
  class CreatePrivateChannel {

    // --- 성공 ---
    @Test
    @DisplayName("참여자 목록을 포함한 private 채널 생성")
    void createPrivateChannel_success() {
      // given
      UUID participantId = UUID.randomUUID();
      PrivateChannelRequest request = new PrivateChannelRequest(List.of(participantId));

      ChannelDto privateChannelDto = new ChannelDto(channelId, "Private Channel",
          ChannelType.PRIVATE, "", Instant.now(), List.of(mockUserDto));

      given(channelRepository.save(any(Channel.class))).willReturn(mockPrivateChannel);
      given(userRepository.findById(participantId)).willReturn(Optional.of(mockUser));
      given(readStatusRepository.save(any(ReadStatus.class))).willReturn(mock(ReadStatus.class));
      given(userMapper.toDto(any(User.class), anyBoolean())).willReturn(mockUserDto);
      given(channelMapper.toDto(any(Channel.class), nullable(Instant.class), any())).willReturn(
          privateChannelDto);

      // when
      channelService.createPrivateChannel(request);

      // then
      ArgumentCaptor<ReadStatus> captor = ArgumentCaptor.forClass(ReadStatus.class);
      then(readStatusRepository).should().save(captor.capture());
      assertThat(captor.getValue().getUser()).isSameAs(mockUser);
      assertThat(captor.getValue().getChannel()).isSameAs(mockPrivateChannel);
      assertThat(captor.getValue().isNotificationEnabled()).isTrue();
      // 참여자별 채널 목록 캐시를 무효화하기 위한 이벤트
      then(applicationEventPublisher).should()
          .publishEvent(new PrivateChannelCreatedEvent(List.of(participantId)));
    }

    // --- 실패 ---
    @Test
    @DisplayName("참여자 중 존재하지 않는 userId가 있는 경우 UserNotFound 예외가 발생")
    void createPrivateChannel_userNotFound_throwsUserNotFoundException() {
      // given
      UUID unknownId = UUID.randomUUID();
      PrivateChannelRequest request = new PrivateChannelRequest(List.of(unknownId));

      given(channelRepository.save(any(Channel.class))).willReturn(mockPrivateChannel);
      given(userRepository.findById(unknownId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> channelService.createPrivateChannel(request))
          .isInstanceOf(UserNotFoundException.class);
      then(readStatusRepository).should(never()).save(any());
    }
  }

  @Nested
  @DisplayName("findAllByUserId()")
  class FindAllByUserId {

    // --- 성공 ---
    @Test
    @DisplayName("Public 채널은 모두 유저가 반환됨")
    void findAllByUserId_publicChannelIncluded() {
      // given
      given(channelRepository.findAll()).willReturn(List.of(mockPublicChannel));
      given(readStatusRepository.findAllByUserId(userId)).willReturn(Collections.emptyList());
      given(messageRepository.findLatestMessagesByChannelIds(any())).willReturn(
          Collections.emptyList());
      given(readStatusRepository.findAllByChannelIdIn(any())).willReturn(Collections.emptyList());
      given(channelMapper.toDto(any(Channel.class), nullable(Instant.class), any())).willReturn(
          mockChannelDto);

      // when
      List<ChannelDto> result = channelService.findAllByUserId(userId);

      // then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).type()).isEqualTo(ChannelType.PUBLIC);
    }

    @Test
    @DisplayName("참여 중인 Private 채널은 참여자 목록과 함께 반환됨")
    void findAllByUserId_privateChannel_participant() {
      // given
      ReadStatus readStatus = new ReadStatus(mockUser, mockPrivateChannel, Instant.now(), true);
      given(channelRepository.findAll()).willReturn(List.of(mockPrivateChannel));
      given(readStatusRepository.findAllByUserId(userId)).willReturn(List.of(readStatus));
      given(messageRepository.findLatestMessagesByChannelIds(any())).willReturn(
          Collections.emptyList());
      given(readStatusRepository.findAllByChannelIdIn(List.of(channelId)))
          .willReturn(List.of(readStatus));
      given(userMapper.toDto(mockUser, false)).willReturn(mockUserDto);
      givenChannelMapperBuildsDtoFromArguments();

      // when
      List<ChannelDto> result = channelService.findAllByUserId(userId);

      // then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).type()).isEqualTo(ChannelType.PRIVATE);
      assertThat(result.get(0).participants()).containsExactly(mockUserDto);
    }

    @Test
    @DisplayName("채널 목록은 마지막 메시지 시각(없으면 채널 생성 시각) 기준 내림차순으로 정렬됨")
    void findAllByUserId_sortedByLastMessageAtDesc() {
      // given
      Instant now = Instant.now();
      Channel oldChannelWithNewMessage = publicChannelCreatedAt(now.minusSeconds(300));
      Channel newChannelWithoutMessage = publicChannelCreatedAt(now.minusSeconds(100));
      Message latest = new Message(oldChannelWithNewMessage, mockUser, "latest");
      ReflectionTestUtils.setField(latest, "createdAt", now);

      given(channelRepository.findAll())
          .willReturn(List.of(newChannelWithoutMessage, oldChannelWithNewMessage));
      given(readStatusRepository.findAllByUserId(userId)).willReturn(Collections.emptyList());
      given(messageRepository.findLatestMessagesByChannelIds(any())).willReturn(List.of(latest));
      given(readStatusRepository.findAllByChannelIdIn(any())).willReturn(Collections.emptyList());
      givenChannelMapperBuildsDtoFromArguments();

      // when
      List<ChannelDto> result = channelService.findAllByUserId(userId);

      // then
      assertThat(result).extracting(ChannelDto::id)
          .containsExactly(oldChannelWithNewMessage.getId(), newChannelWithoutMessage.getId());
      assertThat(result.get(0).lastMessageAt()).isEqualTo(now);
      assertThat(result.get(1).lastMessageAt()).isEqualTo(now.minusSeconds(100));
    }

    private Channel publicChannelCreatedAt(Instant createdAt) {
      Channel channel = new Channel("channel", "", ChannelType.PUBLIC);
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
    @DisplayName("Private 채널은 참여하지 않으면 반환되지 않음")
    void findAllByUserId_privateChannel_notParticipant() {
      // given
      given(channelRepository.findAll()).willReturn(List.of(mockPrivateChannel));
      given(readStatusRepository.findAllByUserId(userId)).willReturn(Collections.emptyList());
      given(messageRepository.findLatestMessagesByChannelIds(any())).willReturn(
          Collections.emptyList());
      given(readStatusRepository.findAllByChannelIdIn(any())).willReturn(Collections.emptyList());

      // when
      List<ChannelDto> result = channelService.findAllByUserId(userId);

      // then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("deleteChannel()")
  class DeleteChannel {

    // --- 성공 ---
    @Test
    @DisplayName("채널이 정상적으로 삭제됨")
    void deleteChannel_success() {
      // given
      given(channelRepository.findById(channelId)).willReturn(Optional.of(mockPublicChannel));

      // when
      channelService.deleteChannel(channelId);

      // then
      then(channelRepository).should().delete(mockPublicChannel);
      then(applicationEventPublisher).should().publishEvent(any(ChannelDeletedEvent.class));
    }

    // --- 실패 ---
    @Test
    @DisplayName("존재하지 않는 channelId일 경우 ChannelNotFoundException이 발생")
    void deleteChannel_channelNotFound_throwsChannelNotFoundException() {
      // given
      given(channelRepository.findById(channelId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> channelService.deleteChannel(channelId))
          .isInstanceOf(ChannelNotFoundException.class);
      then(channelRepository).should(never()).delete(any());
    }
  }

  @Nested
  @DisplayName("updateChannel()")
  class UpdateChannel {

    // --- 성공 ---
    @Test
    @DisplayName("채널 이름과 설명 변경성공")
    void updateChannel_success() {
      // given
      ChannelUpdateRequest request = new ChannelUpdateRequest("new", "new");
      ChannelDto updateDto = new ChannelDto(channelId, "new", ChannelType.PUBLIC, "new",
          Instant.now(), Collections.emptyList());

      given(channelRepository.findById(channelId)).willReturn(Optional.of(mockPublicChannel));
      given(messageRepository.findTopByChannelIdOrderByCreatedAtDesc(channelId))
          .willReturn(Optional.empty());
      given(channelMapper.toDto(any(Channel.class), nullable(Instant.class), any()))
          .willReturn(updateDto);

      // when
      ChannelDto result = channelService.updateChannel(channelId, request);

      // then
      assertThat(mockPublicChannel.getName()).isEqualTo("new");
      assertThat(mockPublicChannel.getDescription()).isEqualTo("new");
      assertThat(result).isEqualTo(updateDto);
      then(applicationEventPublisher).should().publishEvent(any(ChannelUpdatedEvent.class));
    }

    // --- 실패 ---
    @Test
    @DisplayName("channelId가 존재하지 않을 경우 ChannelNotFoundException이 발생")
    void updateChannel_channelNotFound_throwsChannelNotFoundException() {
      // given
      given(channelRepository.findById(channelId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(
          () -> channelService.updateChannel(channelId, new ChannelUpdateRequest("test", "test")))
          .isInstanceOf(ChannelNotFoundException.class);
    }

    @Test
    @DisplayName("private 채널을 수정할 경우 PrivateChannelUpdateException 발생")
    void updateChannel_privateChannel_throwsPrivateChannelUpdateException() {
      // given
      given(channelRepository.findById(channelId)).willReturn(Optional.of(mockPrivateChannel));

      // when & then
      assertThatThrownBy(
          () -> channelService.updateChannel(channelId, new ChannelUpdateRequest("test", "test")))
          .isInstanceOf(PrivateChannelUpdateException.class);
      assertThat(mockPrivateChannel.getName()).isEqualTo("Private Channel");
    }
  }
}