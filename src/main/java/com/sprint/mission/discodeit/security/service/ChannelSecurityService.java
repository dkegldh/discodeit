package com.sprint.mission.discodeit.security.service;

import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 채널과 읽음 상태에 대한 접근 권한 판단. {@code @PreAuthorize}에서 사용한다.
 * <p>
 * 대상이 없으면 true를 반환해 인가를 통과시키고, 서비스에서 404를 응답하게 한다
 * ({@link MessageSecurityService}와 같은 방식).
 */
@Service("channelSecurityService")
@RequiredArgsConstructor
public class ChannelSecurityService {

  private final ChannelRepository channelRepository;
  private final ReadStatusRepository readStatusRepository;

  /**
   * Public 채널은 누구나, Private 채널은 참여자(읽음 상태가 있는 사용자)만 접근할 수 있다.
   */
  public boolean canAccess(UUID channelId, UUID userId) {
    return channelRepository.findById(channelId)
        .map(channel -> channel.getType() == ChannelType.PUBLIC
            || readStatusRepository.existsByUserIdAndChannelId(userId, channelId))
        .orElse(true);
  }

  public boolean isReadStatusOwner(UUID readStatusId, UUID userId) {
    return readStatusRepository.findById(readStatusId)
        .map(readStatus -> readStatus.getUser().getId().equals(userId))
        .orElse(true);
  }
}
