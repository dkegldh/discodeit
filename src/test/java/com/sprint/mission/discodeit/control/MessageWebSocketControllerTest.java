package com.sprint.mission.discodeit.control;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

import com.sprint.mission.discodeit.dto.message.CreateMessageRequest;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.security.DiscodeitUserDetails;
import com.sprint.mission.discodeit.service.MessageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class MessageWebSocketControllerTest {

  @Mock
  private MessageService messageService;

  @Test
  @DisplayName("STOMP로 메시지를 보내면 CONNECT 시 인증된 사용자를 작성자로 메시지가 생성됨")
  void createMessage_usesAuthenticatedUserAsAuthor() {
    // given
    UUID userId = UUID.randomUUID();
    DiscodeitUserDetails userDetails = new DiscodeitUserDetails(
        new UserDto(userId, "tester", "test@test.com", null, true, Role.USER), "pw");
    UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(
        userDetails, null, userDetails.getAuthorities());
    CreateMessageRequest request = new CreateMessageRequest(UUID.randomUUID(), "hello");

    // when
    new MessageWebSocketController(messageService).createMessage(request, principal);

    // then
    then(messageService).should().createMessage(eq(userId), eq(request), eq(List.of()));
  }
}
