package com.growercare.notification.config;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.notification.adapters.out.provider.MailboxProvider;
import com.growercare.notification.application.MessageDispatch.ProviderRegistry;
import com.growercare.notification.application.port.MessageProvider;
import com.growercare.notification.domain.TemplateRenderer;
import com.growercare.security.PrincipalResolver;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** notification-service wiring (SPEC-NOT-01 D2, D3, R7, R9, R10). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

    /** R10: refuse to start with a conflicting provider configuration (the exception names every setting). */
    @Bean
    ProviderRegistry providerRegistry(NotificationProperties props, ObjectProvider<MessageProvider> mailbox) {
        props.validate();
        Optional<MessageProvider> provider = props.mailboxEnabled() ? Optional.of(mailbox.getObject()) : Optional.empty();
        return channel -> provider;        // Phase 1: the mailbox serves every channel; real adapters arrive per channel
    }

    /** D3: the mailbox adapter exists only with {@code GC_DEV_MAILBOX=true}. */
    @Bean
    @ConditionalOnProperty(name = "gc.notification.dev-mailbox", havingValue = "true")
    MailboxProvider mailboxProvider(JdbcClient jdbc, NamedSqlRegistry sql) {
        return new MailboxProvider(jdbc, sql);
    }

    @Bean
    TemplateRenderer templateRenderer() {
        return TemplateRenderer.fromClasspath();
    }

    /**
     * R7: there are no end users in notification-service. Every user token resolves to no principal (401); only
     * allow-listed service clients pass, and only on {@code /internal/**} (gc-security's chain).
     */
    @Bean
    PrincipalResolver noEndUsers() {
        return subject -> Optional.empty();
    }

    /**
     * D3/R8/R9: {@code /dev/**} needs no token, so Playwright and people can read codes locally. The routes only exist
     * when the mailbox controller is registered ({@code GC_DEV_MAILBOX=true}); otherwise every {@code /dev} path is 404.
     */
    @Bean
    @Order(1)
    SecurityFilterChain devMailboxChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/dev/**")                 // GET only; CSRF protection stays on (it only guards unsafe methods)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
