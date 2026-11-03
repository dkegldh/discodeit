package com.sprint.mission.discodeit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprint.mission.discodeit.dto.message.CreateMessageRequest;
import com.sprint.mission.discodeit.dto.readstatus.ReadStatusCreateRequest;
import com.sprint.mission.discodeit.dto.readstatus.ReadStatusUpdateRequest;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Message;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.MessageRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Private 채널은 참여자만 메시지를 읽고 쓸 수 있고, 읽음 상태는 본인 것만 만들고 바꿀 수 있는지 검증한다.
 */
class PrivateChannelAccessIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ChannelRepository channelRepository;

  @Autowired
  private ReadStatusRepository readStatusRepository;

  @Autowired
  private MessageRepository messageRepository;

  private User member;
  private Channel privateChannel;
  private Channel publicChannel;
  private ReadStatus memberReadStatus;
  private Instant memberLastReadAt;

  @BeforeEach
  void setUp() {
    member = saveUser("member", Role.USER);
    User otherMember = saveUser("otherMember", Role.USER);

    privateChannel = channelRepository.save(new Channel("", "", ChannelType.PRIVATE));
    publicChannel = channelRepository.save(new Channel("public", "", ChannelType.PUBLIC));
    memberLastReadAt = Instant.parse("2026-01-01T00:00:00Z");
    memberReadStatus = readStatusRepository.save(
        new ReadStatus(member, privateChannel, memberLastReadAt, true));
    readStatusRepository.save(new ReadStatus(otherMember, privateChannel, Instant.now(), true));

    messageRepository.save(new Message(privateChannel, member, "비밀 메시지"));
  }

  private ResultActions readMessages(Channel channel, String token) throws Exception {
    return mockMvc.perform(get("/api/messages").param("channelId", channel.getId().toString())
        .with(auth(token)));
  }

  private ResultActions postMessage(Channel channel, String content, String token)
      throws Exception {
    return mockMvc.perform(multipart("/api/messages")
        .file(new MockMultipartFile("messageCreateRequest", "", MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(new CreateMessageRequest(channel.getId(), content))))
        .with(auth(token)));
  }

  private ResultActions createReadStatus(UUID userId, Channel channel, String token)
      throws Exception {
    return mockMvc.perform(post("/api/readStatuses")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(
            new ReadStatusCreateRequest(userId, channel.getId())))
        .with(auth(token)));
  }

  @Nested
  @DisplayName("Private 채널에 참여하지 않은 사용자")
  class Outsider {

    private User outsider;
    private String token;

    @BeforeEach
    void loginAsOutsider() throws Exception {
      outsider = saveUser("outsider", Role.USER);
      token = login("outsider");
    }

    @Test
    @DisplayName("메시지를 조회하면 403을 반환")
    void readMessages_returns403() throws Exception {
      readMessages(privateChannel, token).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("메시지를 작성하면 403을 반환하고 저장되지 않음")
    void postMessage_returns403() throws Exception {
      postMessage(privateChannel, "끼어들기", token).andExpect(status().isForbidden());

      assertThat(messageRepository.findAll()).extracting(Message::getContent)
          .doesNotContain("끼어들기");
    }

    @Test
    @DisplayName("자신을 참여자로 추가하면 403을 반환하고 참여자가 되지 않음")
    void joinByCreatingReadStatus_returns403() throws Exception {
      createReadStatus(outsider.getId(), privateChannel, token)
          .andExpect(status().isForbidden());

      assertThat(readStatusRepository.findByUserIdAndChannelId(outsider.getId(),
          privateChannel.getId())).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 읽음 상태를 수정하면 403을 반환하고 바뀌지 않음")
    void updateOthersReadStatus_returns403() throws Exception {
      mockMvc.perform(patch("/api/readStatuses/{id}", memberReadStatus.getId())
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  new ReadStatusUpdateRequest(Instant.now(), false)))
              .with(auth(token)))
          .andExpect(status().isForbidden());

      assertThat(readStatusRepository.findById(memberReadStatus.getId()))
          .hasValueSatisfying(rs -> {
            assertThat(rs.getLastReadAt()).isEqualTo(memberLastReadAt);
            assertThat(rs.isNotificationEnabled()).isTrue();
          });
    }

    @Test
    @DisplayName("Public 채널이라도 다른 사용자 명의로 읽음 상태를 만들면 403을 반환")
    void createReadStatusForOtherUser_returns403() throws Exception {
      createReadStatus(member.getId(), publicChannel, token).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Public 채널은 메시지를 읽고 쓸 수 있고, 자신의 읽음 상태를 만들 수 있음")
    void publicChannel_allowed() throws Exception {
      postMessage(publicChannel, "안녕하세요", token).andExpect(status().isCreated());
      readMessages(publicChannel, token).andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].content").value("안녕하세요"));
      createReadStatus(outsider.getId(), publicChannel, token).andExpect(status().isCreated());
    }
  }

  @Nested
  @DisplayName("Private 채널 참여자")
  class Member {

    private String token;

    @BeforeEach
    void loginAsMember() throws Exception {
      token = login("member");
    }

    @Test
    @DisplayName("메시지를 읽고 쓸 수 있음")
    void readAndPostMessages_allowed() throws Exception {
      readMessages(privateChannel, token).andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].content").value("비밀 메시지"));
      postMessage(privateChannel, "답장", token).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("자신의 읽음 상태를 수정할 수 있음")
    void updateOwnReadStatus_allowed() throws Exception {
      Instant newLastReadAt = Instant.parse("2026-02-01T00:00:00Z");

      mockMvc.perform(patch("/api/readStatuses/{id}", memberReadStatus.getId())
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  new ReadStatusUpdateRequest(newLastReadAt, null)))
              .with(auth(token)))
          .andExpect(status().isOk());

      assertThat(readStatusRepository.findById(memberReadStatus.getId()))
          .hasValueSatisfying(rs -> assertThat(rs.getLastReadAt()).isEqualTo(newLastReadAt));
    }
  }
}
