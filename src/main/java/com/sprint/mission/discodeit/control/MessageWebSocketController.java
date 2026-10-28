package com.sprint.mission.discodeit.control;

import com.sprint.mission.discodeit.dto.message.CreateMessageRequest;
import com.sprint.mission.discodeit.security.DiscodeitUserDetails;
import com.sprint.mission.discodeit.service.MessageService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class MessageWebSocketController {

  private final MessageService messageService;

  @MessageMapping("/messages")
  public void createMessage(@Payload CreateMessageRequest request, Principal principal) {
    // STOMP CONNECT 시 JwtAuthenticationChannelInterceptor가 설정한 인증 정보
    DiscodeitUserDetails userDetails =
        (DiscodeitUserDetails) ((Authentication) principal).getPrincipal();
    messageService.createMessage(userDetails.getUserDto().id(), request, List.of());
  }
}
