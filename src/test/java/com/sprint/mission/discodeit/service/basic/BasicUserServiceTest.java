package com.sprint.mission.discodeit.service.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.sprint.mission.discodeit.dto.user.RoleUpdateRequest;
import com.sprint.mission.discodeit.dto.user.UserCreateRequest;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.dto.user.UserUpdateRequest;
import com.sprint.mission.discodeit.entity.BinaryContent;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.event.BinaryContentCreatedEvent;
import com.sprint.mission.discodeit.event.PasswordChangeEvent;
import com.sprint.mission.discodeit.event.RoleUpdatedEvent;
import com.sprint.mission.discodeit.event.UserCreatedEvent;
import com.sprint.mission.discodeit.exception.user.DuplicateUserException;
import com.sprint.mission.discodeit.exception.user.EmailAlreadyExistsException;
import com.sprint.mission.discodeit.exception.user.UserNotFoundException;
import com.sprint.mission.discodeit.mapper.UserMapper;
import com.sprint.mission.discodeit.repository.BinaryContentRepository;
import com.sprint.mission.discodeit.repository.UserRepository;
import com.sprint.mission.discodeit.security.jwt.JwtRegistry;
import com.sprint.mission.discodeit.storage.BinaryContentStorage;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BasicUserServiceTest {

  @Mock
  private UserRepository userRepository;
  @Mock
  private BinaryContentRepository binaryContentRepository;
  @Mock
  private BinaryContentStorage binaryContentStorage;
  @Mock
  private ApplicationEventPublisher eventPublisher;
  @Mock
  private UserMapper userMapper;
  @Mock
  private JwtRegistry jwtRegistry;
  @Mock
  private PasswordEncoder passwordEncoder;

  // @InjectMocks는 누락된 생성자 인자에 null을 주입하므로, 직접 생성해 의존성 변경을 컴파일 타임에 드러낸다
  private BasicUserService userService;

  private UUID userId;
  private User mockUser;
  private UserDto mockUserDto;

  @BeforeEach
  void setUp() {
    userService = new BasicUserService(userRepository, binaryContentRepository,
        binaryContentStorage, jwtRegistry, userMapper, passwordEncoder, eventPublisher);

    userId = UUID.randomUUID();
    mockUser = new User("testUser", "test@test.com", "password123", null);
    ReflectionTestUtils.setField(mockUser, "id", userId);

    mockUserDto = new UserDto(
        userId,
        "tester",
        "test@test.com",
        null,
        true,
        Role.USER
    );
  }

  @Nested
  @DisplayName("createUser()")
  class CreateUser {

    private UserCreateRequest request;

    @BeforeEach
    void setUpRequest() {
      request = new UserCreateRequest("tester", "test@test.com", "password123");
    }

    // --- 성공 ---
    @Test
    @DisplayName("유저 생성 성공 - 비밀번호가 인코딩되어 저장되고 생성 이벤트가 발행됨")
    void createUser_success_withoutProfile() {
      // given
      given(userRepository.findByUsername("tester")).willReturn(Optional.empty());
      given(userRepository.findByEmail("test@test.com")).willReturn(Optional.empty());
      given(passwordEncoder.encode("password123")).willReturn("encodedPassword");
      given(userMapper.toDto(any(User.class), anyBoolean())).willReturn(mockUserDto);

      // when
      userService.createUser(request, null);

      // then
      ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
      then(userRepository).should().save(captor.capture());
      User saved = captor.getValue();
      assertThat(saved.getUsername()).isEqualTo("tester");
      assertThat(saved.getEmail()).isEqualTo("test@test.com");
      assertThat(saved.getPassword()).isEqualTo("encodedPassword");
      assertThat(saved.getRole()).isEqualTo(Role.USER);
      assertThat(saved.getProfile()).isNull();

      then(binaryContentRepository).should(never()).save(any());
      then(eventPublisher).should().publishEvent(any(UserCreatedEvent.class));
    }

    @Test
    @DisplayName("유저 생성 성공 - username, email, password의 앞뒤 공백이 제거되어 저장됨")
    void createUser_trimsInput() {
      // given
      UserCreateRequest paddedRequest = new UserCreateRequest("  tester ", " test@test.com ",
          " password123 ");
      given(userRepository.findByUsername("tester")).willReturn(Optional.empty());
      given(userRepository.findByEmail("test@test.com")).willReturn(Optional.empty());
      given(passwordEncoder.encode("password123")).willReturn("encodedPassword");

      // when
      userService.createUser(paddedRequest, null);

      // then
      ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
      then(userRepository).should().save(captor.capture());
      assertThat(captor.getValue().getUsername()).isEqualTo("tester");
      assertThat(captor.getValue().getEmail()).isEqualTo("test@test.com");
    }

    @Test
    @DisplayName("유저 생성 성공 - 프로필 이미지 메타데이터가 저장되고 바이너리 저장 이벤트가 발행됨")
    void createUser_success_withProfile() {
      MockMultipartFile profileFile = new MockMultipartFile(
          "profile", "profile.png", "image/png", "fake-image-bytes".getBytes()
      );
      BinaryContent savedBinary = new BinaryContent("profile.png", "image/png", 16L);
      UUID binaryId = UUID.randomUUID();
      ReflectionTestUtils.setField(savedBinary, "id", binaryId);

      // given
      given(userRepository.findByUsername("tester")).willReturn(Optional.empty());
      given(userRepository.findByEmail("test@test.com")).willReturn(Optional.empty());
      given(binaryContentRepository.save(any(BinaryContent.class))).willReturn(savedBinary);
      given(passwordEncoder.encode(any())).willReturn("encodedPassword");

      // when
      userService.createUser(request, profileFile);

      // then
      ArgumentCaptor<BinaryContent> binaryCaptor = ArgumentCaptor.forClass(BinaryContent.class);
      then(binaryContentRepository).should().save(binaryCaptor.capture());
      assertThat(binaryCaptor.getValue().getFileName()).isEqualTo("profile.png");
      assertThat(binaryCaptor.getValue().getContentType()).isEqualTo("image/png");

      ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
      then(userRepository).should().save(userCaptor.capture());
      assertThat(userCaptor.getValue().getProfile()).isSameAs(binaryCaptor.getValue());

      then(eventPublisher).should().publishEvent(argThat((Object event) ->
          event instanceof BinaryContentCreatedEvent e
              && e.binaryContentId().equals(binaryId)
              && new String(e.bytes()).equals("fake-image-bytes")));
    }

    // --- 실패 ---
    @Test
    @DisplayName("유저 생성 실패 - 이미 존재하는 유저")
    void createUser_duplicateUsername_duplicateUserException() {
      // given
      given(userRepository.findByUsername("tester")).willReturn(Optional.of(mockUser));

      // when & then
      assertThatThrownBy(() -> userService.createUser(request, null))
          .isInstanceOf(DuplicateUserException.class);

      then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("유저 생성 실패 - 이미 존재하는 이메일")
    void createUser_duplicateEmail_EmailAlreadyExistsException() {
      // given
      given(userRepository.findByUsername("tester")).willReturn(Optional.empty());
      given(userRepository.findByEmail("test@test.com")).willReturn(Optional.of(mockUser));

      // when & then
      assertThatThrownBy(() -> userService.createUser(request, null))
          .isInstanceOf(EmailAlreadyExistsException.class);

      then(userRepository).should(never()).save(any());
    }
  }

  @Nested
  @DisplayName("readUser()")
  class ReadUser {

    @Test
    @DisplayName("활성 JWT가 있는 사용자는 online=true로 조회됨")
    void readUser_activeUser_online() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(jwtRegistry.getActiveUserIds()).willReturn(Set.of(userId));

      // when
      userService.readUser(userId);

      // then
      then(userMapper).should().toDto(mockUser, true);
    }

    @Test
    @DisplayName("활성 JWT가 없는 사용자는 online=false로 조회됨")
    void readUser_inactiveUser_offline() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(jwtRegistry.getActiveUserIds()).willReturn(Set.of(UUID.randomUUID()));

      // when
      userService.readUser(userId);

      // then
      then(userMapper).should().toDto(mockUser, false);
    }

    @Test
    @DisplayName("존재하지 않는 userId일 경우 UserNotFoundException 발생")
    void readUser_userNotFound_throwsUserNotFoundException() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> userService.readUser(userId))
          .isInstanceOf(UserNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("deleteUser()")
  class DeleteUser {

    // --- 성공 ---
    @Test
    @DisplayName("프로필 이미지가 있는 사용자를 삭제하는 경우 BinaryContent도 함께 삭제됨")
    void deleteUser_withProfile_deletesProfileAndUser() {
      // given
      BinaryContent profile = new BinaryContent("pic.png", "image/png", 20L);
      User userWithProfile = new User("tester", "test@test.com", "pw", profile);

      given(userRepository.findById(userId)).willReturn(Optional.of(userWithProfile));

      // when
      userService.deleteUser(userId);

      // then
      then(binaryContentRepository).should().delete(profile);
      then(userRepository).should().delete(userWithProfile);
    }

    @Test
    @DisplayName("프로필 이미지가 없는 사용자를 삭제하는 경우")
    void deleteUser_withoutProfile_deletesUser() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));

      // when
      userService.deleteUser(userId);

      // then
      then(binaryContentRepository).should(never()).delete(any());
      then(userRepository).should().delete(mockUser);
    }

    // --- 실패 ---
    @Test
    @DisplayName("존재하지 않는 userId일 경우 UserNotFoundException 발생")
    void deleteUser_userNotFound_throwsUserNotFoundException() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> userService.deleteUser(userId))
          .isInstanceOf(UserNotFoundException.class);

      then(userRepository).should(never()).delete(any(User.class));
    }
  }

  @Nested
  @DisplayName("updateUser()")
  class UpdateUser {

    // --- 성공 ---
    @Test
    @DisplayName("username, email, password가 변경되고 비밀번호는 인코딩되어 저장됨")
    void updateUser_allField_success() {
      // given
      UserUpdateRequest updateRequest = new UserUpdateRequest("newName", "new@test.com",
          "newPassword123");

      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(userRepository.findByUsername("newName")).willReturn(Optional.empty());
      given(userRepository.findByEmail("new@test.com")).willReturn(Optional.empty());
      given(passwordEncoder.encode("newPassword123")).willReturn("encodedNewPassword");
      given(jwtRegistry.getActiveUserIds()).willReturn(Collections.emptySet());

      // when
      userService.updateUser(userId, updateRequest, null);

      // then
      assertThat(mockUser.getUsername()).isEqualTo("newName");
      assertThat(mockUser.getEmail()).isEqualTo("new@test.com");
      assertThat(mockUser.getPassword()).isEqualTo("encodedNewPassword");
    }

    @Test
    @DisplayName("비밀번호를 변경하면 PasswordChangeEvent가 발행됨")
    void updateUser_passwordChanged_publishesPasswordChangeEvent() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(passwordEncoder.encode("newPassword123")).willReturn("encodedNewPassword");
      given(jwtRegistry.getActiveUserIds()).willReturn(Collections.emptySet());

      // when
      userService.updateUser(userId, new UserUpdateRequest(null, null, "newPassword123"), null);

      // then
      then(eventPublisher).should().publishEvent(argThat((Object event) ->
          event instanceof PasswordChangeEvent e && e.getUserId().equals(userId)));
    }

    @Test
    @DisplayName("비밀번호를 변경하지 않으면 기존 비밀번호가 유지되고 PasswordChangeEvent가 발행되지 않음")
    void updateUser_passwordNotChanged_keepsPassword() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(userRepository.findByUsername("newName")).willReturn(Optional.empty());
      given(jwtRegistry.getActiveUserIds()).willReturn(Collections.emptySet());

      // when
      userService.updateUser(userId, new UserUpdateRequest("newName", null, null), null);

      // then
      assertThat(mockUser.getPassword()).isEqualTo("password123");
      then(passwordEncoder).should(never()).encode(any());
      then(eventPublisher).should(never()).publishEvent(any(PasswordChangeEvent.class));
    }

    @Test
    @DisplayName("프로필 이미지 변경 시 기존 이미지는 삭제되고 새 이미지로 교체됨")
    void updateUser_profileImage_success() {
      //given
      BinaryContent existingProfile = new BinaryContent("last.png", "image.png", 10L);
      User userWithProfile = new User("tester", "test@test.com", "password123", existingProfile);

      MockMultipartFile newProfileFile = new MockMultipartFile(
          "profile", "new.png", "image/png", "new-image".getBytes()
      );
      BinaryContent newBinary = new BinaryContent("new.png", "image/png", 9L);
      UUID newBinaryId = UUID.randomUUID();
      ReflectionTestUtils.setField(newBinary, "id", newBinaryId);

      given(userRepository.findById(userId)).willReturn(Optional.of(userWithProfile));
      given(binaryContentRepository.save(any(BinaryContent.class))).willReturn(newBinary);
      given(jwtRegistry.getActiveUserIds()).willReturn(Collections.emptySet());

      // when
      userService.updateUser(userId, new UserUpdateRequest(null, null, null), newProfileFile);

      // then
      then(binaryContentRepository).should().delete(existingProfile);
      assertThat(userWithProfile.getProfile().getFileName()).isEqualTo("new.png");
      then(eventPublisher).should().publishEvent(argThat((Object event) ->
          event instanceof BinaryContentCreatedEvent e
              && e.binaryContentId().equals(newBinaryId)));
    }

    // --- 실패 ---
    @Test
    @DisplayName("존재하지 않는 userId일 경우 UserNotFoundException이 발생")
    void updateUser_userNotFound_throwsUserNotFoundException() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> userService.updateUser(userId,
          new UserUpdateRequest("name", "ex@ex.com", "password"), null))
          .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("변경하려는 username이 이미 사용중이면 DuplicateUserException이 발생")
    void updateUser_duplicateUsername_throwsDuplicateUserException() {
      // given
      User otherUser = new User("otherName", "other@test.com", "password", null);
      UserUpdateRequest updateRequest = new UserUpdateRequest("otherName", null, null);

      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(userRepository.findByUsername("otherName")).willReturn(Optional.of(otherUser));

      // when & then
      assertThatThrownBy(() -> userService.updateUser(userId, updateRequest, null))
          .isInstanceOf(DuplicateUserException.class);
      assertThat(mockUser.getUsername()).isEqualTo("testUser");
    }

    @Test
    @DisplayName("변경하려는 email이 이미 사용중이라면 EmailAlreadyExistsException이 발생")
    void updateUser_duplicateEmail_throwsEmailAlreadyExistsException() {
      // given
      User otherUser = new User("otherName", "other@test.com", "password", null);
      UserUpdateRequest updateRequest = new UserUpdateRequest(null, "other@test.com", null);

      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(userRepository.findByEmail("other@test.com")).willReturn(Optional.of(otherUser));

      // when & then
      assertThatThrownBy(() -> userService.updateUser(userId, updateRequest, null))
          .isInstanceOf(EmailAlreadyExistsException.class);
      assertThat(mockUser.getEmail()).isEqualTo("test@test.com");
    }
  }

  @Nested
  @DisplayName("updateRole()")
  class UpdateRole {

    @Test
    @DisplayName("권한이 변경되면 RoleUpdatedEvent가 발행되고 기존 JWT가 무효화됨")
    void updateRole_success_invalidatesJwt() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));

      // when
      userService.updateRole(new RoleUpdateRequest(userId, Role.CHANNEL_MANAGER));

      // then
      assertThat(mockUser.getRole()).isEqualTo(Role.CHANNEL_MANAGER);
      then(eventPublisher).should()
          .publishEvent(new RoleUpdatedEvent(userId, Role.USER, Role.CHANNEL_MANAGER));
      then(jwtRegistry).should().invalidateJwtInformationByUserId(userId);
    }

    @Test
    @DisplayName("존재하지 않는 userId일 경우 UserNotFoundException이 발생하고 JWT는 무효화되지 않음")
    void updateRole_userNotFound_throwsUserNotFoundException() {
      // given
      given(userRepository.findById(userId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(
          () -> userService.updateRole(new RoleUpdateRequest(userId, Role.ADMIN)))
          .isInstanceOf(UserNotFoundException.class);
      then(jwtRegistry).should(never()).invalidateJwtInformationByUserId(any());
    }
  }
}
