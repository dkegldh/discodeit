package com.sprint.mission.discodeit.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReadStatusIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ChannelRepository channelRepository;

  @Autowired
  private ReadStatusRepository readStatusRepository;

  private User me;
  private User other;
  private String myToken;

  @BeforeEach
  void setUp() throws Exception {
    me = saveUser("me", Role.USER);
    other = saveUser("other", Role.USER);
    myToken = login("me");

    Channel channel = channelRepository.save(new Channel("", "", ChannelType.PRIVATE));
    readStatusRepository.save(new ReadStatus(me, channel, Instant.now(), true));
    readStatusRepository.save(new ReadStatus(other, channel, Instant.now(), true));
  }

  @Test
  @DisplayName("자신의 읽음 상태 목록을 조회하면 200과 목록을 반환")
  void readOwnReadStatuses_returns200() throws Exception {
    mockMvc.perform(get("/api/readStatuses").param("userId", me.getId().toString())
            .with(auth(myToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].userId").value(me.getId().toString()));
  }

  @Test
  @DisplayName("다른 사용자의 읽음 상태 목록을 조회하면 403을 반환")
  void readOtherUsersReadStatuses_returns403() throws Exception {
    mockMvc.perform(get("/api/readStatuses").param("userId", other.getId().toString())
            .with(auth(myToken)))
        .andExpect(status().isForbidden());
  }
}
