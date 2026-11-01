package com.sprint.mission.discodeit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sprint.mission.discodeit.dto.channel.PrivateChannelRequest;
import com.sprint.mission.discodeit.dto.message.CreateMessageRequest;
import com.sprint.mission.discodeit.dto.readstatus.ReadStatusCreateRequest;
import com.sprint.mission.discodeit.dto.user.UserCreateRequest;
import com.sprint.mission.discodeit.dto.user.UserUpdateRequest;
import com.sprint.mission.discodeit.entity.BinaryContentStatus;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.BinaryContentRepository;
import com.sprint.mission.discodeit.service.BinaryContentService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 조회 결과를 캐시한 뒤 원본 데이터가 바뀌었을 때, 다음 조회에 변경이 반영되는지 검증한다.
 */
class CacheConsistencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private BinaryContentRepository binaryContentRepository;

  @Autowired
  private BinaryContentService binaryContentService;

  private User me;
  private User friend;
  private String myToken;
  private UUID privateChannelId;

  @BeforeEach
  void setUp() throws Exception {
    me = saveUser("me", Role.USER);
    friend = saveUser("friend", Role.USER);
    myToken = login("me");

    String response = mockMvc.perform(post("/api/channels/private")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new PrivateChannelRequest(List.of(me.getId(), friend.getId()))))
            .with(auth(myToken)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    privateChannelId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
  }

  private JsonNode myChannels() throws Exception {
    String body = mockMvc.perform(get("/api/channels").param("userId", me.getId().toString())
            .with(auth(myToken)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body);
  }

  private JsonNode users() throws Exception {
    String body = mockMvc.perform(get("/api/users").with(auth(myToken)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body);
  }

  @Test
  @DisplayName("채널 목록을 조회한 뒤 새 메시지가 오면, 다시 조회했을 때 lastMessageAt이 새 메시지 시각으로 바뀜")
  void channelList_reflectsNewMessage() throws Exception {
    myChannels();

    String messageBody = mockMvc.perform(multipart("/api/messages")
            .file(new MockMultipartFile("messageCreateRequest", "",
                MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(
                new CreateMessageRequest(privateChannelId, "새 메시지"))))
            .with(auth(myToken)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String messageCreatedAt = objectMapper.readTree(messageBody).get("createdAt").asText();
    awaitAsyncEventListeners();

    assertThat(myChannels().get(0).get("lastMessageAt").asText()).isEqualTo(messageCreatedAt);
  }

  @Test
  @DisplayName("채널 목록을 조회한 뒤 참여자가 이름을 바꾸면, 다시 조회했을 때 바뀐 이름이 보임")
  void channelList_reflectsParticipantRename() throws Exception {
    myChannels();

    String friendToken = login("friend");
    mockMvc.perform(multipart("/api/users/{id}", friend.getId())
            .file(new MockMultipartFile("userUpdateRequest", "", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(new UserUpdateRequest("renamed", null, null))))
            .with(auth(friendToken))
            .with(req -> {
              req.setMethod("PATCH");
              return req;
            }))
        .andExpect(status().isOk());
    awaitAsyncEventListeners();

    assertThat(myChannels().get(0).get("participants").findValuesAsText("username"))
        .containsExactlyInAnyOrder("me", "renamed");
  }

  @Test
  @DisplayName("채널 목록을 조회한 뒤 채널에 참여자가 추가되면, 다시 조회했을 때 새 참여자가 보임")
  void channelList_reflectsNewParticipant() throws Exception {
    myChannels();
    User newcomer = saveUser("newcomer", Role.USER);

    mockMvc.perform(post("/api/readStatuses")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new ReadStatusCreateRequest(newcomer.getId(), privateChannelId)))
            .with(auth(myToken)))
        .andExpect(status().isCreated());

    assertThat(myChannels().get(0).get("participants").findValuesAsText("username"))
        .containsExactlyInAnyOrder("me", "friend", "newcomer");
  }

  @Test
  @DisplayName("사용자 목록을 조회한 뒤 프로필 이미지 상태가 바뀌면, 다시 조회했을 때 바뀐 상태가 보임")
  void userList_reflectsProfileStatusChange() throws Exception {
    String body = mockMvc.perform(multipart("/api/users")
            .file(new MockMultipartFile("userCreateRequest", "", MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(
                    new UserCreateRequest("withProfile", "p@test.com", "password123"))))
            .file(new MockMultipartFile("profile", "p.png", "image/png", "img".getBytes()))
            .with(csrf()))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    UUID profileId = UUID.fromString(
        objectMapper.readTree(body).get("profile").get("id").asText());
    await().atMost(Duration.ofSeconds(5)).until(() ->
        binaryContentRepository.findById(profileId).orElseThrow().getStatus()
            == BinaryContentStatus.SUCCESS);
    users();

    // 업로드 결과가 나중에 바뀌는 경우(예: 저장 실패 처리)
    binaryContentService.updateStatus(profileId, BinaryContentStatus.FAIL);

    assertThat(users().findValues("profile").stream()
        .filter(profile -> !profile.isNull())
        .map(profile -> profile.get("status").asText()))
        .containsExactly("FAIL");
  }
}
