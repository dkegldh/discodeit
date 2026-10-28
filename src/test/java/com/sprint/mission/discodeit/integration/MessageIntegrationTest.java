package com.sprint.mission.discodeit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprint.mission.discodeit.dto.channel.PrivateChannelRequest;
import com.sprint.mission.discodeit.dto.message.CreateMessageRequest;
import com.sprint.mission.discodeit.dto.message.MessageUpdateRequest;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Message;
import com.sprint.mission.discodeit.entity.Notification;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.MessageRepository;
import com.sprint.mission.discodeit.repository.NotificationRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class MessageIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private MessageRepository messageRepository;

  @Autowired
  private ChannelRepository channelRepository;

  @Autowired
  private NotificationRepository notificationRepository;

  private User savedUser;
  private Channel savedChannel;
  private Message savedMessage;
  private String accessToken;

  @BeforeEach
  void setUp() throws Exception {
    savedUser = saveUser("testUser", Role.USER);

    savedChannel = channelRepository.save(
        new Channel("public", "public Channel", ChannelType.PUBLIC)
    );

    savedMessage = messageRepository.save(
        new Message(savedChannel, savedUser, "기존 메시지")
    );

    accessToken = login("testUser");
  }

  private MockMultipartFile messagePart(UUID channelId, String content) throws Exception {
    return new MockMultipartFile("messageCreateRequest", "", MediaType.APPLICATION_JSON_VALUE,
        objectMapper.writeValueAsBytes(new CreateMessageRequest(channelId, content)));
  }

  // --- POST ---
  @Test
  @DisplayName("유효한 요청으로 메시지 생성 시 201과 생성된 메시지 정보 반환")
  void createMessage_returns201() throws Exception {
    mockMvc.perform(multipart("/api/messages")
            .file(messagePart(savedChannel.getId(), "test"))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.content").value("test"))
        .andExpect(jsonPath("$.channelId").value(savedChannel.getId().toString()))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  @DisplayName("요청 바디에 다른 사용자의 authorId를 넣어도 작성자는 로그인한 사용자로 저장됨")
  void createMessage_spoofedAuthorId_authorIsAuthenticatedUser() throws Exception {
    User victim = saveUser("victim", Role.USER);
    // 기존 클라이언트는 authorId를 함께 보내므로, 필드가 있어도 무시되는지 원시 JSON으로 검증
    MockMultipartFile spoofedPart = new MockMultipartFile("messageCreateRequest", "",
        MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(Map.of(
        "channelId", savedChannel.getId(),
        "authorId", victim.getId(),
        "content", "사칭 메시지")));

    mockMvc.perform(multipart("/api/messages").file(spoofedPart).with(auth(accessToken)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.author.id").value(savedUser.getId().toString()));

    assertThat(messageRepository.findAll())
        .filteredOn(message -> message.getContent().equals("사칭 메시지"))
        .singleElement()
        .satisfies(message ->
            assertThat(message.getAuthor().getId()).isEqualTo(savedUser.getId()));
  }

  @Test
  @DisplayName("메시지 생성이 커밋되면 Kafka로 MessageCreatedEvent가 발행됨")
  void createMessage_publishesKafkaEventAfterCommit() throws Exception {
    mockMvc.perform(multipart("/api/messages")
            .file(messagePart(savedChannel.getId(), "test"))
            .with(auth(accessToken)))
        .andExpect(status().isCreated());

    verify(kafkaTemplate, timeout(5000))
        .send(eq("discodeit.MessageCreatedEvent"), anyString());
  }

  @Test
  @DisplayName("Private 채널에 메시지를 작성하면 커밋 이후 작성자를 제외한 참여자에게만 알림이 생성됨")
  void createMessage_privateChannel_notifiesOtherParticipants() throws Exception {
    // given
    User receiver = saveUser("receiver", Role.USER);
    String response = mockMvc.perform(post("/api/channels/private")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new PrivateChannelRequest(List.of(savedUser.getId(), receiver.getId()))))
            .with(auth(accessToken)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    UUID privateChannelId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

    // when
    mockMvc.perform(multipart("/api/messages")
            .file(messagePart(privateChannelId, "안녕하세요"))
            .with(auth(accessToken)))
        .andExpect(status().isCreated());

    // then
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(notificationRepository.findAllByReceiverId(receiver.getId()))
            .singleElement()
            .satisfies(notification -> {
              assertThat(notification.getTitle()).isEqualTo("testUser (#Private Channel)");
              assertThat(notification.getContent()).isEqualTo("안녕하세요");
            }));
    assertThat(notificationRepository.findAllByReceiverId(savedUser.getId()))
        .extracting(Notification::getContent)
        .doesNotContain("안녕하세요");
  }

  @Test
  @DisplayName("존재하지 않는 채널 ID로 메시지 생성 시 404를 반환")
  void createMessage_channelNotFound_returns404() throws Exception {
    mockMvc.perform(multipart("/api/messages")
            .file(messagePart(UUID.randomUUID(), "test"))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
  }

  // --- GET ---
  @Test
  @DisplayName("channelId로 메시지 조회 시 200과 메시지 목록을 반환")
  void readMessage_returns200WithMessageList() throws Exception {
    mockMvc.perform(get("/api/messages").param("channelId", savedChannel.getId().toString())
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].content").value("기존 메시지"))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  @DisplayName("메시지가 없는 채널 조회 시 200과 빈 content를 반환")
  void readMessage_emptyChannel_returnsEmptyContent() throws Exception {
    Channel emptyChannel = channelRepository.save(
        new Channel("empty", "empty Channel", ChannelType.PUBLIC)
    );

    mockMvc.perform(get("/api/messages").param("channelId", emptyChannel.getId().toString())
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  // --- DELETE ---
  @Test
  @DisplayName("메시지 삭제 시 204를 반환하고 DB에서 삭제됨")
  void deleteMessage_returns204AndDeletedFromDb() throws Exception {
    mockMvc.perform(delete("/api/messages/{messageId}", savedMessage.getId())
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNoContent());

    assertThat(messageRepository.findById(savedMessage.getId())).isEmpty();
  }

  @Test
  @DisplayName("존재하지 않는 메시지 삭제 시 404를 반환")
  void deleteMessage_notFound_returns404() throws Exception {
    mockMvc.perform(delete("/api/messages/{messageId}", UUID.randomUUID())
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
  }

  // --- PATCH ---
  @Test
  @DisplayName("유효한 요청으로 메시지 수정 시 200과 수정된 메시지 정보를 반환")
  void updateMessage_returns200() throws Exception {
    MessageUpdateRequest request = new MessageUpdateRequest("수정된 메시지");

    mockMvc.perform(patch("/api/messages/{messageId}", savedMessage.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("수정된 메시지"))
        .andExpect(jsonPath("$.id").value(savedMessage.getId().toString()));
  }

  @Test
  @DisplayName("존재하지 않는 메시지 수정 시 404를 반환")
  void updateMessage_notFound_returns404() throws Exception {
    MessageUpdateRequest request = new MessageUpdateRequest("수정된 내용");

    mockMvc.perform(patch("/api/messages/{messageId}", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
  }

  @Nested
  @DisplayName("작성자가 아닌 사용자")
  class NotAuthor {

    private String otherToken;

    @BeforeEach
    void loginAsOtherUser() throws Exception {
      saveUser("other", Role.USER);
      otherToken = login("other");
    }

    @Test
    @DisplayName("다른 사람의 메시지를 수정하면 403을 반환하고 수정되지 않음")
    void updateMessage_returns403() throws Exception {
      MessageUpdateRequest request = new MessageUpdateRequest("hacked");

      mockMvc.perform(patch("/api/messages/{messageId}", savedMessage.getId())
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request))
              .with(auth(otherToken)))
          .andExpect(status().isForbidden());

      assertThat(messageRepository.findById(savedMessage.getId()))
          .hasValueSatisfying(message -> assertThat(message.getContent()).isEqualTo("기존 메시지"));
    }

    @Test
    @DisplayName("다른 사람의 메시지를 삭제하면 403을 반환하고 삭제되지 않음")
    void deleteMessage_returns403() throws Exception {
      mockMvc.perform(delete("/api/messages/{messageId}", savedMessage.getId())
              .with(auth(otherToken)))
          .andExpect(status().isForbidden());

      assertThat(messageRepository.findById(savedMessage.getId())).isPresent();
    }
  }
}
