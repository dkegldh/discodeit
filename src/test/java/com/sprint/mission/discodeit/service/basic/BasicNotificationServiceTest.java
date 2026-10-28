package com.sprint.mission.discodeit.service.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sprint.mission.discodeit.dto.notification.NotificationDto;
import com.sprint.mission.discodeit.entity.Notification;
import com.sprint.mission.discodeit.event.NotificationCreatedEvent;
import com.sprint.mission.discodeit.exception.notification.NotificationAccessDeniedException;
import com.sprint.mission.discodeit.exception.notification.NotificationNotFoundException;
import com.sprint.mission.discodeit.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BasicNotificationServiceTest {

  @Mock
  private NotificationRepository notificationRepository;
  @Mock
  private ApplicationEventPublisher eventPublisher;

  private BasicNotificationService notificationService;

  private UUID receiverId;
  private UUID notificationId;
  private Notification notification;

  @BeforeEach
  void setUp() {
    notificationService = new BasicNotificationService(notificationRepository, eventPublisher);

    receiverId = UUID.randomUUID();
    notificationId = UUID.randomUUID();
    notification = new Notification(receiverId, "title", "content");
    ReflectionTestUtils.setField(notification, "id", notificationId);
    ReflectionTestUtils.setField(notification, "createdAt", Instant.now());
  }

  @Nested
  @DisplayName("create()")
  class Create {

    @Test
    @DisplayName("알림을 저장하고 저장된 값으로 NotificationCreatedEvent를 발행")
    void create_savesAndPublishesEvent() {
      // given
      given(notificationRepository.save(any(Notification.class))).willReturn(notification);

      // when
      NotificationDto result = notificationService.create(receiverId, "title", "content");

      // then
      assertThat(result.id()).isEqualTo(notificationId);
      assertThat(result.receiverId()).isEqualTo(receiverId);
      assertThat(result.title()).isEqualTo("title");
      assertThat(result.content()).isEqualTo("content");
      then(eventPublisher).should().publishEvent(argThat((Object event) ->
          event instanceof NotificationCreatedEvent e && e.getData().equals(result)));
    }
  }

  @Nested
  @DisplayName("findAllByReceiverId()")
  class FindAllByReceiverId {

    @Test
    @DisplayName("수신자의 알림 목록을 DTO로 변환해 반환")
    void findAllByReceiverId_returnsDtos() {
      // given
      given(notificationRepository.findAllByReceiverId(receiverId))
          .willReturn(List.of(notification));

      // when
      List<NotificationDto> result = notificationService.findAllByReceiverId(receiverId);

      // then
      assertThat(result).singleElement()
          .satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(notificationId);
            assertThat(dto.receiverId()).isEqualTo(receiverId);
          });
    }
  }

  @Nested
  @DisplayName("delete()")
  class Delete {

    @Test
    @DisplayName("본인의 알림은 삭제됨")
    void delete_ownNotification_deleted() {
      // given
      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

      // when
      notificationService.delete(notificationId, receiverId);

      // then
      then(notificationRepository).should().delete(notification);
    }

    @Test
    @DisplayName("다른 사용자의 알림을 삭제하려 하면 NotificationAccessDeniedException이 발생하고 삭제되지 않음")
    void delete_otherUsersNotification_throwsAccessDenied() {
      // given
      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

      // when & then
      assertThatThrownBy(() -> notificationService.delete(notificationId, UUID.randomUUID()))
          .isInstanceOf(NotificationAccessDeniedException.class);
      then(notificationRepository).should(never()).delete(any());
    }

    @Test
    @DisplayName("존재하지 않는 알림이면 NotificationNotFoundException이 발생")
    void delete_notFound_throwsNotFound() {
      // given
      given(notificationRepository.findById(notificationId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> notificationService.delete(notificationId, receiverId))
          .isInstanceOf(NotificationNotFoundException.class);
    }
  }
}
