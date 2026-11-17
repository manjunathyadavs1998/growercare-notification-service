package com.growercare.notification.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.notification.adapters.out.provider.MailboxProvider;
import com.growercare.notification.application.port.MessageProvider;
import com.growercare.testsupport.PostgresTestDatabase;
import com.growercare.testsupport.TestJwts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Spring context for notification-service API tests: a real Postgres (owner/app split, Flyway as the owner),
 * test JWTs, the mailbox provider wrapped so a test can make it fail (SPEC-NOT-01 R6).
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@AutoConfigureMockMvc
@Import(NotificationApiTestBase.Stubs.class)
public abstract class NotificationApiTestBase {

    protected static final PostgresTestDatabase DB = PostgresTestDatabase.forService("gc_notification");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::jdbcUrl);
        r.add("spring.datasource.username", DB::appUser);
        r.add("spring.datasource.password", DB::appPassword);
        r.add("spring.flyway.user", DB::ownerUser);
        r.add("spring.flyway.password", DB::ownerPassword);
    }

    /** The real mailbox provider, with a switch that makes every dispatch fail like a provider outage. */
    public static final class FailableProvider implements MessageProvider {
        final MailboxProvider real;
        public final AtomicBoolean failing = new AtomicBoolean();

        FailableProvider(MailboxProvider real) {
            this.real = real;
        }

        @Override
        public void send(Dispatch dispatch) {
            if (failing.get()) {
                throw new IllegalStateException("simulated provider outage");
            }
            real.send(dispatch);
        }
    }

    @TestConfiguration
    public static class Stubs {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api", "gc-internal"));
        }

        @Bean
        @Primary
        FailableProvider failableProvider(org.springframework.beans.factory.ObjectProvider<MailboxProvider> real) {
            return new FailableProvider(real.getIfAvailable());   // absent when the dev mailbox is off
        }
    }

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final String GROWER_SERVICE = TestJwts.service("grower-service").bearer();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected FailableProvider provider;

    protected final JdbcClient owner = JdbcClient.create(DB.ownerDataSource());

    protected ResultActions send(String authorization, Map<String, Object> body, UUID key) throws Exception {
        var request = post("/internal/messages").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return mvc.perform(request);
    }

    /** An OTP SMS in Marathi to a fresh recipient (so rows of different tests never mix in the mailbox). */
    protected static Map<String, Object> otp(String recipient, String code) {
        var body = new LinkedHashMap<String, Object>();
        body.put("channel", "SMS");
        body.put("recipient", recipient);
        body.put("template", "otp.activation");
        body.put("language", "mr");
        body.put("category", "OTP");
        body.put("params", Map.of("code", code, "minutes", "5"));
        return body;
    }

    private static final java.util.concurrent.atomic.AtomicLong NEXT_PHONE = new java.util.concurrent.atomic.AtomicLong(9_100_000_000L);

    protected static String freshPhone() {
        return "+91" + NEXT_PHONE.getAndIncrement();
    }

    protected static Map<String, Object> with(Map<String, Object> body, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected");
        }
        var copy = new LinkedHashMap<>(body);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) {
                copy.remove((String) keyValues[i]);
            } else {
                copy.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return copy;
    }

    protected static ResultActions problem(ResultActions r, int status, String code) throws Exception {
        return r.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value(code));
    }
}
