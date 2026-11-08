package com.sprint.mission.discodeit.event.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprint.mission.discodeit.dto.channel.ChannelDto;
import com.sprint.mission.discodeit.dto.notification.NotificationDto;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.service.SseService;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseKafkaFanoutListener {

  private final ObjectMapper objectMapper;
  private final SseService sseService;

  @KafkaListener(topics = "discodeit.NotificationCreatedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onNotificationCreated(String kafkaEvent) {
    readAndHandle(kafkaEvent, NotificationDto.class,
        dto -> sseService.send(Set.of(dto.receiverId()), "notifications.created", dto));
  }

  @KafkaListener(topics = "discodeit.ChannelCreatedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onChannelCreated(String kafkaEvent) {
    readAndHandle(kafkaEvent, ChannelDto.class,
        dto -> sseService.broadcast("channels.created", dto));
  }

  @KafkaListener(topics = "discodeit.ChannelUpdatedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onChannelUpdated(String kafkaEvent) {
    readAndHandle(kafkaEvent, ChannelDto.class,
        dto -> sseService.broadcast("channels.updated", dto));
  }

  @KafkaListener(topics = "discodeit.ChannelDeletedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onChannelDeleted(String kafkaEvent) {
    readAndHandle(kafkaEvent, ChannelDto.class,
        dto -> sseService.broadcast("channels.deleted", dto));
  }

  @KafkaListener(topics = "discodeit.UserCreatedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onUserCreated(String kafkaEvent) {
    readAndHandle(kafkaEvent, UserDto.class,
        dto -> sseService.broadcast("users.created", dto));
  }

  @KafkaListener(topics = "discodeit.UserUpdatedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onUserUpdated(String kafkaEvent) {
    readAndHandle(kafkaEvent, UserDto.class,
        dto -> sseService.broadcast("users.updated", dto));
  }

  @KafkaListener(topics = "discodeit.UserDeletedEvent",
      groupId = "discodeit-sse-fanout-${random.uuid}")
  public void onUserDeleted(String kafkaEvent) {
    readAndHandle(kafkaEvent, UserDto.class,
        dto -> sseService.broadcast("users.deleted", dto));
  }

  /**
   * Kafka 메시지는 KafkaProduceRequiredEventListener가 보낸 이벤트 전체({"data": ..., "createdAt": ...})이므로
   * 실제 내용인 data를 꺼내 읽는다. 형식이 맞지 않는 메시지는 재시도해도 같으므로 로그만 남기고 건너뛴다.
   */
  private <T> void readAndHandle(String json, Class<T> type, Consumer<T> handler) {
    try {
      JsonNode data = objectMapper.readTree(json).get("data");
      if (data == null || data.isNull()) {
        log.error("SSE Kafka 이벤트에 data가 없음 - type: {}", type.getSimpleName());
        return;
      }
      handler.accept(objectMapper.treeToValue(data, type));
    } catch (JsonProcessingException e) {
      log.error("SSE Kafka 이벤트 역직렬화 실패 - type: {}", type.getSimpleName(), e);
    }
  }
}