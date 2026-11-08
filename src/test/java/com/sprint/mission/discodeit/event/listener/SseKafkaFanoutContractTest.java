package com.sprint.mission.discodeit.event.listener;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprint.mission.discodeit.dto.channel.ChannelDto;
import com.sprint.mission.discodeit.dto.notification.NotificationDto;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.event.ChannelCreatedEvent;
import com.sprint.mission.discodeit.event.ChannelDeletedEvent;
import com.sprint.mission.discodeit.event.ChannelUpdatedEvent;
import com.sprint.mission.discodeit.event.NotificationCreatedEvent;
import com.sprint.mission.discodeit.event.UserCreatedEvent;
import com.sprint.mission.discodeit.event.UserDeletedEvent;
import com.sprint.mission.discodeit.event.UserUpdatedEvent;
import com.sprint.mission.discodeit.service.SseService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka로 이벤트를 보내는 쪽({@link KafkaProduceRequiredEventListener})이 만든 메시지를 받는 쪽
 * ({@link SseKafkaFanoutListener})이 올바르게 읽는지 검증한다. 통합 테스트에서는 KafkaTemplate을 Mock으로 대체해
 * 두 클래스 사이의 메시지 형식이 검증되지 않았다.
 */
class SseKafkaFanoutContractTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
  private static final UserDto USER = new UserDto(UUID.randomUUID(), "tester", "t@test.com",
      null, false, Role.USER);
  private static final ChannelDto CHANNEL = new ChannelDto(UUID.randomUUID(), "channel",
      ChannelType.PUBLIC, "description", NOW, List.of());

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
  private final SseService sseService = mock(SseService.class);

  private KafkaProduceRequiredEventListener producer;
  private SseKafkaFanoutListener consumer;

  @BeforeEach
  void setUp() {
    // 애플리케이션과 같은 설정(JavaTimeModule 등)의 ObjectMapper
    ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    producer = new KafkaProduceRequiredEventListener(kafkaTemplate, objectMapper);
    consumer = new SseKafkaFanoutListener(objectMapper, sseService);
  }

  private String sentPayload(String topic) {
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq(topic), payload.capture());
    return payload.getValue();
  }

  @Test
  @DisplayName("알림 생성 이벤트는 수신자에게만 원래 알림 내용 그대로 전달됨")
  void notificationCreated_sentToReceiver() {
    NotificationDto notification = new NotificationDto(UUID.randomUUID(), NOW, USER.id(),
        "title", "content");
    producer.on(new NotificationCreatedEvent(notification, NOW));

    consumer.onNotificationCreated(sentPayload("discodeit.NotificationCreatedEvent"));

    then(sseService).should()
        .send(Set.of(USER.id()), "notifications.created", notification);
  }

  static Stream<Arguments> broadcastEvents() {
    return Stream.of(
        Arguments.of("discodeit.ChannelCreatedEvent", "channels.created", CHANNEL,
            (BiConsumer<KafkaProduceRequiredEventListener, Object>) (p, d) ->
                p.on(new ChannelCreatedEvent((ChannelDto) d, NOW)),
            (BiConsumer<SseKafkaFanoutListener, String>) SseKafkaFanoutListener::onChannelCreated),
        Arguments.of("discodeit.ChannelUpdatedEvent", "channels.updated", CHANNEL,
            (BiConsumer<KafkaProduceRequiredEventListener, Object>) (p, d) ->
                p.on(new ChannelUpdatedEvent((ChannelDto) d, NOW)),
            (BiConsumer<SseKafkaFanoutListener, String>) SseKafkaFanoutListener::onChannelUpdated),
        Arguments.of("discodeit.ChannelDeletedEvent", "channels.deleted", CHANNEL,
            (BiConsumer<KafkaProduceRequiredEventListener, Object>) (p, d) ->
                p.on(new ChannelDeletedEvent((ChannelDto) d, NOW)),
            (BiConsumer<SseKafkaFanoutListener, String>) SseKafkaFanoutListener::onChannelDeleted),
        Arguments.of("discodeit.UserCreatedEvent", "users.created", USER,
            (BiConsumer<KafkaProduceRequiredEventListener, Object>) (p, d) ->
                p.on(new UserCreatedEvent((UserDto) d, NOW)),
            (BiConsumer<SseKafkaFanoutListener, String>) SseKafkaFanoutListener::onUserCreated),
        Arguments.of("discodeit.UserUpdatedEvent", "users.updated", USER,
            (BiConsumer<KafkaProduceRequiredEventListener, Object>) (p, d) ->
                p.on(new UserUpdatedEvent((UserDto) d, NOW)),
            (BiConsumer<SseKafkaFanoutListener, String>) SseKafkaFanoutListener::onUserUpdated),
        Arguments.of("discodeit.UserDeletedEvent", "users.deleted", USER,
            (BiConsumer<KafkaProduceRequiredEventListener, Object>) (p, d) ->
                p.on(new UserDeletedEvent((UserDto) d, NOW)),
            (BiConsumer<SseKafkaFanoutListener, String>) SseKafkaFanoutListener::onUserDeleted)
    );
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("broadcastEvents")
  @DisplayName("채널/사용자 이벤트는 원래 데이터 그대로 모든 연결에 방송됨")
  void broadcastEvent_sentWithOriginalData(String topic, String sseEventName, Object data,
      BiConsumer<KafkaProduceRequiredEventListener, Object> publish,
      BiConsumer<SseKafkaFanoutListener, String> consume) {
    publish.accept(producer, data);

    consume.accept(consumer, sentPayload(topic));

    then(sseService).should().broadcast(sseEventName, data);
  }

  @Test
  @DisplayName("형식이 맞지 않는 메시지는 예외 없이 건너뜀 (재시도로 같은 실패를 반복하지 않음)")
  void malformedPayload_skippedWithoutException() {
    Consumer<String> consume = consumer::onNotificationCreated;

    consume.accept("{\"unexpected\":true}");
    consume.accept("not-json");

    then(sseService).should(org.mockito.Mockito.never())
        .send(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any());
  }
}
