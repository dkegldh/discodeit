package com.sprint.mission.discodeit.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Message;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.MessageRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MessageSecurityServiceTest {

  @Mock
  private MessageRepository messageRepository;

  private MessageSecurityService messageSecurityService;

  private UUID messageId;
  private UUID authorId;
  private Channel channel;

  @BeforeEach
  void setUp() {
    messageSecurityService = new MessageSecurityService(messageRepository);
    messageId = UUID.randomUUID();
    authorId = UUID.randomUUID();
    channel = new Channel("channel", "", ChannelType.PUBLIC);
  }

  private Message messageBy(User author) {
    return new Message(channel, author, "content");
  }

  @Test
  @DisplayName("작성자 본인이면 true")
  void isAuthor_author_true() {
    User author = new User("author", "a@test.com", "pw", null);
    ReflectionTestUtils.setField(author, "id", authorId);
    given(messageRepository.findById(messageId)).willReturn(Optional.of(messageBy(author)));

    assertThat(messageSecurityService.isAuthor(messageId, authorId)).isTrue();
  }

  @Test
  @DisplayName("작성자가 아니면 false")
  void isAuthor_otherUser_false() {
    User author = new User("author", "a@test.com", "pw", null);
    ReflectionTestUtils.setField(author, "id", authorId);
    given(messageRepository.findById(messageId)).willReturn(Optional.of(messageBy(author)));

    assertThat(messageSecurityService.isAuthor(messageId, UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("작성자가 탈퇴해 author가 null인 메시지는 누구도 수정/삭제할 수 없음")
  void isAuthor_deletedAuthor_false() {
    given(messageRepository.findById(messageId)).willReturn(Optional.of(messageBy(null)));

    assertThat(messageSecurityService.isAuthor(messageId, authorId)).isFalse();
  }

  @Test
  @DisplayName("메시지가 없으면 true를 반환해 인가를 통과시키고, 서비스에서 404를 응답하도록 함")
  void isAuthor_messageNotFound_true() {
    given(messageRepository.findById(messageId)).willReturn(Optional.empty());

    assertThat(messageSecurityService.isAuthor(messageId, authorId)).isTrue();
  }
}
