package com.sprint.mission.discodeit.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

  /**
   * 타입 정보를 함께 저장하는 Redis JSON 직렬화기. 캐시와 JWT 저장소가 같은 규칙을 쓰도록 한 곳에서 만든다.
   * <p>
   * Redis 값에 담긴 타입 정보로 아무 클래스나 만들 수 없도록(역직렬화 가젯 공격) 허용 목록을 둔다.
   * 저장되는 DTO가 쓰는 패키지만 허용하며, 새 타입을 캐싱할 때는 왕복 테스트로 확인한다.
   */
  static RedisSerializer<Object> typedJsonSerializer(ObjectMapper objectMapper) {
    ObjectMapper typedObjectMapper = objectMapper.copy();

    PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("com.sprint.mission.discodeit")
        .allowIfSubType("java.util")
        .allowIfSubType("java.time")
        .build();

    typedObjectMapper.activateDefaultTyping(
        typeValidator,
        DefaultTyping.EVERYTHING,
        As.PROPERTY
    );

    return new GenericJackson2JsonRedisSerializer(typedObjectMapper);
  }

  @Bean
  public RedisSerializer<Object> jwtRedisSerializer(ObjectMapper objectMapper) {
    return typedJsonSerializer(objectMapper);
  }

  @Bean
  public RedisTemplate<String, Object> jwtRedisTemplate(
      RedisConnectionFactory connectionFactory,
      RedisSerializer<Object> jwtRedisSerializer
  ) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(jwtRedisSerializer);
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(jwtRedisSerializer);
    template.afterPropertiesSet();
    return template;
  }
}
