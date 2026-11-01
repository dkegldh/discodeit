package com.sprint.mission.discodeit.integration;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprint.mission.discodeit.entity.Role;
import com.sprint.mission.discodeit.entity.User;
import com.sprint.mission.discodeit.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 통합 테스트 공통 설정.
 * <p>
 * 테스트 메서드를 트랜잭션으로 감싸 롤백하면 커밋이 일어나지 않아 {@code AFTER_COMMIT} 이벤트 리스너(파일 저장, 알림 생성,
 * Kafka 발행)가 실행되지 않는다. 운영과 같은 흐름을 검증하기 위해 트랜잭션 없이 실행하고, 매 테스트 후 테이블과 캐시를 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  protected static final String DEFAULT_PASSWORD = "password123";

  private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
  private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
  private static final List<String> TABLES = List.of(
      "notifications", "message_attachments", "read_statuses", "messages", "channels", "users",
      "binary_contents"
  );

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected ObjectMapper objectMapper;

  @Autowired
  protected UserRepository userRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private CacheManager cacheManager;

  @Autowired
  @Qualifier("eventTaskExecutor")
  private TaskExecutor eventTaskExecutor;

  // 외부 브로커 없이 실행하기 위해 대체하고, 발행 여부는 이 Mock으로 검증한다
  @MockitoBean
  protected KafkaTemplate<String, String> kafkaTemplate;

  private Cookie csrfCookie;

  @BeforeEach
  void issueCsrfToken() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/auth/csrf-token"))
        .andExpect(status().isNoContent())
        .andReturn();
    csrfCookie = result.getResponse().getCookie(CSRF_COOKIE_NAME);
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    TABLES.forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
    jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  protected User saveUser(String username, Role role) {
    User user = new User(username, username + "@test.com", passwordEncoder.encode(DEFAULT_PASSWORD),
        null);
    user.updateRole(role);
    return userRepository.save(user);
  }

  protected String login(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .param("username", username)
            .param("password", DEFAULT_PASSWORD)
            .with(csrf()))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  /**
   * 커밋 이후 Kafka로 발행된 메시지를 반환한다. 테스트에는 브로커가 없으므로, 이 값을 {@code @KafkaListener} 메서드에
   * 직접 넘겨 컨슈머 전달을 대신한다.
   */
  protected String awaitKafkaPayload(String topic) {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate, timeout(5000)).send(eq(topic), captor.capture());
    return captor.getValue();
  }

  /**
   * {@code eventTaskExecutor}에서 실행되는 비동기 이벤트 리스너가 모두 끝날 때까지 기다린다. "N개만 생성됨"처럼 나중에 더
   * 생기지 않는다는 것을 확인할 때 사용한다.
   */
  protected void awaitAsyncEventListeners() {
    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) eventTaskExecutor;
    await().atMost(Duration.ofSeconds(5)).until(() -> executor.getActiveCount() == 0
        && executor.getThreadPoolExecutor().getQueue().isEmpty());
  }

  protected RequestPostProcessor csrf() {
    return request -> {
      // setCookies는 기존 쿠키를 덮어쓰므로, 요청에 이미 담긴 쿠키(예: refresh token)를 유지한 채 추가
      Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
      Cookie[] cookies = Arrays.copyOf(existing, existing.length + 1);
      cookies[existing.length] = csrfCookie;
      request.setCookies(cookies);
      request.addHeader(CSRF_HEADER_NAME, csrfCookie.getValue());
      return request;
    };
  }

  /**
   * Bearer 토큰과 CSRF 토큰을 함께 설정한다.
   */
  protected RequestPostProcessor auth(String accessToken) {
    return request -> {
      csrf().postProcessRequest(request);
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
      return request;
    };
  }
}
