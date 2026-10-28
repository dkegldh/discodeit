package com.sprint.mission.discodeit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sprint.mission.discodeit.redis.RedisLockProvider;
import com.sprint.mission.discodeit.security.jwt.InMemoryJwtRegistry;
import com.sprint.mission.discodeit.security.jwt.JwtRegistry;
import com.sprint.mission.discodeit.security.jwt.RedisJwtRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;

class JwtRegistryConfigTest {

  // JwtRegistry를 주입받는 컴포넌트와 같은 방식(생성자, 파라미터명 jwtRegistry)으로 주입받는 빈
  static class JwtRegistryConsumer {

    final JwtRegistry jwtRegistry;

    JwtRegistryConsumer(JwtRegistry jwtRegistry) {
      this.jwtRegistry = jwtRegistry;
    }
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(JwtRegistryConfig.class)
      .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
      .withBean(RedisLockProvider.class, () -> mock(RedisLockProvider.class))
      .withBean(JwtRegistryConsumer.class)
      .withPropertyValues("discodeit.security.jwt.max-active-count=1");

  @Test
  @DisplayName("prod 프로파일에서는 서버 간 공유되는 RedisJwtRegistry가 주입됨")
  void prodProfile_injectsRedisJwtRegistry() {
    contextRunner.withPropertyValues("spring.profiles.active=prod").run(context -> {
      assertThat(context).hasSingleBean(JwtRegistry.class);
      assertThat(context.getBean(JwtRegistryConsumer.class).jwtRegistry)
          .isInstanceOf(RedisJwtRegistry.class);
    });
  }

  @Test
  @DisplayName("prod가 아닌 프로파일에서는 InMemoryJwtRegistry가 주입됨")
  void nonProdProfile_injectsInMemoryJwtRegistry() {
    contextRunner.withPropertyValues("spring.profiles.active=dev").run(context -> {
      assertThat(context).hasSingleBean(JwtRegistry.class);
      assertThat(context.getBean(JwtRegistryConsumer.class).jwtRegistry)
          .isInstanceOf(InMemoryJwtRegistry.class);
    });
  }
}
