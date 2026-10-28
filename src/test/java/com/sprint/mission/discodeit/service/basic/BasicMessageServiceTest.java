package com.sprint.mission.discodeit.service.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

import com.sprint.mission.discodeit.dto.message.CreateMessageRequest;
import com.sprint.mission.discodeit.dto.message.MessageDto;
import com.sprint.mission.discodeit.dto.message.MessageUpdateRequest;
import com.sprint.mission.discodeit.dto.response.PageResponse;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.BinaryContent;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.Message;
import com.sprint.mission.discodeit.entity.MessageAttachment;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.event.BinaryContentCreatedEvent;
import com.sprint.mission.discodeit.event.MessageCreatedEvent;
import com.sprint.mission.discodeit.exception.channel.ChannelNotFoundException;
import com.sprint.mission.discodeit.exception.message.MessageNotFoundException;
import com.sprint.mission.discodeit.exception.user.UserNotFoundException;
import com.sprint.mission.discodeit.mapper.MessageMapper;
import com.sprint.mission.discodeit.mapper.PageResponseMapper;
import com.sprint.mission.discodeit.repository.BinaryContentRepository;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.MessageAttachmentRepository;
import com.sprint.mission.discodeit.repository.MessageRepository;
import com.sprint.mission.discodeit.repository.UserRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import org.springframework.data.domain.Limit;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BasicMessageServiceTest {

  @Mock
  private MessageRepository messageRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private ChannelRepository channelRepository;

  @Mock
  private BinaryContentRepository binaryContentRepository;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @Mock
  private MessageMapper messageMapper;

  @Mock
  private MessageAttachmentRepository messageAttachmentRepository;

  @Mock
  private PageResponseMapper pageResponseMapper;

  private BasicMessageService messageService;

  private UUID messageId;
  private UUID channelId;
  private UUID authorId;
  private Channel mockChannel;
  private User mockAuthor;
  private Message mockMessage;
  private UserDto mockUserDto;
  private MessageDto mockMessageDto;

  @BeforeEach
  void setUp() {
    messageService = new BasicMessageService(messageRepository, userRepository,
        channelRepository, binaryContentRepository, messageAttachmentRepository, eventPublisher,
        messageMapper, pageResponseMapper);

    messageId = UUID.randomUUID();
    channelId = UUID.randomUUID();
    authorId = UUID.randomUUID();

    mockChannel = new Channel("test", "test", ChannelType.PUBLIC);
    ReflectionTestUtils.setField(mockChannel, "id", channelId);

    mockAuthor = new User("tester", "test@test.com", "password123", null);
    ReflectionTestUtils.setField(mockAuthor, "id", authorId);
    mockUserDto = new UserDto(authorId, "tester", "test@test.com", null, true, Role.USER);

    mockMessage = new Message(mockChannel, mockAuthor, "test message");
    mockMessageDto = new MessageDto(messageId, Instant.now(), Instant.now(), "test message",
        channelId, mockUserDto,
        Collections.emptyList());
  }

  @Nested
  @DisplayName("createMessage")
  class CreateMessage {

    // --- 성공 ---
    @Test
    @DisplayName("첨부파일 없이 메시지 생성")
    void createMessage_withoutAttachments_success() {
      // given
      CreateMessageRequest request = new CreateMessageRequest(
          channelId, authorId, "test message"
      );
      given(channelRepository.findById(channelId)).willReturn(Optional.of(mockChannel));
      given(userRepository.findById(authorId)).willReturn(Optional.of(mockAuthor));
      given(messageRepository.save(any(Message.class))).willReturn(mockMessage);
      given(messageMapper.toDto(any(Message.class))).willReturn(mockMessageDto);

      // when
      messageService.createMessage(request, null);

      // then
      ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
      then(messageRepository).should().save(captor.capture());
      assertThat(captor.getValue().getContent()).isEqualTo("test message");
      assertThat(captor.getValue().getChannel()).isSameAs(mockChannel);
      assertThat(captor.getValue().getAuthor()).isSameAs(mockAuthor);
      then(binaryContentRepository).should(never()).save(any());
      then(eventPublisher).should().publishEvent(
          new MessageCreatedEvent(channelId, "test", authorId, "tester", "test message"));
    }

    @Test
    @DisplayName("첨부파일과 함께 메시지 생성")
    void createMessage_withAttachments_success() {
      // given
      CreateMessageRequest request = new CreateMessageRequest(
          channelId, authorId, "test message"
      );
      MockMultipartFile file = new MockMultipartFile(
          "file", "test.png", "image/png", "fake-bytes".getBytes()
      );
      BinaryContent savedBinaryContent = new BinaryContent("test.png", "image/png", 10L);
      UUID binaryId = UUID.randomUUID();
      ReflectionTestUtils.setField(savedBinaryContent, "id", binaryId);

      given(channelRepository.findById(channelId)).willReturn(Optional.of(mockChannel));
      given(userRepository.findById(authorId)).willReturn(Optional.of(mockAuthor));
      given(binaryContentRepository.save(any(BinaryContent.class))).willReturn(savedBinaryContent);
      given(messageRepository.save(any(Message.class))).willReturn(mockMessage);
      given(messageMapper.toDto(any(Message.class))).willReturn(mockMessageDto);

      // when
      messageService.createMessage(request, List.of(file));

      // then
      ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
      then(messageRepository).should().save(captor.capture());
      assertThat(captor.getValue().getMessageAttachments())
          .extracting(MessageAttachment::getBinaryContent)
          .containsExactly(savedBinaryContent);
      then(eventPublisher).should().publishEvent(argThat((Object event) ->
          event instanceof BinaryContentCreatedEvent e && e.binaryContentId().equals(binaryId)));
    }

    // --- 실패 ---
    @Test
    @DisplayName("존재하지 않는 channelId일 경우 ChannelNotFoundException이 발생")
    void createMessage_channelNotFound_throwsChannelNotFoundException() {
      // given
      CreateMessageRequest request = new CreateMessageRequest(
          channelId, authorId, "test message"
      );
      given(channelRepository.findById(channelId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> messageService.createMessage(request, null))
          .isInstanceOf(ChannelNotFoundException.class);
      then(messageRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 authorId일 경우 UserNotFoundException이 발생")
    void createMessage_userNotFound_throwsUserNotFoundException() {
      // given
      CreateMessageRequest request = new CreateMessageRequest(
          channelId, authorId, "test message"
      );
      given(channelRepository.findById(channelId)).willReturn(Optional.of(mockChannel));
      given(userRepository.findById(authorId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> messageService.createMessage(request, null))
          .isInstanceOf(UserNotFoundException.class);
      then(messageRepository).should(never()).save(any());
    }
  }

  @Nested
  @DisplayName("readMessageByChannel")
  class ReadMessageByChannel {

    private Message messageAt(Instant createdAt) {
      Message message = new Message(mockChannel, mockAuthor, "message at " + createdAt);
      ReflectionTestUtils.setField(message, "createdAt", createdAt);
      return message;
    }

    @Test
    @DisplayName("다음 페이지 존재 여부를 판단하기 위해 size + 1개를 조회")
    void readMessagesByChannel_requestsSizePlusOne() {
      // given
      given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(channelId), any(Limit.class)))
          .willReturn(List.of());

      // when
      messageService.readMessagesByChannel(channelId, null, 10);

      // then
      ArgumentCaptor<Limit> captor = ArgumentCaptor.forClass(Limit.class);
      then(messageRepository).should()
          .findByChannelIdOrderByCreatedAtDesc(eq(channelId), captor.capture());
      assertThat(captor.getValue().max()).isEqualTo(11);
    }

    @Test
    @DisplayName("cursor가 null이고 조회 결과가 size 이하면 hasNext=false, nextCursor=null")
    void readMessagesByChannel_noCursor_lastPage() {
      // given
      given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(channelId), any(Limit.class)))
          .willReturn(List.of(mockMessage));
      given(messageMapper.toDto(mockMessage)).willReturn(mockMessageDto);

      // when
      PageResponse<MessageDto> result = messageService.readMessagesByChannel(channelId, null, 10);

      // then
      assertThat(result.content()).hasSize(1);
      assertThat(result.hasNext()).isFalse();
      assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("조회 결과가 size보다 많으면 size개만 반환하고, 마지막 메시지의 createdAt을 nextCursor로 반환")
    void readMessagesByChannel_hasNext_returnsSizeAndCursor() {
      // given
      Instant now = Instant.now();
      Message m1 = messageAt(now);
      Message m2 = messageAt(now.minusSeconds(1));
      Message m3 = messageAt(now.minusSeconds(2));
      given(messageRepository.findByChannelIdOrderByCreatedAtDesc(eq(channelId), any(Limit.class)))
          .willReturn(List.of(m1, m2, m3));
      given(messageMapper.toDto(any(Message.class))).willReturn(mockMessageDto);

      // when
      PageResponse<MessageDto> result = messageService.readMessagesByChannel(channelId, null, 2);

      // then
      assertThat(result.content()).hasSize(2);
      assertThat(result.hasNext()).isTrue();
      assertThat(result.nextCursor()).isEqualTo(m2.getCreatedAt());
      then(messageMapper).should(never()).toDto(m3);
    }

    @Test
    @DisplayName("cursor가 있을 경우 해당 시점 이전 메시지를 조회")
    void readMessageByChannel_withCursor_success() {
      // given
      Instant cursor = Instant.now();

      given(messageRepository.findByChannelIdAndCreatedAtBeforeOrderByCreatedAtDesc(
          eq(channelId), eq(cursor), any(Limit.class)
      )).willReturn(List.of(mockMessage));
      given(messageMapper.toDto(mockMessage)).willReturn(mockMessageDto);

      // when
      PageResponse<MessageDto> result = messageService.readMessagesByChannel(
          channelId, cursor, 10
      );

      // then
      assertThat(result.content()).hasSize(1);
      assertThat(result.hasNext()).isFalse();
    }
  }

  @Nested
  @DisplayName("deleteMessage")
  class DeleteMessage {

    // --- 성공 ---
    @Test
    @DisplayName("첨부파일이 없는 메시지가 정상적으로 삭제됨")
    void deleteMessage_withoutAttachments_success() {
      // given
      given(messageRepository.findById(messageId)).willReturn(Optional.of(mockMessage));

      // when
      messageService.deleteMessage(messageId);

      // then
      then(binaryContentRepository).should(never()).deleteAll(any());
      then(messageRepository).should().delete(mockMessage);
    }

    @Test
    @DisplayName("첨부파일이 있는 메시지 삭제 시 BinaryContent도 함께 삭제됨")
    void deleteMessage_withAttachments_success() {
      // given
      BinaryContent binary = new BinaryContent("file.png", "image/png", 10L);
      MessageAttachment attachment = new MessageAttachment(mockMessage, binary);
      ReflectionTestUtils.setField(mockMessage, "messageAttachments", List.of(attachment));

      given(messageRepository.findById(messageId)).willReturn(Optional.of(mockMessage));

      // when
      messageService.deleteMessage(messageId);

      // then
      then(messageAttachmentRepository).should().deleteAll(List.of(attachment));
      then(binaryContentRepository).should().deleteAll(List.of(binary));
      then(messageRepository).should().delete(mockMessage);
    }

    // --- 실패 ---
    @Test
    @DisplayName("존재하지 않는 messageId일 경우 MessageNotFoundException이 발생")
    void deleteMessage_messageNotFound_throwsMessageNotFoundException() {
      // given
      given(messageRepository.findById(messageId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> messageService.deleteMessage(messageId))
          .isInstanceOf(MessageNotFoundException.class);
      then(messageRepository).should(never()).delete(any());
    }
  }

  @Nested
  @DisplayName("updateMessage")
  class UpdateMessage {

    // --- 성공 ---
    @Test
    @DisplayName("메시지가 정상적으로 업데이트됨")
    void updateMessage_success() {
      // given
      MessageUpdateRequest request = new MessageUpdateRequest("new message");
      given(messageRepository.findById(messageId)).willReturn(Optional.of(mockMessage));

      // when
      messageService.updateMessage(messageId, request);

      // then
      assertThat(mockMessage.getContent()).isEqualTo("new message");
      then(messageMapper).should().toDto(mockMessage);
    }

    // --- 실패 ---
    @Test
    @DisplayName("존재하지 않는 messageId일 경우 MessageNotFoundException이 발생")
    void updateMessage_messageNotFound_throwsMessageNotFoundException() {
      // given
      given(messageRepository.findById(messageId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(
          () -> messageService.updateMessage(messageId, new MessageUpdateRequest("new message")))
          .isInstanceOf(MessageNotFoundException.class);
    }
  }
}