package com.sprint.mission.discodeit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprint.mission.discodeit.dto.channel.ChannelUpdateRequest;
import com.sprint.mission.discodeit.dto.channel.PrivateChannelRequest;
import com.sprint.mission.discodeit.dto.channel.PublicChannelRequest;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ChannelIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ChannelRepository channelRepository;

  private Channel savedPublicChannel;
  private Channel savedPrivateChannel;
  private User savedUser;
  private String accessToken;

  @BeforeEach
  void setUp() throws Exception {
    savedUser = saveUser("testUser", Role.CHANNEL_MANAGER);

    savedPublicChannel = channelRepository.save(
        new Channel("public", "test channel", ChannelType.PUBLIC)
    );
    savedPrivateChannel = channelRepository.save(
        new Channel("", "", ChannelType.PRIVATE)
    );

    accessToken = login("testUser");
  }

  // --- POST(PUBLIC) ---
  @Test
  @DisplayName("유효한 요청으로 Public 채널을 생성 시 201과 채널 정보를 반환")
  void createPublicChannel_returns201() throws Exception {
    PublicChannelRequest request = new PublicChannelRequest("test", "des");

    mockMvc.perform(post("/api/channels/public")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("test"))
        .andExpect(jsonPath("$.type").value("PUBLIC"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  @DisplayName("채널명이 한글자일 경우 Public 채널 생성 시 400을 반환")
  void createPublicChannel_shortName_returns400() throws Exception {
    PublicChannelRequest request = new PublicChannelRequest("a", "des");

    mockMvc.perform(post("/api/channels/public")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // --- POST(PRIVATE) ---
  @Test
  @DisplayName("유효한 요청으로 Private 채널을 생성 시 201과 채널 정보를 반환")
  void createPrivateChannel_returns201() throws Exception {
    PrivateChannelRequest request = new PrivateChannelRequest(List.of(savedUser.getId()));

    mockMvc.perform(post("/api/channels/private")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("PRIVATE"))
        .andExpect(jsonPath("$.participants.length()").value(1));
  }

  @Test
  @DisplayName("존재하지 않는 유저 ID로 Private채널 생성 시 404를 반환")
  void createPrivateChannel_userNotFound_returns404() throws Exception {
    PrivateChannelRequest request = new PrivateChannelRequest(List.of(UUID.randomUUID()));

    mockMvc.perform(post("/api/channels/private")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  // --- DELETE ---
  @Test
  @DisplayName("존재하는 채널 삭제 시 204를 반환")
  void deleteChannel_returns204() throws Exception {
    mockMvc.perform(delete("/api/channels/{channelId}", savedPublicChannel.getId())
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNoContent());

    assertThat(channelRepository.findById(savedPublicChannel.getId())).isEmpty();
  }

  @Test
  @DisplayName("존재하지 않는 채널 삭제 시 404와 CHANNEL_NOT_FOUND를 반환")
  void deleteChannel_notFound_returns404() throws Exception {
    mockMvc.perform(delete("/api/channels/{channelId}", UUID.randomUUID())
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
  }

  // --- PATCH ---
  @Test
  @DisplayName("Public 채널 수정 시 200과 수정된 채널 정보를 반환")
  void updatePublicChannel_returns200() throws Exception {
    ChannelUpdateRequest request = new ChannelUpdateRequest("updateName", "updateDes");

    mockMvc.perform(patch("/api/channels/{channelId}", savedPublicChannel.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("updateName"))
        .andExpect(jsonPath("$.description").value("updateDes"));
  }

  @Test
  @DisplayName("Private 채널 수정 시 400과 CHANNEL_MODIFY_PRIVATE를 반환")
  void updatePrivateChannel_returns400() throws Exception {
    ChannelUpdateRequest request = new ChannelUpdateRequest("newName", null);

    mockMvc.perform(patch("/api/channels/{channelId}", savedPrivateChannel.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request))
            .with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CHANNEL_MODIFY_PRIVATE"));
  }

  @Nested
  @DisplayName("CHANNEL_MANAGER 권한이 없는 일반 사용자")
  class WithoutChannelManagerRole {

    private String userToken;

    @BeforeEach
    void loginAsUser() throws Exception {
      saveUser("normalUser", Role.USER);
      userToken = login("normalUser");
    }

    @Test
    @DisplayName("Public 채널을 생성하면 403을 반환")
    void createPublicChannel_returns403() throws Exception {
      PublicChannelRequest request = new PublicChannelRequest("test", "des");

      mockMvc.perform(post("/api/channels/public")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request))
              .with(auth(userToken)))
          .andExpect(status().isForbidden());

      assertThat(channelRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Public 채널을 수정하면 403을 반환하고 수정되지 않음")
    void updatePublicChannel_returns403() throws Exception {
      ChannelUpdateRequest request = new ChannelUpdateRequest("hacked", null);

      mockMvc.perform(patch("/api/channels/{channelId}", savedPublicChannel.getId())
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request))
              .with(auth(userToken)))
          .andExpect(status().isForbidden());

      assertThat(channelRepository.findById(savedPublicChannel.getId()))
          .hasValueSatisfying(channel -> assertThat(channel.getName()).isEqualTo("public"));
    }

    @Test
    @DisplayName("채널을 삭제하면 403을 반환하고 삭제되지 않음")
    void deleteChannel_returns403() throws Exception {
      mockMvc.perform(delete("/api/channels/{channelId}", savedPublicChannel.getId())
              .with(auth(userToken)))
          .andExpect(status().isForbidden());

      assertThat(channelRepository.findById(savedPublicChannel.getId())).isPresent();
    }

    @Test
    @DisplayName("Private 채널은 권한과 관계없이 생성할 수 있음")
    void createPrivateChannel_returns201() throws Exception {
      PrivateChannelRequest request = new PrivateChannelRequest(List.of(savedUser.getId()));

      mockMvc.perform(post("/api/channels/private")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request))
              .with(auth(userToken)))
          .andExpect(status().isCreated());
    }
  }
}
