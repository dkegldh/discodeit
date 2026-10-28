package com.sprint.mission.discodeit.service.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sprint.mission.discodeit.dto.readstatus.ReadStatusCreateRequest;
import com.sprint.mission.discodeit.dto.readstatus.ReadStatusUpdateRequest;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.exception.channel.ChannelNotFoundException;
import com.sprint.mission.discodeit.exception.message.ReadStatusAlreadyExistsException;
import com.sprint.mission.discodeit.exception.message.ReadStatusNotFoundException;
import com.sprint.mission.discodeit.mapper.ReadStatusMapper;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import com.sprint.mission.discodeit.repository.UserRepository;
import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class BasicReadStatusServiceTest {

  @Mock
  private ReadStatusRepository readStatusRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private ChannelRepository channelRepository;
  @Mock
  private ReadStatusMapper readStatusMapper;

  private BasicReadStatusService readStatusService;

  private UUID userId;
  private UUID channelId;
  private User user;

  @BeforeEach
  void setUp() {
    readStatusService = new BasicReadStatusService(readStatusRepository, userRepository,
        channelRepository, readStatusMapper);

    userId = UUID.randomUUID();
    channelId = UUID.randomUUID();
    user = new User("tester", "test@test.com", "password123", null);
  }

  @Nested
  @DisplayName("createReadStatus()")
  class CreateReadStatus {

    private ReadStatus createWith(ChannelType type) {
      Channel channel = new Channel("channel", "", type);
      given(userRepository.findById(userId)).willReturn(Optional.of(user));
      given(channelRepository.findById(channelId)).willReturn(Optional.of(channel));
      given(readStatusRepository.findByUserIdAndChannelId(userId, channelId))
          .willReturn(Optional.empty());

      readStatusService.createReadStatus(new ReadStatusCreateRequest(userId, channelId));

      ArgumentCaptor<ReadStatus> captor = ArgumentCaptor.forClass(ReadStatus.class);
      then(readStatusRepository).should().save(captor.capture());
      return captor.getValue();
    }

    @Test
    @DisplayName("Private 채널은 알림이 켜진 상태로 생성됨")
    void createReadStatus_privateChannel_notificationEnabled() {
      assertThat(createWith(ChannelType.PRIVATE).isNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("Public 채널은 알림이 꺼진 상태로 생성됨")
    void createReadStatus_publicChannel_notificationDisabled() {
      assertThat(createWith(ChannelType.PUBLIC).isNotificationEnabled()).isFalse();
    }

    @Test
    @DisplayName("이미 같은 사용자/채널의 ReadStatus가 있으면 ReadStatusAlreadyExistsException이 발생")
    void createReadStatus_duplicate_throwsAlreadyExists() {
      // given
      Channel channel = new Channel("channel", "", ChannelType.PUBLIC);
      given(userRepository.findById(userId)).willReturn(Optional.of(user));
      given(channelRepository.findById(channelId)).willReturn(Optional.of(channel));
      given(readStatusRepository.findByUserIdAndChannelId(userId, channelId))
          .willReturn(Optional.of(new ReadStatus(user, channel, Instant.now(), false)));

      // when & then
      assertThatThrownBy(() -> readStatusService.createReadStatus(
          new ReadStatusCreateRequest(userId, channelId)))
          .isInstanceOf(ReadStatusAlreadyExistsException.class);
      then(readStatusRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 채널이면 ChannelNotFoundException이 발생")
    void createReadStatus_channelNotFound_throwsChannelNotFound() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(user));
      given(channelRepository.findById(channelId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> readStatusService.createReadStatus(
          new ReadStatusCreateRequest(userId, channelId)))
          .isInstanceOf(ChannelNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("updateStatus()")
  class UpdateStatus {

    private UUID readStatusId;
    private ReadStatus readStatus;
    private Instant lastReadAt;

    @BeforeEach
    void setUpReadStatus() {
      readStatusId = UUID.randomUUID();
      lastReadAt = Instant.now().minusSeconds(60);
      readStatus = new ReadStatus(user, new Channel("channel", "", ChannelType.PRIVATE),
          lastReadAt, true);
    }

    @Test
    @DisplayName("요청에 포함된 필드만 변경됨")
    void updateStatus_onlyGivenFieldsChanged() {
      // given
      given(readStatusRepository.findById(readStatusId)).willReturn(Optional.of(readStatus));

      // when
      readStatusService.updateStatus(readStatusId, new ReadStatusUpdateRequest(null, false));

      // then
      assertThat(readStatus.isNotificationEnabled()).isFalse();
      assertThat(readStatus.getLastReadAt()).isEqualTo(lastReadAt);
    }

    @Test
    @DisplayName("마지막으로 읽은 시각이 변경됨")
    void updateStatus_lastReadAtChanged() {
      // given
      Instant newLastReadAt = Instant.now();
      given(readStatusRepository.findById(readStatusId)).willReturn(Optional.of(readStatus));

      // when
      readStatusService.updateStatus(readStatusId,
          new ReadStatusUpdateRequest(newLastReadAt, null));

      // then
      assertThat(readStatus.getLastReadAt()).isEqualTo(newLastReadAt);
      assertThat(readStatus.isNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 ReadStatus면 ReadStatusNotFoundException이 발생")
    void updateStatus_notFound_throwsNotFound() {
      // given
      given(readStatusRepository.findById(readStatusId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> readStatusService.updateStatus(readStatusId,
          new ReadStatusUpdateRequest(Instant.now(), null)))
          .isInstanceOf(ReadStatusNotFoundException.class);
    }
  }
}
