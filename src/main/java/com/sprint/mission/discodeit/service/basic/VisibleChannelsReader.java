package com.sprint.mission.discodeit.service.basic;

import com.sprint.mission.discodeit.dto.channel.ChannelDto;
import com.sprint.mission.discodeit.dto.user.UserDto;
import com.sprint.mission.discodeit.entity.Channel;
import com.sprint.mission.discodeit.entity.ChannelType;
import com.sprint.mission.discodeit.entity.ReadStatus;
import com.sprint.mission.discodeit.mapper.ChannelMapper;
import com.sprint.mission.discodeit.mapper.UserMapper;
import com.sprint.mission.discodeit.repository.ChannelRepository;
import com.sprint.mission.discodeit.repository.ReadStatusRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 볼 수 있는 채널과 참여자 목록을 조회해 캐시한다.
 * <p>
 * 채널 구성과 참여자는 드물게 바뀌지만 마지막 메시지 시각은 메시지가 올 때마다 바뀐다. 그래서 마지막 메시지 시각은 캐시하지
 * 않고 {@link BasicChannelService#findAllByUserId}에서 매번 조회한다. 여기서 반환하는 {@code lastMessageAt}은 메시지가
 * 없을 때 쓰는 기본값인 채널 생성 시각이다.
 * <p>
 * {@code @Cacheable}은 같은 클래스 안에서 호출하면 적용되지 않으므로 별도 빈으로 분리했다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisibleChannelsReader {

  private final ChannelRepository channelRepository;
  private final ReadStatusRepository readStatusRepository;

  private final UserMapper userMapper;
  private final ChannelMapper channelMapper;

  @Cacheable(cacheNames = "channels", key = "#userId")
  public List<ChannelDto> read(UUID userId) {
    List<Channel> allChannels = channelRepository.findAll();

    Set<UUID> joinChannelIds = readStatusRepository.findAllByUserId(userId).stream()
        .map(rs -> rs.getChannel().getId())
        .collect(Collectors.toSet());

    List<Channel> targetChannels = allChannels.stream()
        .filter(c -> c.getType() == ChannelType.PUBLIC || joinChannelIds.contains(c.getId()))
        .toList();

    List<UUID> privateChannelIds = targetChannels.stream()
        .filter(c -> c.getType() == ChannelType.PRIVATE)
        .map(Channel::getId)
        .toList();
    Map<UUID, List<UserDto>> participantMap = readStatusRepository.findAllByChannelIdIn(
            privateChannelIds).stream()
        .collect(Collectors.groupingBy((ReadStatus rs) -> rs.getChannel().getId(),
            Collectors.mapping(
                (ReadStatus rs) -> userMapper.toDto(rs.getUser(), false),
                Collectors.toList()
            )
        ));

    return targetChannels.stream()
        .map(channel -> channelMapper.toDto(channel, channel.getCreatedAt(),
            participantMap.getOrDefault(channel.getId(), Collections.emptyList())))
        .toList();
  }
}
