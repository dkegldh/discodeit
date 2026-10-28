package com.sprint.mission.discodeit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sprint.mission.discodeit.dto.user.UserCreateRequest;
import com.sprint.mission.discodeit.dto.user.UserUpdateRequest;
import com.sprint.mission.discodeit.entity.BinaryContentStatus;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.BinaryContentRepository;
import com.sprint.mission.discodeit.storage.BinaryContentStorage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class UserIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private BinaryContentRepository binaryContentRepository;

  @Autowired
  private BinaryContentStorage binaryContentStorage;

  private User savedUser;
  private String accessToken;

  @BeforeEach
  void setUp() throws Exception {
    savedUser = saveUser("test", Role.USER);
    accessToken = login("test");
  }

  private MockMultipartFile jsonPart(String name, Object value) throws Exception {
    return new MockMultipartFile(name, "", MediaType.APPLICATION_JSON_VALUE,
        objectMapper.writeValueAsBytes(value));
  }

  // --- POST ---
  @Test
  @DisplayName("유효한 요청으로 사용자 생성 시 201과 생성된 사용자 정보를 반환")
  void createUser_validRequest_returns201() throws Exception {
    UserCreateRequest request = new UserCreateRequest("newUser", "new@test.com", "password123");

    mockMvc.perform(multipart("/api/users").file(jsonPart("userCreateRequest", request))
            .with(csrf()))
        .andDo(print())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("newUser"))
        .andExpect(jsonPath("$.email").value("new@test.com"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  @DisplayName("프로필 이미지와 함께 사용자 생성 시, 커밋 이후 비동기로 파일이 저장되고 상태가 SUCCESS로 변경됨")
  void createUser_withProfile_storesFileAfterCommit() throws Exception {
    UserCreateRequest request = new UserCreateRequest("newUser", "new@test.com", "password123");
    MockMultipartFile profile = new MockMultipartFile("profile", "profile.png", "image/png",
        "image-bytes".getBytes());

    MvcResult result = mockMvc.perform(multipart("/api/users")
            .file(jsonPart("userCreateRequest", request))
            .file(profile)
            .with(csrf()))
        .andExpect(status().isCreated())
        .andReturn();
    UUID profileId = UUID.fromString(objectMapper.readTree(result.getResponse()
        .getContentAsString()).get("profile").get("id").asText());

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(binaryContentRepository.findById(profileId))
            .hasValueSatisfying(content ->
                assertThat(content.getStatus()).isEqualTo(BinaryContentStatus.SUCCESS)));
    assertThat(binaryContentStorage.get(profileId).readAllBytes())
        .isEqualTo("image-bytes".getBytes());
  }

  @Test
  @DisplayName("중복된 이메일로 사용자 생성 시 409를 반환")
  void createUser_duplicateEmail_returns409() throws Exception {
    UserCreateRequest request = new UserCreateRequest("otherUser", "test@test.com",
        "password123");

    mockMvc.perform(multipart("/api/users").file(jsonPart("userCreateRequest", request))
            .with(csrf()))
        .andDo(print())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
  }

  // --- GET ---
  @Test
  @DisplayName("전체 사용자 조회 시 200과 사용자 목록을 반환")
  void getAllUsers_returns200WithUserList() throws Exception {
    mockMvc.perform(get("/api/users").with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].username").value("test"))
        .andExpect(jsonPath("$[0].email").value("test@test.com"));
  }

  @Test
  @DisplayName("인증된 사용자가 삭제된 경우 전체 조회 시 401을 반환")
  void getAllUsers_noUsers_return401WhenUserDeleted() throws Exception {
    userRepository.deleteAll();

    mockMvc.perform(get("/api/users").with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("토큰 없이 전체 조회 시 401을 반환")
  void getAllUsers_withoutToken_returns401() throws Exception {
    mockMvc.perform(get("/api/users"))
        .andExpect(status().isUnauthorized());
  }

  // --- DELETE ---
  @Test
  @DisplayName("존재하는 사용자를 삭제 할 경우 204를 반환하고 DB에서도 삭제됨")
  void deleteUser_return204AndDeletedFromDb() throws Exception {
    mockMvc.perform(delete("/api/users/{id}", savedUser.getId()).with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isNoContent());

    assertThat(userRepository.findById(savedUser.getId())).isEmpty();
  }

  @Test
  @DisplayName("본인이 아닌 다른 사용자를 삭제 시도할 경우 403을 반환하고 삭제되지 않음")
  void deleteUser_otherUser_returns403() throws Exception {
    User otherUser = saveUser("other", Role.USER);

    mockMvc.perform(delete("/api/users/{id}", otherUser.getId()).with(auth(accessToken)))
        .andDo(print())
        .andExpect(status().isForbidden());

    assertThat(userRepository.findById(otherUser.getId())).isPresent();
  }

  // --- PATCH ---
  @Test
  @DisplayName("유효한 요청으로 사용자를 수정 할 경우 200과 수정된 사용자 정보를 반환")
  void updateUser_returns200() throws Exception {
    UserUpdateRequest request = new UserUpdateRequest("updateUser", null, null);

    mockMvc.perform(multipart("/api/users/{id}", savedUser.getId())
            .file(jsonPart("userUpdateRequest", request))
            .with(auth(accessToken))
            .with(req -> {
              req.setMethod("PATCH");
              return req;
            }))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("updateUser"))
        .andExpect(jsonPath("$.id").value(savedUser.getId().toString()));
  }

  @Test
  @DisplayName("본인이 아닌 다른 사용자를 수정 시도할 경우 403을 반환하고 수정되지 않음")
  void updateUser_otherUser_returns403() throws Exception {
    User otherUser = saveUser("other", Role.USER);
    UserUpdateRequest request = new UserUpdateRequest("hacked", null, null);

    mockMvc.perform(multipart("/api/users/{id}", otherUser.getId())
            .file(jsonPart("userUpdateRequest", request))
            .with(auth(accessToken))
            .with(req -> {
              req.setMethod("PATCH");
              return req;
            }))
        .andDo(print())
        .andExpect(status().isForbidden());

    assertThat(userRepository.findById(otherUser.getId()))
        .hasValueSatisfying(user -> assertThat(user.getUsername()).isEqualTo("other"));
  }
}
